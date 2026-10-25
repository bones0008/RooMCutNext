package com.RooMCutNext.data

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.RooMCutNext.ai.AiProvider
import com.RooMCutNext.ai.AiSettings
import com.RooMCutNext.ai.AiSettingsStore

/**
 * Small app-level settings store (language preference).
 * Per-app locales are applied through AppCompatDelegate so the system handles
 * activity recreation and persistence automatically.
 */
class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("roomcutnext_settings", Context.MODE_PRIVATE)

    fun languageMode(): String = prefs.getString(KEY_LANGUAGE, "system") ?: "system"

    fun setLanguageMode(mode: String) {
        prefs.edit().putString(KEY_LANGUAGE, mode).apply()
        applySavedLanguage()
    }

    /** Toggle zh <-> en; "system" is treated as zh for the cycle. */
    fun toggleLanguage(): String {
        val next = if (languageMode() == "en") "zh" else "en"
        setLanguageMode(next)
        return next
    }

    // -----------------------------------------------------------------
    // v1.6.0: AI 剪辑（BYOK）设置 — 只存本机，不参与备份/同步。
    // -----------------------------------------------------------------

    /** 当前 AI 模型配置（未配置时返回全默认值）。 */
    fun aiSettings(): AiSettings = AiSettings(
        providerId = prefs.getString(KEY_AI_PROVIDER, AiProvider.ZAI) ?: AiProvider.ZAI,
        baseUrlOverride = prefs.getString(KEY_AI_BASE_URL, "") ?: "",
        apiKey = prefs.getString(KEY_AI_API_KEY, "") ?: "",
        model = prefs.getString(KEY_AI_MODEL, "") ?: "",
        visionMode = prefs.getString(KEY_AI_VISION, AiSettings.VISION_AUTO) ?: AiSettings.VISION_AUTO,
    )

    fun setAiSettings(settings: AiSettings) {
        prefs.edit()
            .putString(KEY_AI_PROVIDER, settings.providerId)
            .putString(KEY_AI_BASE_URL, settings.baseUrlOverride)
            .putString(KEY_AI_API_KEY, settings.apiKey)
            .putString(KEY_AI_MODEL, settings.model)
            .putString(KEY_AI_VISION, settings.visionMode)
            .apply()
    }

    fun applySavedLanguage() {
        val locales = when (languageMode()) {
            "zh" -> LocaleListCompat.forLanguageTags("zh")
            "en" -> LocaleListCompat.forLanguageTags("en")
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        if (AppCompatDelegate.getApplicationLocales() != locales) {
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }

    companion object {
        private const val KEY_LANGUAGE = "language"
        private const val KEY_AI_PROVIDER = "ai_provider"
        private const val KEY_AI_BASE_URL = "ai_base_url"
        private const val KEY_AI_API_KEY = "ai_api_key"
        private const val KEY_AI_MODEL = "ai_model"
        private const val KEY_AI_VISION = "ai_vision"
    }
}
