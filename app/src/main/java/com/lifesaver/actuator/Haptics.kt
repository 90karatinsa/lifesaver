package com.lifesaver.actuator

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Titreşim.
 *
 * DÜRÜSTLÜK NOTU: [VibrationAttributes.FLAG_BYPASS_INTERRUPTION_POLICY]
 * ayrıcalıklı olmayan uygulamalar için YOK SAYILIR. Kullanıcı titreşimi
 * kapattıysa uygulama bunu aşamaz. Bu yüzden titreşim, alarmın tek başına
 * güvenilen bileşeni değildir; siren ve fener asıl kanallardır.
 */
class Haptics(private val context: Context) {

    private companion object { const val TAG = "LifeSaver/Haptics" }

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }.getOrNull()

    val isAvailable: Boolean get() = vibrator?.hasVibrator() == true

    /** Uzun-kısa-kısa, dikkat çekici ve sürekli tekrarlayan desen. */
    fun startAlarmPattern() {
        val v = vibrator ?: return
        val timings = longArrayOf(0, 600, 200, 200, 200, 200, 200, 800)
        val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255, 0, 0)
        runCatching {
            val effect = VibrationEffect.createWaveform(timings, amplitudes, 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val attrs = VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_ALARM)
                    .build()
                v.vibrate(effect, attrs)
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect)
            }
        }.onFailure { Log.w(TAG, "Titresim baslatilamadi", it) }
    }

    /** Geri sayım sırasında her saniye tek darbe. */
    fun tick() {
        val v = vibrator ?: return
        runCatching {
            v.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    fun stop() {
        runCatching { vibrator?.cancel() }
    }
}
