package com.lifesaver

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import com.lifesaver.data.Storage

/**
 * Uygulama kökü ve elle yazılmış bağımlılık kabı.
 *
 * Hilt/Dagger bilinçli olarak KULLANILMIYOR: bu uygulamanın çalışması bir
 * hayati gerekliliktir ve derleme zamanı kod üretimi katmanı, kazandırdığı
 * kolaylıktan daha fazla kırılganlık getirir. Üç bağımlılık için kap yeter.
 */
class LifeSaverApp : Application() {

    val storage: Storage by lazy { Storage(this) }

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return

        // Koruma bildirimi: sessiz ve kalıcı. Kullanıcıyı rahatsız etmemeli.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_GUARD,
                getString(R.string.channel_guard_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_guard_desc)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )

        // Alarm kanalı: mümkün olan en yüksek önem.
        // Sesi yoktur (sireni AudioTrack ayrı çalar) ama IMPORTANCE_HIGH,
        // tam ekran niyetin çalışabilmesi için gereklidir.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT,
                getString(R.string.channel_alert_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.channel_alert_desc)
                enableVibration(true)
                setBypassDnd(true)   // yalnızca kullanıcı kanal iznini verirse etkili olur
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setSound(
                    null,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
        )
    }

    companion object {
        const val CHANNEL_GUARD = "koruma"
        const val CHANNEL_ALERT = "alarm"
        const val NOTIFICATION_GUARD = 1001
        const val NOTIFICATION_ALERT = 1002
        /** İptalden sonra "ne olmuştu?" sorusu. */
        const val NOTIFICATION_FEEDBACK = 1003
        const val NOTIFICATION_WAVE = 1004
    }
}
