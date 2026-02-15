package com.knowyourphone.app.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.knowyourphone.app.model.HighlightTarget
import com.knowyourphone.app.privacy.RedactionSummary

// --- Client -> Server messages ---

data class AudioDataMessage(
    val type: String = "audio_data",
    val format: String = "wav",
    val sampleRate: Int = 16000
)

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidVersion: String
)

data class HelloMessage(
    val type: String = "hello",
    val sessionId: String,
    val clientId: String? = null,
    val deviceInfo: DeviceInfo
)

data class SetLanguageMessage(
    val type: String = "set_language",
    val languageCode: String
)

data class ScreenshotResponseMessage(
    val type: String = "screenshot_response",
    val screenshot: String, // base64 JPEG
    val uiTree: JsonObject,
    val redacted: Boolean = false,
    val redactions: List<RedactionSummary> = emptyList()
)

data class ScreenshotDeclinedMessage(
    val type: String = "screenshot_declined"
)

data class CancelMessage(
    val type: String = "cancel"
)

data class SetAutoScreenshotMessage(
    val type: String = "set_auto_screenshot",
    val enabled: Boolean
)

// --- Server -> Client messages ---

sealed class ServerMessage {
    data class Transcript(val text: String) : ServerMessage()
    data class NeedScreenshot(val reason: String) : ServerMessage()
    data class ScreenshotRequest(val text: String, val reason: String, val hasAudio: Boolean) : ServerMessage()
    object AnswerStart : ServerMessage()
    data class Highlights(val highlights: List<HighlightTarget>) : ServerMessage()
    data class AnswerEnd(val text: String, val highlights: List<HighlightTarget>) : ServerMessage()
    data class Error(val message: String) : ServerMessage()
    object Cancelled : ServerMessage()
}

object MessageParser {
    private val gson = Gson()

    fun parseServerMessage(json: String): ServerMessage? {
        return try {
            val obj = gson.fromJson(json, JsonObject::class.java)
            when (obj.get("type")?.asString) {
                "transcript" -> ServerMessage.Transcript(
                    text = obj.get("text")?.asString ?: ""
                )
                "need_screenshot" -> ServerMessage.NeedScreenshot(
                    reason = obj.get("reason")?.asString ?: ""
                )
                "screenshot_request" -> ServerMessage.ScreenshotRequest(
                    text = obj.get("text")?.asString ?: "",
                    reason = obj.get("reason")?.asString ?: "",
                    hasAudio = obj.get("hasAudio")?.asBoolean ?: false
                )
                "answer_start" -> ServerMessage.AnswerStart
                "highlights" -> {
                    val highlights = mutableListOf<HighlightTarget>()
                    obj.getAsJsonArray("highlights")?.forEach { elem ->
                        val h = gson.fromJson(elem, HighlightTarget::class.java)
                        highlights.add(h)
                    }
                    ServerMessage.Highlights(highlights = highlights)
                }
                "answer_end" -> {
                    val highlights = mutableListOf<HighlightTarget>()
                    obj.getAsJsonArray("highlights")?.forEach { elem ->
                        val h = gson.fromJson(elem, HighlightTarget::class.java)
                        highlights.add(h)
                    }
                    ServerMessage.AnswerEnd(
                        text = obj.get("text")?.asString ?: "",
                        highlights = highlights
                    )
                }
                "error" -> ServerMessage.Error(
                    message = obj.get("message")?.asString ?: "Unknown error"
                )
                "cancelled" -> ServerMessage.Cancelled
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun <T> toJson(obj: T): String = gson.toJson(obj)
}
