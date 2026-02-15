package com.knowyourphone.app.network

import android.util.Log
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

class WsClient(
    private val onMessage: (String) -> Unit,
    private val onBinaryMessage: (ByteArray) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit
) {
    companion object {
        private const val TAG = "WsClient"
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    @Volatile
    private var connected = false

    fun connect(url: String) {
        Log.d(TAG, "Connecting to WebSocket at: $url")
        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected successfully to $url")
                connected = true
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
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure for $url: ${t.message}")
                response?.let {
                    Log.e(TAG, "Response code: ${it.code}, message: ${it.message}")
                    it.body?.string()?.let { body ->
                        Log.e(TAG, "Response body: $body")
                    }
                }
                connected = false
                onDisconnected()
            }
        })
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
        connected = false
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
    }

    fun isConnected(): Boolean = connected
}
