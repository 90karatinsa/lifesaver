package com.lifesaver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Yeniden başlatmadan sonra korumayı geri getirir.
 *
 * DÜRÜST SINIRLAR:
 *  - Kullanıcı uygulamayı "Kısıtlanmış" pil kovasına koyduysa Android 13+
 *    BOOT_COMPLETED'i HİÇ göndermez ve uygulama kendi kendine toparlanamaz.
 *    Bunu düzeltmenin API'si yoktur; yalnızca kullanıcı geri alabilir.
 *  - Konum tipli ön plan servisi, arka plandan (yani açılıştan) başlatılırken
 *    ACCESS_BACKGROUND_LOCATION olmadan konuma erişemez. Bu izin yoksa servis
 *    yine başlar ama konumsuz çalışır ve ana ekran bunu kırmızı gösterir.
 */
class BootReceiver : BroadcastReceiver() {

    private companion object { const val TAG = "LifeSaver/Boot" }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val app = context.applicationContext as? LifeSaverApp ?: return
        if (!app.storage.state.value.config.armed) {
            Log.i(TAG, "Koruma kapali, aciliste baslatilmadi")
            return
        }
        runCatching { GuardianService.Commands.start(context) }
            .onFailure { Log.e(TAG, "Aciliste koruma baslatilamadi", it) }
    }
}
