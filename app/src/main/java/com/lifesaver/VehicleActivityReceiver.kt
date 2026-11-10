package com.lifesaver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * Etkinlik tanımadan gelen "araçta mı" bilgisi.
 *
 * Neden hıza ek olarak gerekli: GNSS tünelde, kapalı otoparkta ve şehir
 * kanyonlarında hız üretemez. Etkinlik tanıma ivmeölçer imzasından çalışır
 * ve bu boşlukları kapatır. Ayrıca "araçta telefon düştü" vetosunun
 * sertliği bu güven değerine bağlıdır.
 */
object VehicleContext {
    @Volatile var inVehicle: Boolean = false
        private set
    @Volatile var confidence: Int = 0
        private set
    @Volatile var updatedAtMs: Long = 0L
        private set

    /**
     * TEŞHİS ALANLARI.
     *
     * Neden gerekli: etkinlik tanıma sınıflandırıcısı Google Play Services
     * SÜRECİNDE çalışır ve izin/pil ayarları eksikse SESSİZCE ölür — hata
     * dönmez, sadece hiç sonuç gelmez. Bu durumda "araçta değilsin" ile
     * "sınıflandırıcı ölü" birbirinden ayırt edilemez. Bu alanlar o ayrımı
     * yapılabilir kılar ve hazırlık ekranında gösterilir.
     *
     * GrapheneOS gibi sandbox'lanmış Play kurulumlarında bu somut bir risk.
     */
    @Volatile var lastResultAtMs: Long = 0L
        private set
    @Volatile var lastTopActivity: String = ""
        private set
    @Volatile var lastTopConfidence: Int = 0
        private set
    @Volatile var resultCount: Int = 0
        private set

    fun update(inVehicle: Boolean, confidence: Int, nowMs: Long) {
        this.inVehicle = inVehicle
        this.confidence = confidence
        this.updatedAtMs = nowMs
    }

    /** Her sonuçta çağrılır — araçta olsun ya da olmasın. */
    fun recordResult(topActivity: String, topConfidence: Int, nowMs: Long) {
        lastResultAtMs = nowMs
        lastTopActivity = topActivity
        lastTopConfidence = topConfidence
        if (resultCount < Int.MAX_VALUE) resultCount++
    }

    fun clear() {
        inVehicle = false; confidence = 0; updatedAtMs = 0L
    }
}

class VehicleActivityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityRecognitionResult.hasResult(intent)) return
        val result = ActivityRecognitionResult.extractResult(intent) ?: return
        val now = System.currentTimeMillis()

        // Teşhis: HER sonuç kaydedilir. "Hiç sonuç gelmedi" ile "araçta
        // değilsin" ancak böyle ayırt edilebilir.
        val top = result.mostProbableActivity
        VehicleContext.recordResult(turkishName(top.type), top.confidence, now)

        val vehicle = result.probableActivities
            .firstOrNull { it.type == DetectedActivity.IN_VEHICLE }
        VehicleContext.update(
            inVehicle = vehicle != null && vehicle.confidence >= 50,
            confidence = vehicle?.confidence ?: 0,
            nowMs = now,
        )
    }

    companion object {
        const val ACTION = "com.lifesaver.ACTIVITY_UPDATE"

        fun turkishName(type: Int): String = when (type) {
            DetectedActivity.IN_VEHICLE -> "araçta"
            DetectedActivity.ON_BICYCLE -> "bisiklette"
            DetectedActivity.ON_FOOT -> "yürüyor"
            DetectedActivity.WALKING -> "yürüyor"
            DetectedActivity.RUNNING -> "koşuyor"
            DetectedActivity.STILL -> "hareketsiz"
            DetectedActivity.TILTING -> "eğiliyor"
            else -> "bilinmiyor"
        }
    }
}
