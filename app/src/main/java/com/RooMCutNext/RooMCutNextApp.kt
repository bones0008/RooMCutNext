package com.RooMCutNext

import android.app.Application
import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class RooMCutNextApp : Application() {

    override fun onCreate() {
        super.onCreate()
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
