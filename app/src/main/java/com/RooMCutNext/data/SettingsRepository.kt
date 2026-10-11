package com.RooMCutNext.data

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

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
    }
}
