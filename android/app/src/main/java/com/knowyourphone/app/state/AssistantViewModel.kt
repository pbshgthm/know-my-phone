package com.knowyourphone.app.state

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.knowyourphone.app.KypAccessibilityService
import com.knowyourphone.app.R
import com.knowyourphone.app.audio.AudioRecorder
import com.knowyourphone.app.audio.PromptAudioPlayer
import com.knowyourphone.app.audio.StreamingAudioPlayer
import com.knowyourphone.app.i18n.LanguageManager
import kotlinx.coroutines.channels.Channel
import com.knowyourphone.app.model.HighlightTarget
import com.knowyourphone.app.network.*
import com.knowyourphone.app.privacy.PiiRedactor
import com.knowyourphone.app.util.WavEncoder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.util.UUID

class AssistantViewModel(
    private val context: Context,
    private val serverUrl: String = "ws://localhost:8765"
) {
    companion object {
        private const val TAG = "AssistantVM"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val gson = Gson()

    private val _state = MutableStateFlow(AssistantState.IDLE)
    val state: StateFlow<AssistantState> = _state

    private val _highlights = MutableStateFlow<List<HighlightTarget>>(emptyList())
    val highlights: StateFlow<List<HighlightTarget>> = _highlights

    private val _screenshotReason = MutableStateFlow("")
    val screenshotReason: StateFlow<String> = _screenshotReason

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _reconnecting = MutableStateFlow(false)
    val reconnecting: StateFlow<Boolean> = _reconnecting

    private val _autoScreenshot = MutableStateFlow(false)
    val autoScreenshot: StateFlow<Boolean> = _autoScreenshot

    private val _inputLevel = MutableStateFlow(0f)
    val inputLevel: StateFlow<Float> = _inputLevel

    private val _playbackLevel = MutableStateFlow(0f)
    val playbackLevel: StateFlow<Float> = _playbackLevel

    private val audioRecorder = AudioRecorder()
    private val promptPlayer = PromptAudioPlayer(context)

    private var sessionId: String = UUID.randomUUID().toString()
    private var languageCode: String = getPreferredLanguageCode()
    private val clientId: String = getOrCreateClientId()

    init {
        LanguageManager.init(context.applicationContext)
        _autoScreenshot.value = context.getSharedPreferences("kyp_prefs", Context.MODE_PRIVATE)
            .getBoolean("auto_screenshot", false)
    }

    @Volatile
    private var pendingHighlights: List<HighlightTarget> = emptyList()
    @Volatile
    private var smoothedInputLevel = 0f
    @Volatile
    private var smoothedPlaybackLevel = 0f

    // Streaming audio state
    @Volatile
    private var streamingAudio = false
    private var streamingPlayer: StreamingAudioPlayer? = null
    private var pcmChannel: Channel<ByteArray>? = null
    private var pcmConsumerJob: Job? = null

    private val wsClient: WsClient = WsClient(
        onMessage = { text -> handleServerMessage(text) },
        onBinaryMessage = { data -> handleBinaryMessage(data) },
        onConnected = {
            val wasReconnecting = _reconnecting.value
            _connected.value = true
            _reconnecting.value = false
            if (wasReconnecting) {
                _errorMessage.value = appStrings().connected
            }
            sendHello()
            sendLanguage()
            sendAutoScreenshotSetting()
            Log.d(TAG, "Connected to server")
        },
        onDisconnected = {
            scope.launch(Dispatchers.Main) {
                _connected.value = false
                _errorMessage.value = appStrings().connectionLost
                // Reset state to IDLE if we get disconnected while waiting
                if (_state.value == AssistantState.THINKING || _state.value == AssistantState.LISTENING) {
                    _state.value = AssistantState.IDLE
                }
                Log.d(TAG, "Disconnected from server, state reset to IDLE")
            }
        },
        onReconnecting = { reconnecting ->
            _reconnecting.value = reconnecting
            if (reconnecting) {
                _errorMessage.value = appStrings().reconnecting
            }
        }
    )

    private var highlightDismissJob: Job? = null

    private fun sendHello() {
        val device = DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE
        )
        wsClient.sendText(MessageParser.toJson(HelloMessage(sessionId = sessionId, clientId = clientId, deviceInfo = device)))
    }

    private fun sendLanguage() {
        wsClient.sendText(MessageParser.toJson(SetLanguageMessage(languageCode = languageCode)))
    }

    fun connect() {
        wsClient.connect(serverUrl)
    }

    fun disconnect() {
        wsClient.disconnect()
        _connected.value = false
        _inputLevel.value = 0f
        smoothedInputLevel = 0f
        _playbackLevel.value = 0f
        smoothedPlaybackLevel = 0f
    }

    /**
     * Press-and-hold start.
     * IDLE -> start listening
     * THINKING/SPEAKING -> cancel and start listening
     * NEED_SCREENSHOT -> no-op (X button handles cancel)
     */
    fun onPressStart() {
        when (_state.value) {
            AssistantState.IDLE -> startListening()
            AssistantState.LISTENING -> { /* already listening */ }
            AssistantState.THINKING -> {
                sendCancel()
                stopStreamingPlayer()
                startListening()
            }
            AssistantState.SPEAKING -> {
                stopStreamingPlayer()
                sendCancel()
                _playbackLevel.value = 0f
                smoothedPlaybackLevel = 0f
                startListening()
            }
            AssistantState.NEED_SCREENSHOT -> {
                // No-op: X button handles screenshot cancel
            }
        }
    }

    /**
     * Release press.
     * LISTENING -> stop and send audio
     */
    fun onPressEnd() {
        if (_state.value == AssistantState.LISTENING) {
            stopListeningAndSend()
        }
    }

    /**
     * Cancel recording without sending (e.g., drag).
     */
    fun cancelRecordingIfListening() {
        if (_state.value != AssistantState.LISTENING) return
        audioRecorder.cancelRecording()
        _inputLevel.value = 0f
        smoothedInputLevel = 0f
        _playbackLevel.value = 0f
        smoothedPlaybackLevel = 0f
        _state.value = AssistantState.IDLE
    }

    /**
     * X button cancel action — handles all states.
     */
    fun onCancel() {
        when (_state.value) {
            AssistantState.IDLE -> {
                // Hide pill / stop service — handled by OverlayService
            }
            AssistantState.LISTENING -> {
                audioRecorder.cancelRecording()
                _inputLevel.value = 0f
                smoothedInputLevel = 0f
                _playbackLevel.value = 0f
                smoothedPlaybackLevel = 0f
                _state.value = AssistantState.IDLE
            }
            AssistantState.THINKING -> {
                sendCancel()
                stopStreamingPlayer()
                _inputLevel.value = 0f
                smoothedInputLevel = 0f
                _playbackLevel.value = 0f
                smoothedPlaybackLevel = 0f
                _state.value = AssistantState.IDLE
            }
            AssistantState.NEED_SCREENSHOT -> {
                promptPlayer.stop()
                sendCancel()
                _state.value = AssistantState.IDLE
            }
            AssistantState.SPEAKING -> {
                stopStreamingPlayer()
                _highlights.value = emptyList()
                highlightDismissJob?.cancel()
                pendingHighlights = emptyList()
        
                _inputLevel.value = 0f
                smoothedInputLevel = 0f
                _playbackLevel.value = 0f
                smoothedPlaybackLevel = 0f
                _state.value = AssistantState.IDLE
            }
        }
    }

    private fun sendCancel() {
        wsClient.sendText(MessageParser.toJson(CancelMessage()))
    }

    private fun startListening() {
        if (!wsClient.isConnected()) {
            Log.w(TAG, "Not connected, attempting to reconnect...")
            connect()
        }
        _highlights.value = emptyList()
        highlightDismissJob?.cancel()

        _inputLevel.value = 0f
        smoothedInputLevel = 0f
        _playbackLevel.value = 0f
        smoothedPlaybackLevel = 0f
        val started = audioRecorder.startRecording(scope) { level ->
            val smoothed = smoothedInputLevel * 0.15f + level * 0.85f
            smoothedInputLevel = smoothed
            _inputLevel.value = smoothed
        }
        if (!started) {
            Log.e(TAG, "Failed to start recording (mic unavailable)")
            _errorMessage.value = appStrings().micUnavailable
            _inputLevel.value = 0f
            smoothedInputLevel = 0f
            _playbackLevel.value = 0f
            smoothedPlaybackLevel = 0f
            _state.value = AssistantState.IDLE
            return
        }
        _state.value = AssistantState.LISTENING
    }

    private fun stopListeningAndSend() {
        val pcmData = audioRecorder.stopRecording()
        _inputLevel.value = 0f
        smoothedInputLevel = 0f
        _playbackLevel.value = 0f
        smoothedPlaybackLevel = 0f
        _state.value = AssistantState.THINKING

        if (pcmData.isEmpty()) {
            Log.w(TAG, "No audio data recorded")
            _state.value = AssistantState.IDLE
            return
        }

        if (!wsClient.isConnected()) {
            Log.e(TAG, "Cannot send audio: WebSocket not connected")
            _errorMessage.value = appStrings().sendFailed
            _state.value = AssistantState.IDLE
            return
        }

        scope.launch(Dispatchers.IO) {
            val wavData = WavEncoder.encode(pcmData)

            // Send text metadata frame first
            val meta = MessageParser.toJson(AudioDataMessage())
            val metaSent = wsClient.sendText(meta)

            // Then send binary WAV frame
            val dataSent = wsClient.sendBinary(wavData)

            if (!metaSent || !dataSent) {
                Log.e(TAG, "Failed to send audio frames")
                withContext(Dispatchers.Main) {
                    _errorMessage.value = appStrings().sendFailed
                    _inputLevel.value = 0f
                    smoothedInputLevel = 0f
                    _playbackLevel.value = 0f
                    smoothedPlaybackLevel = 0f
                    _state.value = AssistantState.IDLE
                }
            } else {
                Log.d(TAG, "Sent audio: ${wavData.size} bytes WAV")
                if (_autoScreenshot.value) {
                    Log.d(TAG, "Auto-screenshot ON, capturing screenshot immediately")
                    withContext(Dispatchers.Main) {
                        captureAndSendScreenshot()
                    }
                }
            }
        }
    }

    private fun handleServerMessage(json: String) {
        val message = MessageParser.parseServerMessage(json) ?: return

        // AnswerStart: begin streaming audio mode — set flag on OkHttp reader thread
        if (message is ServerMessage.AnswerStart) {
            Log.d(TAG, "AnswerStart: streaming audio begins")

            // Create channel SYNCHRONOUSLY on the reader thread so binary frames
            // arriving immediately after this message are buffered (not dropped).
            val channel = Channel<ByteArray>(Channel.UNLIMITED)
            pcmChannel = channel
            streamingAudio = true  // must be set AFTER channel is assigned

            // Create player + consumer on Main (channel buffers until ready)
            scope.launch(Dispatchers.Main) {
                _inputLevel.value = 0f
                smoothedInputLevel = 0f
                _playbackLevel.value = 0f
                smoothedPlaybackLevel = 0f
                _state.value = AssistantState.SPEAKING

                val player = StreamingAudioPlayer()
                streamingPlayer = player
                player.start()
                // Set callback AFTER start() — start() calls stop() which nulls onLevelChanged
                player.onLevelChanged = { level ->
                    val smoothed = smoothedPlaybackLevel * 0.20f + level * 0.80f
                    smoothedPlaybackLevel = smoothed
                    _playbackLevel.value = smoothed
                }

                // Single consumer coroutine writes PCM chunks in order.
                // All chunks sent before this point are already buffered in channel.
                pcmConsumerJob = scope.launch(Dispatchers.IO) {
                    for (chunk in channel) {
                        player.writeChunk(chunk)
                    }
                }
            }
            return
        }

        // AnswerEnd: streaming complete — finalize playback
        if (message is ServerMessage.AnswerEnd) {
            Log.d(TAG, "AnswerEnd: text=${message.text.take(50)}, highlights=${message.highlights.size}")
            streamingAudio = false
            pendingHighlights = message.highlights

            scope.launch(Dispatchers.Main) {
                // Close the channel so the consumer finishes
                pcmChannel?.close()
                pcmConsumerJob?.join()
                pcmChannel = null
                pcmConsumerJob = null

                // Tell player all data is written — it polls for completion
                val player = streamingPlayer
                if (player != null) {
                    player.onCompletion = {
                        scope.launch(Dispatchers.Main) {
                            // Show highlights after audio playback completes
                            if (pendingHighlights.isNotEmpty()) {
                                _highlights.value = pendingHighlights
                                scheduleHighlightDismiss()
                            }
                            _inputLevel.value = 0f
                            smoothedInputLevel = 0f
                            _playbackLevel.value = 0f
                            smoothedPlaybackLevel = 0f
                            _state.value = AssistantState.IDLE
                            streamingPlayer = null
                        }
                    }
                    player.finish()
                } else {
                    // No player (answer_end without answer_start = text-only error recovery)
                    if (pendingHighlights.isNotEmpty()) {
                        _highlights.value = pendingHighlights
                        scheduleHighlightDismiss()
                    }
                    _inputLevel.value = 0f
                    smoothedInputLevel = 0f
                    _playbackLevel.value = 0f
                    smoothedPlaybackLevel = 0f
                    _state.value = AssistantState.IDLE
                }
            }
            return
        }

        // ScreenshotRequest: always transitions to NEED_SCREENSHOT
        if (message is ServerMessage.ScreenshotRequest) {
            Log.d(TAG, "Screenshot request: ${message.reason}")
            _screenshotReason.value = message.reason
            scope.launch(Dispatchers.Main) {
                _state.value = AssistantState.NEED_SCREENSHOT
                // Play voice prompt when manual screenshot mode (auto-screenshot off)
                if (!_autoScreenshot.value) {
                    promptPlayer.play(R.raw.share_screen)
                }
            }
            return
        }

        scope.launch(Dispatchers.Main) {
            when (message) {
                is ServerMessage.Transcript -> {
                    Log.d(TAG, "Transcript: ${message.text}")
                    // Stay in THINKING state
                }

                is ServerMessage.NeedScreenshot -> {
                    Log.d(TAG, "Need screenshot: ${message.reason}")
                    _screenshotReason.value = message.reason
                    _state.value = AssistantState.NEED_SCREENSHOT
                }

                is ServerMessage.AnswerStart -> { /* handled above */ }
                is ServerMessage.Highlights -> { /* no-op: highlights come via answer_end */ }
                is ServerMessage.AnswerEnd -> { /* handled above */ }
                is ServerMessage.ScreenshotRequest -> { /* handled above */ }

                is ServerMessage.Error -> {
                    Log.e(TAG, "Server error: ${message.message}")
                    _errorMessage.value = message.message
                    stopStreamingPlayer()
                    _inputLevel.value = 0f
                    smoothedInputLevel = 0f
                    _playbackLevel.value = 0f
                    smoothedPlaybackLevel = 0f
                    _state.value = AssistantState.IDLE
                }

                is ServerMessage.Cancelled -> {
                    Log.d(TAG, "Server acknowledged cancellation")
                }
            }
        }
    }

    private fun handleBinaryMessage(data: ByteArray) {
        if (streamingAudio) {
            pcmChannel?.trySend(data)
        } else {
            Log.w(TAG, "Unexpected binary message: ${data.size} bytes (not streaming)")
        }
    }

    private fun stopStreamingPlayer() {
        streamingAudio = false
        pcmChannel?.close()
        pcmConsumerJob?.cancel()
        pcmChannel = null
        pcmConsumerJob = null
        streamingPlayer?.stop()
        streamingPlayer = null
    }

    private fun scheduleHighlightDismiss() {
        highlightDismissJob?.cancel()
        highlightDismissJob = scope.launch {
            delay(10_000)
            _highlights.value = emptyList()
        }
    }

    /**
     * User confirmed screenshot. Capture and send.
     */
    fun onScreenshotConfirm() {
        promptPlayer.stop()
        _state.value = AssistantState.THINKING
        captureAndSendScreenshot()
    }

    /**
     * Capture screenshot and send to server.
     */
    private fun captureAndSendScreenshot() {
        val accessibility = KypAccessibilityService.instance
        if (accessibility == null) {
            Log.e(TAG, "Accessibility service not running")
            _state.value = AssistantState.IDLE
            return
        }

        scope.launch(Dispatchers.Main) {
            // Collect UI tree
            val uiTree = accessibility.collectUiTree()

            // Capture screenshot (pill overlay stays visible — backend prompt knows to ignore it)
            accessibility.captureScreenshot { bitmap ->
                scope.launch(Dispatchers.IO) io@{
                    if (bitmap == null || uiTree == null) {
                        Log.e(TAG, "Failed to capture screenshot or UI tree")
                        withContext(Dispatchers.Main) { _state.value = AssistantState.IDLE }
                        return@io
                    }

                    // Redact PII from UI tree
                    val redactionResult = PiiRedactor.redactUiTree(uiTree)
                    val hasPii = redactionResult.nodeRedactions.isNotEmpty()
                    if (hasPii) {
                        Log.d(TAG, "PII redacted: ${redactionResult.summaries.size} types found")
                    }

                    // Redact PII regions on bitmap if needed
                    val finalBitmap = if (hasPii) {
                        val redacted = PiiRedactor.redactBitmap(bitmap, redactionResult.nodeRedactions)
                        bitmap.recycle()
                        redacted
                    } else {
                        bitmap
                    }

                    // Encode bitmap to JPEG base64
                    val baos = ByteArrayOutputStream()
                    finalBitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                    finalBitmap.recycle()
                    val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

                    // Convert redacted UI tree to JsonObject
                    val uiTreeJson = gson.toJsonTree(redactionResult.redactedSnapshot).asJsonObject

                    val msg = ScreenshotResponseMessage(
                        screenshot = base64,
                        uiTree = uiTreeJson,
                        redacted = hasPii,
                        redactions = if (hasPii) redactionResult.summaries else emptyList()
                    )
                    val sent = wsClient.sendText(MessageParser.toJson(msg))
                    if (!sent) {
                        Log.e(TAG, "Failed to send screenshot response")
                        withContext(Dispatchers.Main) { _state.value = AssistantState.IDLE }
                    } else {
                        Log.d(TAG, "Sent screenshot response (redacted=$hasPii)")
                    }
                }
            }
        }
    }

    /**
     * User declined screenshot.
     */
    fun onScreenshotDecline() {
        _state.value = AssistantState.THINKING
        val msg = ScreenshotDeclinedMessage()
        val sent = wsClient.sendText(MessageParser.toJson(msg))
        if (!sent) {
            _state.value = AssistantState.IDLE
        }
    }

    fun setLanguage(code: String) {
        languageCode = code
        context.getSharedPreferences("kyp_prefs", Context.MODE_PRIVATE)
            .edit().putString("language_code", code).apply()
        sendLanguage()
    }

    fun setAutoScreenshot(enabled: Boolean) {
        _autoScreenshot.value = enabled
        context.getSharedPreferences("kyp_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("auto_screenshot", enabled).apply()
        sendAutoScreenshotSetting()
    }

    private fun sendAutoScreenshotSetting() {
        wsClient.sendText(MessageParser.toJson(SetAutoScreenshotMessage(enabled = _autoScreenshot.value)))
    }

    private fun getOrCreateClientId(): String {
        val prefs = context.getSharedPreferences("kyp_prefs", Context.MODE_PRIVATE)
        val existing = prefs.getString("client_id", null)
        if (existing != null) return existing
        val newId = UUID.randomUUID().toString()
        prefs.edit().putString("client_id", newId).apply()
        Log.d(TAG, "Generated new clientId: $newId")
        return newId
    }

    private fun getPreferredLanguageCode(): String {
        val prefs = context.getSharedPreferences("kyp_prefs", Context.MODE_PRIVATE)
        return prefs.getString("language_code", "en") ?: "en"
    }

    private fun appStrings() = LanguageManager.getAppStrings(languageCode)

    fun clearError() {
        _errorMessage.value = null
    }

    fun destroy() {
        highlightDismissJob?.cancel()
        stopStreamingPlayer()
        promptPlayer.stop()
        if (audioRecorder.isCurrentlyRecording()) {
            audioRecorder.stopRecording()
        }
        _inputLevel.value = 0f
        smoothedInputLevel = 0f
        _playbackLevel.value = 0f
        smoothedPlaybackLevel = 0f
        wsClient.disconnect()
        scope.cancel()
    }
}
