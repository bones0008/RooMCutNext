package com.RooMCutNext

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.RooMCutNext.data.SettingsRepository
import com.RooMCutNext.ui.theme.RooMCutNextTheme
import com.RooMCutNext.ui.AppRoot

class MainActivity : AppCompatActivity() {

    private val settings by lazy { SettingsRepository(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings.applySavedLanguage()
        setContent {
            RooMCutNextTheme {
                AppRoot()
            }
        }
    }
}
