package com.lifesaver.learn

import kotlinx.serialization.Serializable

/**
 * Kullanıcının alarmı NEDEN iptal ettiği.
 *
 * Bu ayrım sistemin güvenliğinin temelidir. Her iptal yanlış alarm DEĞİLDİR:
 * insan gerçekten düşüp "iyiyim" diyebilir. O örnekten öğrenmek, dedektöre
 * gerçek düşmeleri görmezden gelmeyi öğretir — yani tam olarak yapmaması
 * gereken şeyi.
 */
enum class CancelReason(val turkish: String, val teaches: Boolean) {
    /** Böyle bir olay yaşanmadı. TEK öğretici sinyal budur. */
    FALSE_ALARM("Böyle bir şey olmadı", teaches = true),

    /** Olay gerçekten oldu ama kişi iyi. Eşik DOĞRUYDU; öğrenilmez. */
    REAL_BUT_FINE("Oldu ama iyiyim", teaches = false),

    /** Yanlışlıkla iptal edildi. Öğrenilmez. */
    ACCIDENTAL("Yanlışlıkla bastım", teaches = false),

    /** Kullanıcı belirtmedi. İhtiyatlı davranılır, öğrenilmez. */
    UNSPECIFIED("Belirtilmedi", teaches = false),
}

/**
 * İptal edilen bir alarmın tam kaydı.
 *
 * Sinyal penceresi de saklanır: eşik ayarı ancak gerçek saha verisiyle
 * yapılabilir ve bu kayıt aynı zamanda asılsız ihbar iddiasına karşı
 * kullanıcının savunmasıdır.
 */
@Serializable
data class CancellationRecord(
    val timestampMs: Long,
    val mode: String,
    val scenario: String,
    val score: Int,
    val confidence: String,
    val reason: CancelReason = CancelReason.UNSPECIFIED,
    /** Ölçülen özellik değerleri: "peakG", "verticalVelocity", "gyroDegS" ... */
    val features: Map<String, Float> = emptyMap(),
    val evidenceFired: List<String> = emptyList(),
    /** Olay çevresindeki |a| penceresi (m/s^2), 10 Hz'e indirgenmiş. */
    val signalWindow: List<Float> = emptyList(),
)

/**
 * Uygulanmış tek bir eşik uyarlaması.
 *
 * Her uyarlama geri alınabilir ve kullanıcıya gösterilir. Kara kutu yoktur:
 * hangi sayının neden değiştiği her zaman okunabilir.
 */
@Serializable
data class Tuning(
    val mode: String,
    val parameter: String,
    /** Literatürden gelen özgün değer. Geri alma buraya döner. */
    val baseline: Float,
    val current: Float,
    /** Aşılamaz tavan. Bu sınır olmadan sistem kendini sağır edebilir. */
    val ceiling: Float,
    val supportingCancellations: Int,
    val updatedAtMs: Long,
    val note: String,
) {
    val isAtCeiling: Boolean get() = current >= ceiling - 1e-4f
    val changePercent: Int
        get() = if (baseline == 0f) 0 else (((current - baseline) / baseline) * 100f).toInt()
}

/** Ayarlanabilir parametreler. Kod içinde dize kullanmamak için. */
object TunableParameter {
    const val FALL_IMPACT_G = "fall.impactG"
    const val FALL_MIN_SCORE = "fall.minScore"
    const val CRASH_PEAK_MS2 = "crash.peakAccelMs2"
}
