package com.knowmyphone.app.i18n

import android.content.Context
import com.google.gson.Gson

data class PillStrings(
    val tapPrefix: String,
    val tapSuffix: String,
    val thinking: String,
    val shareScreen: String,
    val idlePrompt: String? = null,
    val showOpenApp: String = "Show open app",
    val closeApp: String = "Close"
)

data class AppStrings(
    val title: String,
    val subtitle: String,
    val permissions: String,
    val permissionsHelp: String,
    val micLabel: String,
    val micSubtitle: String,
    val overlayLabel: String,
    val overlaySubtitle: String,
    val accessibilityLabel: String,
    val accessibilitySubtitle: String,
    val grant: String,
    val language: String,
    val autoShare: String,
    val autoShareSub: String,
    val serverEndpoint: String,
    val serverEndpointSub: String,
    val startAssistant: String,
    val resetSession: String,
    val stop: String,
    val permissionsFirst: String,
    val stopped: String,
    val sessionReset: String,
    val connected: String,
    val connectionLost: String,
    val reconnecting: String,
    val micUnavailable: String,
    val sendFailed: String,
    val notificationActive: String,
    val notificationChannelDescription: String
)

data class LanguageConfig(
    val code: String,
    val letter: String,
    val name: String,
    val pill: PillStrings,
    val app: AppStrings
)

private data class LanguagesFile(
    val languages: List<LanguageConfig>
)

object LanguageManager {
    private var languages: List<LanguageConfig> = emptyList()
    private val indicLanguageCodes = setOf("hi", "ta", "kn", "te", "ml")

    fun init(context: Context) {
        if (languages.isNotEmpty()) return
        val json = context.assets.open("languages.json").bufferedReader().use { it.readText() }
        val file = Gson().fromJson(json, LanguagesFile::class.java)
        languages = file.languages
    }

    fun allLanguages(): List<LanguageConfig> = languages

    fun getLanguage(code: String): LanguageConfig =
        languages.firstOrNull { it.code == code } ?: languages.first()

    fun getPillStrings(code: String): PillStrings = getLanguage(code).pill

    fun getAppStrings(code: String): AppStrings = getLanguage(code).app

    fun isIndicLanguage(code: String): Boolean = indicLanguageCodes.contains(code.lowercase())
}
