package com.lifesaver.learn

import kotlin.math.max

/**
 * İptallerden öğrenen sınırlandırılmış eşik ayarlayıcı.
 *
 * TASARIM GEREKÇESİ — bu sınıfın her kuralı bir tehlikeye karşıdır:
 *
 * İptaller yalnızca OLUMSUZ örnek üretir. Gerçek bir kaza neredeyse hiç
 * gözlemlenmez, dolayısıyla bu sistem "bunda çalma"yı öğrenebilir ama
 * "bunda çal"ı ASLA öğrenemez. Denetimsiz bırakılırsa tek yönlü olarak
 * duyarsızlaşır ve kendini sessizce işe yaramaz hale getirir. Bir hayat
 * kurtarma uygulamasında bu, mümkün olan en kötü arıza biçimidir.
 *
 * Bu yüzden beş sert kural vardır:
 *
 *  1. Yalnızca [CancelReason.FALSE_ALARM] öğretir. "Düştüm ama iyiyim"
 *     eşiğin DOĞRU olduğunu söyler; ondan öğrenmek yanlış dersi öğretir.
 *  2. Tek bir iptal hiçbir şey değiştirmez; en az [MIN_SUPPORT] benzer örnek
 *     gerekir. Yüksek güvenli bir alarmın iptali için daha fazlası gerekir.
 *  3. Her parametrenin AŞILAMAZ bir tavanı vardır. Sistem sınırsız
 *     duyarsızlaşamaz.
 *  4. Değişim yalnızca gözlemlenen yanlış alarmı elemeye YETECEK kadardır;
 *     fazlası değil.
 *  5. Her değişiklik kullanıcıya gösterilir ve tek dokunuşla geri alınabilir.
 *
 * Saf Kotlin'dir ve saati dışarıdan alır: davranışı tamamen test edilebilir.
 */
class ThresholdTuner(
    private val minSupport: Int = MIN_SUPPORT,
    private val highConfidenceMinSupport: Int = HIGH_CONFIDENCE_MIN_SUPPORT,
) {

    companion object {
        /** Bir eşiği değiştirmek için gereken en az benzer yanlış alarm. */
        const val MIN_SUPPORT = 3

        /**
         * Yüksek güvenli bir alarm yanlış çıktıysa daha fazla kanıt istenir:
         * bu, modelin ciddi biçimde yanıldığı anlamına gelir ve tek bir
         * örnekle duyarlılığı düşürmek tehlikelidir.
         */
        const val HIGH_CONFIDENCE_MIN_SUPPORT = 5

        /** Bir eşiğin özgün değerinin en fazla kaç katına çıkabileceği. */
        const val MAX_RELATIVE_INCREASE = 1.30f

        /** Puan eşiğine eklenebilecek en fazla değer. */
        const val MAX_SCORE_INCREASE = 2
    }

    /**
     * Mevcut kayıtlara bakarak uygulanacak ayarlamaları önerir.
     *
     * [baselines] her parametrenin literatürden gelen özgün değeridir;
     * tavan buradan hesaplanır, mevcut ayarlanmış değerden DEĞİL. Aksi halde
     * her tur tavanı da yükseltir ve sınır anlamını kaybeder.
     */
    fun propose(
        records: List<CancellationRecord>,
        baselines: Map<String, Float>,
        nowMs: Long,
    ): List<Tuning> {
        val teaching = records.filter { it.reason.teaches }
        if (teaching.isEmpty()) return emptyList()

        return teaching
            .groupBy { it.mode }
            .mapNotNull { (mode, modeRecords) -> proposeForMode(mode, modeRecords, baselines, nowMs) }
    }

    private fun proposeForMode(
        mode: String,
        records: List<CancellationRecord>,
        baselines: Map<String, Float>,
        nowMs: Long,
    ): Tuning? {
        // Yüksek güvenli iptaller daha fazla destek ister.
        val hasHighConfidence = records.any { it.confidence == "HIGH" }
        val required = if (hasHighConfidence) highConfidenceMinSupport else minSupport
        if (records.size < required) return null

        val peaks = records.mapNotNull { it.features["peakG"] }
        val baselineImpact = baselines[TunableParameter.FALL_IMPACT_G]

        if (peaks.isNotEmpty() && baselineImpact != null) {
            val ceiling = baselineImpact * MAX_RELATIVE_INCREASE
            val observedMax = peaks.max()
            // Gözlemlenen en büyük yanlış alarmın hemen üstüne çık — fazlası değil.
            val proposed = observedMax + 0.1f

            if (proposed <= ceiling && proposed > baselineImpact) {
                return Tuning(
                    mode = mode,
                    parameter = TunableParameter.FALL_IMPACT_G,
                    baseline = baselineImpact,
                    current = proposed,
                    ceiling = ceiling,
                    supportingCancellations = records.size,
                    updatedAtMs = nowMs,
                    note = "${records.size} kez yanlış alarm iptal edildi. En büyüğü " +
                        "%.1f g idi; darbe eşiği %.1f g'den %.1f g'ye çıkarıldı."
                            .format(observedMax, baselineImpact, proposed),
                )
            }

            // Darbe eşiği tavana dayandıysa, duyarlılığı düşürmek yerine
            // DAHA FAZLA KANIT iste: bu, gerçek düşmeleri kaçırma riskini
            // eşiği yükseltmekten daha az artırır.
            val baselineScore = baselines[TunableParameter.FALL_MIN_SCORE] ?: return null
            val scoreCeiling = baselineScore + MAX_SCORE_INCREASE
            val proposedScore = min(baselineScore + 1f, scoreCeiling)
            if (proposedScore > baselineScore) {
                return Tuning(
                    mode = mode,
                    parameter = TunableParameter.FALL_MIN_SCORE,
                    baseline = baselineScore,
                    current = proposedScore,
                    ceiling = scoreCeiling,
                    supportingCancellations = records.size,
                    updatedAtMs = nowMs,
                    note = "${records.size} kez yanlış alarm iptal edildi ve darbe eşiği " +
                        "tavana dayandı. Duyarlılığı düşürmek yerine daha fazla " +
                        "destekleyici kanıt isteniyor (${baselineScore.toInt()} → " +
                        "${proposedScore.toInt()} puan).",
                )
            }
        }
        return null
    }

    private fun min(a: Float, b: Float) = if (a < b) a else b

    /** Ayarlamayı puan (tam sayı) eşiğine uygular. */
    fun applyScore(baseline: Int, tuning: Tuning?): Int {
        if (tuning == null) return baseline
        // Göreli tavan tam sayı puanlarda anlamsızdır (3 × 1.30 = 3.9 → 3),
        // bu yüzden puan sınırı MUTLAK konur.
        return tuning.current.toInt().coerceIn(baseline, baseline + MAX_SCORE_INCREASE)
    }

    /**
     * Ondalık eşiği uygular.
     * Tavan burada da zorlanır: bozuk bir kalıcı kayıt sınırı aşamaz.
     */
    fun apply(baseline: Float, tuning: Tuning?): Float {
        if (tuning == null) return baseline
        val ceiling = baseline * MAX_RELATIVE_INCREASE
        return tuning.current.coerceIn(baseline, max(baseline, ceiling))
    }
}
