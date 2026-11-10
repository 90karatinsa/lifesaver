package com.lifesaver.ui

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.lifesaver.LifeSaverApp
import com.lifesaver.detect.ActivityMode

/**
 * Hızlı ayarlar döşemesi: kipi iki dokunuşla değiştirir.
 *
 * Bildirim eylemi korumanın AÇIK olmasını gerektirir; bu döşeme
 * gerektirmez. Amaç aynı: araca binerken uygulamayı açıp menü aramak
 * zorunda kalan kimse kipi değiştirmez, kip yanlış kalır ve algılama yanlış
 * eşiklerle çalışır. Erişimin kolay olması burada bir konfor değil,
 * doğruluk şartıdır.
 */
class ModeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val storage = (application as LifeSaverApp).storage
        val mode = ActivityMode.fromName(storage.state.value.config.activityMode)
        tile.state = Tile.STATE_ACTIVE
        tile.label = "Kip: ${mode.shortLabel}"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = mode.turkish
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, ModePickerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ artık çıplak Intent kabul etmiyor.
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
