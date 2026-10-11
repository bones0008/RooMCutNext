package com.RooMCutNext

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.RooMCutNext.data.SettingsRepository
import com.RooMCutNext.ui.theme.RooMCutNextTheme
import com.RooMCutNext.ui.AppRoot

class MainActivity : AppCompatActivity() {

    private val settings by lazy { SettingsRepository(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15+ forces edge-to-edge for targetSdk 35 apps anyway; opting in
        // explicitly keeps the behaviour identical on Android 12-14. The app is
        // always dark-themed, so both system bars are transparent with light icons.
        // Actual inset avoidance is done in Compose (statusBarsPadding etc.).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        settings.applySavedLanguage()
        setContent {
            RooMCutNextTheme {
                AppRoot()
            }
        }
    }
}
