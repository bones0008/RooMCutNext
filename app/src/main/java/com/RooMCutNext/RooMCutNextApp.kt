package com.RooMCutNext

import android.app.Application
import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.RooMCutNext.media.FontRegistry
import com.RooMCutNext.media.LutRegistry

class RooMCutNextApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // v1.1: font & LUT catalogs live behind app-scoped singletons so the
        // preview builder and the exporter resolve identical resources.
        FontRegistry.init(this)
        LutRegistry.init(this)
        // Warm caches off the main thread: first text overlay / first filter
        // apply would otherwise parse 5 fonts + 8 cubes on a UI frame.
        Thread {
            runCatching { FontRegistry.preloadBundled() }
            runCatching { LutRegistry.preloadAll() }
        }.start()
    }

    companion object {
        /** Light haptic tick for gestures (trim snap, long-press, export done). */
        fun vibrate(context: Context, strength: Int = 20) {
            runCatching {
                val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) {
                    (context.getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(VIBRATOR_SERVICE) as Vibrator
                }
                vibrator.vibrate(VibrationEffect.createOneShot(strength.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }
}
