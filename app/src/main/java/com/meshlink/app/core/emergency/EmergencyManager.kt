package com.meshlink.app.core.emergency

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import kotlinx.coroutines.delay

/** Device-local attention signal for validated SOS/alert events. */
class EmergencyManager(private val context: Context) {
    suspend fun triggerLocal() {
        pulse(count = 5, toneMs = 420L, gapMs = 180L)
    }

    /** Shorter receiver-side attention tone for a normal mesh alert. */
    suspend fun triggerAlertLocal() {
        pulse(count = 2, toneMs = 180L, gapMs = 220L)
    }

    @Suppress("DEPRECATION")
    private suspend fun pulse(count: Int, toneMs: Long, gapMs: Long) {
        // Audio and vibration are independent: failure of one must not suppress the other.
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 100) }.getOrNull()
        val vibrator = runCatching {
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }.getOrNull()

        try {
            repeat(count) {
                runCatching { tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, toneMs.toInt()) }
                runCatching {
                    if (vibrator?.hasVibrator() == true) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator.vibrate(VibrationEffect.createOneShot(toneMs, VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            vibrator.vibrate(toneMs)
                        }
                    }
                }
                delay(gapMs)
            }
        } finally {
            runCatching { tone?.release() }
        }
    }
}
