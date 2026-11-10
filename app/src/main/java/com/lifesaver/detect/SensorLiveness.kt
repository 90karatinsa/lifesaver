package com.lifesaver.detect

import kotlin.math.abs
import kotlin.math.sqrt

/** Sensör akışının sağlık durumu. */
enum class Liveness {
    /** Henüz yeterli örnek toplanmadı. */
    WARMING,

    /** Sensör olayı hiç gelmiyor. */
    NO_EVENTS,

    /**
     * Olaylar geliyor ama veri sıfırlanmış / sabit.
     * Bu, GrapheneOS'un Sensör iznini reddettiği ya da "Sensörler kapalı"
     * gizlilik anahtarının açık olduğu durumdur.
     */
    ZEROED,

    /** Veri geliyor ama fizik dışı: yerçekimi büyüklüğü yok. */
    IMPLAUSIBLE,

    OK,
}

/**
 * Sensör canlılık denetimi.
 *
 * NEDEN VAR — bu, tespit edilmesi en zor sessiz arızadır:
 * `getDefaultSensor()` bu durumların HİÇBİRİNDE null dönmez. Nesne vardır,
 * kayıt başarılı görünür, ama gelen veri sıfırdır. Uygulama "her şey sakin"
 * sanır ve kullanıcı korunduğunu zanneder.
 *
 * Bu, bağlı test cihazında (GrapheneOS) gerçek bir risktir ve stok Android'de
 * de hızlı ayarlardaki "Sensörler kapalı" anahtarıyla oluşur. O anahtarın
 * durumunu okumanın API'si yoktur (OBSERVE_SENSOR_PRIVACY ayrıcalıklıdır),
 * bu yüzden veriden ÇIKARSAMAK zorundayız.
 */
class SensorLiveness(
    private val windowSamples: Int = 250,           // 50 Hz'de 5 saniye
    private val noEventTimeoutMs: Long = 20_000L,
) {
    private val magnitudes = FloatArray(windowSamples)
    private var count = 0
    private var head = 0
    private var lastEventMs = 0L
    private var everReceived = false

    fun onSample(x: Float, y: Float, z: Float, nowMs: Long) {
        lastEventMs = nowMs
        everReceived = true
        magnitudes[head] = sqrt(x * x + y * y + z * z)
        head = (head + 1) % windowSamples
        if (count < windowSamples) count++
    }

    fun reset() {
        count = 0; head = 0; lastEventMs = 0L; everReceived = false
    }

    fun evaluate(nowMs: Long): Liveness {
        if (!everReceived) {
            // Hiç olay gelmediyse, servis başlayalı ne kadar olduğunu
            // bilemeyiz; çağıran taraf zamanlamayı yönetir.
            return Liveness.NO_EVENTS
        }
        if (nowMs - lastEventMs > noEventTimeoutMs) return Liveness.NO_EVENTS
        if (count < windowSamples) return Liveness.WARMING

        var sum = 0.0
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (i in 0 until count) {
            val m = magnitudes[i]
            sum += m
            if (m < min) min = m
            if (m > max) max = m
        }
        val mean = (sum / count).toFloat()

        // Tamamen sabit bir sinyal: gerçek bir MEMS sensörü, masada dururken
        // bile gürültü üretir. Aralık tam sıfırsa veri sentetiktir.
        if (max - min < 1e-4f) return Liveness.ZEROED

        // Yerçekimi olmayan bir dünya yok: durgun telefonda |a| ~9.81 olmalı.
        // Serbest düşüşteki bir telefon bile 5 saniye boyunca 0 kalamaz.
        if (abs(mean) < 1.0f) return Liveness.ZEROED
        if (mean < 5.0f || mean > 15.0f) return Liveness.IMPLAUSIBLE

        return Liveness.OK
    }

    /** Kullanıcıya gösterilecek Türkçe açıklama. */
    fun describe(liveness: Liveness): String = when (liveness) {
        Liveness.OK -> "Sensörler çalışıyor."
        Liveness.WARMING -> "Sensörler ısınıyor..."
        Liveness.NO_EVENTS ->
            "Sensörden veri GELMİYOR. Koruma çalışmıyor. Uygulamanın sensör " +
                "iznini ve pil ayarlarını kontrol edin."
        Liveness.ZEROED ->
            "Sensör verisi SIFIRLANMIŞ geliyor. Bu, cihazın 'Sensörler kapalı' " +
                "gizlilik anahtarının açık olduğu ya da uygulamaya sensör izni " +
                "verilmediği anlamına gelir. Koruma çalışmıyor."
        Liveness.IMPLAUSIBLE ->
            "Sensör verisi fiziksel olarak tutarsız. Koruma güvenilir değil."
    }
}
