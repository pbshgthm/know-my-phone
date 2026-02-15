package com.knowyourphone.app.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

class WsClient(
    private val onMessage: (String) -> Unit,
    private val onBinaryMessage: (ByteArray) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onReconnecting: ((Boolean) -> Unit)? = null
) {
    companion object {
        private const val TAG = "WsClient"
        private const val MAX_BACKOFF_MS = 30_000L
        private const val INITIAL_BACKOFF_MS = 1_000L
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val handler = Handler(Looper.getMainLooper())

    private var webSocket: WebSocket? = null
    @Volatile
    private var connected = false
    @Volatile
    var isReconnecting = false
        private set

    private var serverUrl: String = ""
    private var manuallyDisconnected = false
    private var currentBackoffMs = INITIAL_BACKOFF_MS
    private var reconnectRunnable: Runnable? = null

    fun connect(url: String) {
        serverUrl = url
        manuallyDisconnected = false
        currentBackoffMs = INITIAL_BACKOFF_MS
        cancelReconnect()
        doConnect()
    }

    private fun doConnect() {
        Log.d(TAG, "Connecting to WebSocket at: $serverUrl")
        val request = Request.Builder()
            .url(serverUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected successfully to $serverUrl")
                connected = true
                isReconnecting = false
                currentBackoffMs = INITIAL_BACKOFF_MS
                onReconnecting?.invoke(false)
                onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received text (${text.length} chars): $text")
                onMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d(TAG, "Received binary: ${bytes.size} bytes")
                onBinaryMessage(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
                connected = false
                onDisconnected()
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure for $serverUrl: ${t.message}")
                response?.let {
                    Log.e(TAG, "Response code: ${it.code}, message: ${it.message}")
                    it.body?.string()?.let { body ->
                        Log.e(TAG, "Response body: $body")
                    }
                }
                connected = false
                onDisconnected()
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (manuallyDisconnected) return

        isReconnecting = true
        onReconnecting?.invoke(true)

        val delay = currentBackoffMs
        Log.d(TAG, "Scheduling reconnect in ${delay}ms")

        val runnable = Runnable {
            if (!manuallyDisconnected && !connected) {
                Log.d(TAG, "Attempting reconnect...")
                doConnect()
            }
        }
        reconnectRunnable = runnable
        handler.postDelayed(runnable, delay)

        // Exponential backoff with cap
        currentBackoffMs = (currentBackoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null
        isReconnecting = false
        onReconnecting?.invoke(false)
    }

    fun sendText(text: String): Boolean {
        if (!connected) return false
        return webSocket?.send(text) ?: false
    }

    fun sendBinary(data: ByteArray): Boolean {
        if (!connected) return false
        return webSocket?.send(data.toByteString()) ?: false
    }

    fun disconnect() {
        manuallyDisconnected = true
        cancelReconnect()
        connected = false
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
    }

    fun isConnected(): Boolean = connected
}
