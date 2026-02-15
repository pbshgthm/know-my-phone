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

    // The binary frame we expect after an "answer" text frame
    // Both fields set synchronously on OkHttp reader thread to avoid race with binary frame
    @Volatile
    private var expectingMp3Binary = false
    @Volatile
    private var pendingHighlights: List<HighlightTarget> = emptyList()

    private val wsClient = WsClient(
        onMessage = { text -> handleServerMessage(text) },
        onBinaryMessage = { data -> handleBinaryMessage(data) },
        onConnected = {
            val wasReconnecting = _reconnecting.value
            _connected.value = true
            _reconnecting.value = false
            if (wasReconnecting) {
                _errorMessage.value = "Connected"
            }
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

    fun connect() {
        wsClient.connect(serverUrl)
    }

    fun disconnect() {
        wsClient.disconnect()
        _connected.value = false
    }

    /**
     * Called when user taps the dot.
     * IDLE -> start listening
     * LISTENING -> stop listening and send audio
     * THINKING -> cancel current request, start listening
     * SPEAKING -> stop playback, cancel backend, start listening
     * NEED_SCREENSHOT -> decline screenshot, start listening
     * HIGHLIGHTING -> dismiss highlights, go idle
     */
    fun onDotTap() {
        when (_state.value) {
            AssistantState.IDLE -> startListening()
            AssistantState.LISTENING -> stopListeningAndSend()
            AssistantState.THINKING -> {
                // Cancel current request and start new recording
                sendCancel()
                expectingMp3Binary = false
                startListening()
            }
            AssistantState.SPEAKING -> {
                // Stop audio, cancel any remaining backend work, start new recording
                audioPlayer.stop()
                sendCancel()
                expectingMp3Binary = false
                startListening()
            }
            AssistantState.NEED_SCREENSHOT -> {
                // Decline screenshot and start new recording
                sendCancel()
                startListening()
            }
            AssistantState.HIGHLIGHTING -> {
                _highlights.value = emptyList()
                highlightDismissJob?.cancel()
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
            if (message.hasAudio) {
                expectingMp3Binary = true
                Log.d(TAG, "Set expectingMp3Binary=true, waiting for audio binary")
            } else {
                expectingMp3Binary = false
                Log.d(TAG, "No audio, transitioning directly")
                _errorMessage.value = "Audio unavailable"
                // No audio coming — go to highlights or idle
                scope.launch(Dispatchers.Main) {
                    if (pendingHighlights.isNotEmpty()) {
                        _highlights.value = pendingHighlights
                        _state.value = AssistantState.HIGHLIGHTING
                        scheduleHighlightDismiss()
                    } else {
                        _state.value = AssistantState.IDLE
                    }
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

                is ServerMessage.Error -> {
                    Log.e(TAG, "Server error: ${message.message}")
                    _errorMessage.value = message.message
                    _state.value = AssistantState.IDLE
                }

                is ServerMessage.Cancelled -> {
                    Log.d(TAG, "Server acknowledged cancellation")
                    // State already updated by onDotTap, nothing to do
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
                        if (pendingHighlights.isNotEmpty()) {
                            _highlights.value = pendingHighlights
                            _state.value = AssistantState.HIGHLIGHTING
                            scheduleHighlightDismiss()
                        } else {
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
            _state.value = AssistantState.IDLE
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

        // Collect UI tree
        val uiTree = accessibility.collectUiTree()

        // Capture screenshot
        accessibility.captureScreenshot { bitmap ->
            scope.launch(Dispatchers.IO) {
                if (bitmap == null || uiTree == null) {
                    Log.e(TAG, "Failed to capture screenshot or UI tree")
                    withContext(Dispatchers.Main) { _state.value = AssistantState.IDLE }
                    return@launch
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
