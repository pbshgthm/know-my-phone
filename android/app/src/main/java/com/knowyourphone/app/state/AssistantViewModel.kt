package com.knowyourphone.app.state

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.knowyourphone.app.KypAccessibilityService
import com.knowyourphone.app.audio.AudioPlayer
import com.knowyourphone.app.audio.AudioRecorder
import com.knowyourphone.app.model.HighlightTarget
import com.knowyourphone.app.network.*
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

    private val audioRecorder = AudioRecorder()
    private val audioPlayer = AudioPlayer(context)

    private var sessionId: String = UUID.randomUUID().toString()
    private var languageCode: String = getPreferredLanguageCode()

    // The binary frame we expect after an "answer" text frame
    // Both fields set synchronously on OkHttp reader thread to avoid race with binary frame
    @Volatile
    private var expectingMp3Binary = false
    @Volatile
    private var pendingHighlights: List<HighlightTarget> = emptyList()
    @Volatile
    private var pendingScreenshotRequest = false

    private val wsClient: WsClient = WsClient(
        onMessage = { text -> handleServerMessage(text) },
        onBinaryMessage = { data -> handleBinaryMessage(data) },
        onConnected = {
            val wasReconnecting = _reconnecting.value
            _connected.value = true
            _reconnecting.value = false
            if (wasReconnecting) {
                _errorMessage.value = "Connected"
            }
            sendHello()
            sendLanguage()
            Log.d(TAG, "Connected to server")
        },
        onDisconnected = {
            scope.launch(Dispatchers.Main) {
                _connected.value = false
                _errorMessage.value = "Connection lost"
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
                _errorMessage.value = "Reconnecting..."
            }
        }
    )

    private var highlightDismissJob: Job? = null

    private fun sendHello() {
        wsClient.sendText(MessageParser.toJson(HelloMessage(sessionId = sessionId)))
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
                expectingMp3Binary = false
                startListening()
            }
            AssistantState.SPEAKING -> {
                audioPlayer.stop()
                sendCancel()
                expectingMp3Binary = false
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
                _state.value = AssistantState.IDLE
            }
            AssistantState.THINKING -> {
                sendCancel()
                expectingMp3Binary = false
                _state.value = AssistantState.IDLE
            }
            AssistantState.NEED_SCREENSHOT -> {
                onScreenshotDecline()
            }
            AssistantState.SPEAKING -> {
                audioPlayer.stop()
                _highlights.value = emptyList()
                highlightDismissJob?.cancel()
                pendingHighlights = emptyList()
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
        val started = audioRecorder.startRecording(scope)
        if (!started) {
            Log.e(TAG, "Failed to start recording (mic unavailable)")
            _errorMessage.value = "Mic unavailable"
            _state.value = AssistantState.IDLE
            return
        }
        _state.value = AssistantState.LISTENING
    }

    private fun stopListeningAndSend() {
        val pcmData = audioRecorder.stopRecording()
        _state.value = AssistantState.THINKING

        if (pcmData.isEmpty()) {
            Log.w(TAG, "No audio data recorded")
            _state.value = AssistantState.IDLE
            return
        }

        if (!wsClient.isConnected()) {
            Log.e(TAG, "Cannot send audio: WebSocket not connected")
            _errorMessage.value = "Send failed"
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
                    _errorMessage.value = "Send failed"
                    _state.value = AssistantState.IDLE
                }
            } else {
                Log.d(TAG, "Sent audio: ${wavData.size} bytes WAV")
            }
        }
    }

    private fun handleServerMessage(json: String) {
        val message = MessageParser.parseServerMessage(json) ?: return

        // For Answer messages, set flags synchronously on OkHttp's reader thread
        // BEFORE the binary frame callback fires (OkHttp reader is single-threaded)
        if (message is ServerMessage.Answer) {
            Log.d(TAG, "Answer: ${message.text}, highlights: ${message.highlights.size}, hasAudio: ${message.hasAudio}")
            pendingHighlights = message.highlights
            pendingScreenshotRequest = false
            if (message.hasAudio) {
                expectingMp3Binary = true
                Log.d(TAG, "Set expectingMp3Binary=true, waiting for audio binary")
            } else {
                expectingMp3Binary = false
                Log.d(TAG, "No audio, transitioning directly")
                _errorMessage.value = "Audio unavailable"
                // No audio coming — show highlights independently and go to IDLE
                scope.launch(Dispatchers.Main) {
                    if (pendingHighlights.isNotEmpty()) {
                        _highlights.value = pendingHighlights
                        scheduleHighlightDismiss()
                    }
                    _state.value = AssistantState.IDLE
                }
            }
            return
        }

        if (message is ServerMessage.ScreenshotRequest) {
            Log.d(TAG, "Screenshot request: ${message.reason}, hasAudio=${message.hasAudio}")
            _screenshotReason.value = message.reason
            pendingHighlights = emptyList()
            pendingScreenshotRequest = true
            if (message.hasAudio) {
                expectingMp3Binary = true
                Log.d(TAG, "Set expectingMp3Binary=true for screenshot request")
            } else {
                expectingMp3Binary = false
                scope.launch(Dispatchers.Main) {
                    _state.value = AssistantState.NEED_SCREENSHOT
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

                is ServerMessage.Answer -> { /* handled above */ }
                is ServerMessage.ScreenshotRequest -> { /* handled above */ }

                is ServerMessage.Error -> {
                    Log.e(TAG, "Server error: ${message.message}")
                    _errorMessage.value = message.message
                    _state.value = AssistantState.IDLE
                }

                is ServerMessage.Cancelled -> {
                    Log.d(TAG, "Server acknowledged cancellation")
                    // State already updated by press handlers, nothing to do
                }
            }
        }
    }

    private fun handleBinaryMessage(data: ByteArray) {
        Log.d(TAG, "handleBinaryMessage: ${data.size} bytes, expectingMp3Binary=$expectingMp3Binary")
        if (expectingMp3Binary) {
            expectingMp3Binary = false
            scope.launch(Dispatchers.Main) {
                Log.d(TAG, "Setting state to SPEAKING and playing audio")
                _state.value = AssistantState.SPEAKING
                audioPlayer.playMp3Bytes(data) {
                    // Playback complete
                    scope.launch(Dispatchers.Main) {
                        if (pendingScreenshotRequest) {
                            pendingScreenshotRequest = false
                            _state.value = AssistantState.NEED_SCREENSHOT
                        } else {
                            // Show highlights independently, pill goes to IDLE
                            if (pendingHighlights.isNotEmpty()) {
                                _highlights.value = pendingHighlights
                                scheduleHighlightDismiss()
                            }
                            _state.value = AssistantState.IDLE
                        }
                    }
                }
            }
        }
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
        _state.value = AssistantState.THINKING

        val accessibility = KypAccessibilityService.instance
        if (accessibility == null) {
            Log.e(TAG, "Accessibility service not running")
            _state.value = AssistantState.IDLE
            return
        }

        scope.launch(Dispatchers.Main) {
            // Wait for layout pass after hiding pill, then add buffer
            val dot = com.knowyourphone.app.OverlayService.instance?.getDotView()
            if (dot != null) {
                // Use post to wait for next layout pass
                suspendCancellableCoroutine { cont ->
                    dot.post { cont.resume(Unit) {} }
                }
            }
            delay(200L)

            // Collect UI tree
            val uiTree = accessibility.collectUiTree()

            // Capture screenshot
            accessibility.captureScreenshot { bitmap ->
                scope.launch(Dispatchers.IO) io@{
                    if (bitmap == null || uiTree == null) {
                        Log.e(TAG, "Failed to capture screenshot or UI tree")
                        withContext(Dispatchers.Main) { _state.value = AssistantState.IDLE }
                        return@io
                    }

                    // Encode bitmap to JPEG base64
                    val baos = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                    bitmap.recycle()
                    val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

                    // Convert UI tree to JsonObject
                    val uiTreeJson = gson.toJsonTree(uiTree).asJsonObject

                    val msg = ScreenshotResponseMessage(
                        screenshot = base64,
                        uiTree = uiTreeJson
                    )
                    val sent = wsClient.sendText(MessageParser.toJson(msg))
                    if (!sent) {
                        Log.e(TAG, "Failed to send screenshot response")
                        withContext(Dispatchers.Main) { _state.value = AssistantState.IDLE }
                    } else {
                        Log.d(TAG, "Sent screenshot response")
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

    fun resetSession() {
        sendCancel()
        // Generate a fresh session ID
        sessionId = UUID.randomUUID().toString()
        wsClient.sendText(MessageParser.toJson(ResetSessionMessage()))
        sendHello()
        sendLanguage()
        expectingMp3Binary = false
        pendingScreenshotRequest = false
        pendingHighlights = emptyList()
        _highlights.value = emptyList()
        _state.value = AssistantState.IDLE
    }

    fun setLanguage(code: String) {
        languageCode = code
        sendLanguage()
    }

    private fun getPreferredLanguageCode(): String {
        val prefs = context.getSharedPreferences("kyp_prefs", Context.MODE_PRIVATE)
        return prefs.getString("language_code", "en") ?: "en"
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun destroy() {
        highlightDismissJob?.cancel()
        audioPlayer.stop()
        if (audioRecorder.isCurrentlyRecording()) {
            audioRecorder.stopRecording()
        }
        wsClient.disconnect()
        scope.cancel()
    }
}
