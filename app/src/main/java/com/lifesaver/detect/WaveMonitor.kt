package com.lifesaver.detect

import kotlin.math.sqrt

/**
 * Dalga imzası — "bu telefon su üstünde bir şeyin üzerinde" ipucu.
 *
 * NEDEN VAR: Tekne kipinin en sinsi arızası kipi AÇMAYI UNUTMAKTIR ve bu
 * arıza sessizdir. Güvertede dalga hareketi hiç durmaz; düşme algılamanın
 * "çarpmadan sonra hareketsiz kaldı mı" kapısı karada 0.40 g ölçütüyle
 * çalışır ve teknede bu ölçüt ASLA sağlanmaz. Sonuç: gerçek bir düşme
 * MOVED_AFTER_IMPACT diye elenir. Üstelik günlüğe "elendi" yazıldığı için
 * sistem dışarıdan SAĞLIKLI görünür — sessiz bir koruma boşluğu.
 *
 * NE YAPAR, NE YAPMAZ: Bu sınıf alarm üretmez ve kipi kendiliğinden
 * DEĞİŞTİRMEZ. Yaptığı tek şey hareketsizlik kapısını gevşetmek, yani DAHA
 * ÇOK adayın geçmesine izin vermektir; bu tek yönlü kurala uyar. Tam tekne
 * kipine geçmek kullanıcının kararıdır, çünkü o kip başka eşikleri de
 * değiştirir ve karada yanlış alarmı artırır.
 *
 * NASIL AYIRIR: Deniz dalgası dar ve alçak bir banttadır — 0.08-0.35 Hz,
 * yani 3-12 saniyelik periyot (ISO 2631-1 deniz taşıtı hareket bandı
 * 0.1-0.5 Hz olarak verilir; üst ucu yürüme bandına yaklaşmasın diye
 * daraltıldı). Yürüme 1.4-2.3 Hz'dir ve alçak geçirici süzgeç onu tamamen
 * söndürür. Ayıran şey genlik değil FREKANS ve SÜREKLİLİK: dalga saatlerce
 * aynı ritimde salınır, bir aracın yol tümseği salınmaz.
 *
 * Saf Kotlin'dir ve saati dışarıdan alır: davranışı tamamen test edilebilir.
 */
class WaveMonitor(
    /** YALNIZCA ilk örnek için varsayılan; gerçek fark damgalardan ölçülür. */
    private val sampleRateHz: Int = 50,
    /** Dalga bandının altı (Hz). Altı: gelgit/sürüklenme, dalga değil. */
    private val bandLowHz: Float = 0.08f,
    /** Dalga bandının üstü (Hz). Üstü: motor, adım, titreşim. */
    private val bandHighHz: Float = 0.35f,
    /** Bant içi salınımın en az bu kadar olması gerekir (m/s^2 RMS). */
    private val waveRmsMin: Float = 0.15f,
    /** Bant DIŞI yüksek frekans bunu aşarsa ortam dalga değil (m/s^2 RMS). */
    private val highFreqRmsMax: Float = 2.0f,
    /** Koşulların kesintisiz sürmesi gereken süre. */
    private val sustainSeconds: Float = 120f,
    /** En az bu kadar salınım görülmeden karar verilmez. */
    private val minCrossings: Int = 8,
    /** İki bildirim arasındaki en kısa süre. */
    private val cooldownSeconds: Float = 1800f,
    /** Bundan uzun örnek boşluğu biriken kanıtı geçersiz kılar. */
    private val maxGapSeconds: Float = 1f,
) {

    private var lastAccelT = Long.MIN_VALUE
    private var lastStepT = Long.MIN_VALUE
    private var lastReportT = Long.MIN_VALUE

    /** İki alçak geçiren süzgecin farkı = bant geçiren. */
    private var lpLow = 0f
    private var lpHigh = 0f
    private var hasLp = false

    /** Bant içi ve bant dışı enerjinin üstel ortalaması (kare cinsinden). */
    private var bandMs = 0.0f
    private var highMs = 0.0f

    private var lastSign = 0
    private var lastCrossT = Long.MIN_VALUE
    private var crossings = 0
    private var freqEma = Float.NaN

    /** Tüm koşulların kesintisiz sağlandığı an. */
    private var okSince = Long.MIN_VALUE

    /**
     * Koşullar ŞU AN sağlanıyor mu?
     *
     * [onAccel]'in dönüş değerinden farklıdır: o, süreklilik şartı dolunca
     * bir kez true döner; bu ise anlık durumdur. Gevşetmeyi GERİ ALMAK için
     * gerekli — karaya çıkıldığında bunu görebilmeliyiz.
     */
    var waveActive = false
        private set

    /** Son ölçülen değerler — teşhis ve kullanıcıya açıklama için. */
    var lastBandRms = 0f
        private set
    var lastFrequencyHz = Float.NaN
        private set

    fun reset() {
        lastAccelT = Long.MIN_VALUE
        lastStepT = Long.MIN_VALUE
        lastReportT = Long.MIN_VALUE
        forgetTransient()
    }

    fun onStep(t: Long) {
        lastStepT = t
        // Yürüyen biri güvertede de olsa, o anki hareket dalga değil adımdır.
        okSince = Long.MIN_VALUE
    }

    /**
     * İvme örneği. Dalga hareketi doğrulandığında bir kez true döner;
     * ardından bekleme süresi işler.
     */
    fun onAccel(t: Long, x: Float, y: Float, z: Float): Boolean {
        if (lastAccelT != Long.MIN_VALUE && t - lastAccelT > nanos(maxGapSeconds)) {
            forgetTransient()
        }
        val dt = measuredDt(t)
        val mag = sqrt(x * x + y * y + z * z)

        // İki tek kutuplu süzgeç. Farkları bant geçirendir: lpHigh dalgayı
        // geçirir ve adımı söndürür; lpLow yerçekimi/sürüklenmeyi tutar ve
        // çıkarıldığında geriye salınım kalır.
        val aLow = alpha(dt, bandLowHz)
        val aHigh = alpha(dt, bandHighHz)
        if (!hasLp) {
            hasLp = true
            lpLow = mag; lpHigh = mag
        } else {
            lpLow += aLow * (mag - lpLow)
            lpHigh += aHigh * (mag - lpHigh)
        }
        val band = lpHigh - lpLow
        val high = mag - lpHigh          // banttan hızlı olan her şey

        // ~30 saniyelik zaman sabitiyle enerji ortalaması. Tampon tutmaya
        // gerek yok: pencere uzunluğu sürekli, bellek sabittir.
        val aEnergy = alphaTau(dt, 30f)
        bandMs += aEnergy * (band * band - bandMs)
        highMs += aEnergy * (high * high - highMs)
        lastBandRms = sqrt(bandMs)

        trackFrequency(t, band)
        return evaluate(t)
    }

    /**
     * Sıfır geçişlerinden frekans. Genlikten değil RİTİMDEN karar vermenin
     * yolu budur: yol tümseği büyük ama düzensizdir, dalga küçük ama düzenli.
     */
    private fun trackFrequency(t: Long, band: Float) {
        // Gürültünün sahte geçiş üretmemesi için ölü bant.
        val dead = waveRmsMin * 0.5f
        val sign = when {
            band > dead -> 1
            band < -dead -> -1
            else -> return
        }
        if (lastSign == 0) { lastSign = sign; lastCrossT = t; return }
        if (sign == lastSign) return

        val half = (t - lastCrossT) / 1e9f
        lastSign = sign
        lastCrossT = t
        if (half <= 0f) return

        val f = 1f / (2f * half)
        freqEma = if (freqEma.isNaN()) f else freqEma + 0.25f * (f - freqEma)
        lastFrequencyHz = freqEma
        if (crossings < Int.MAX_VALUE) crossings++
    }

    private fun evaluate(t: Long): Boolean {
        val ok = conditionsHold(t)
        waveActive = ok
        if (!ok) {
            okSince = Long.MIN_VALUE
            return false
        }
        if (okSince == Long.MIN_VALUE) okSince = t
        if (t - okSince < nanos(sustainSeconds)) return false
        if (lastReportT != Long.MIN_VALUE && t - lastReportT < nanos(cooldownSeconds)) {
            return false
        }
        lastReportT = t
        okSince = Long.MIN_VALUE
        return true
    }

    private fun conditionsHold(t: Long): Boolean {
        // Adım varsa hareketin kaynağı bilinmiyor: dalga OLABİLİR ama
        // ölçtüğümüz şey adım. Kanıt karışıksa kanıt yoktur.
        if (lastStepT != Long.MIN_VALUE && t - lastStepT < nanos(10f)) return false
        if (crossings < minCrossings) return false
        val f = freqEma
        if (f.isNaN() || f < bandLowHz || f > bandHighHz) return false
        // Salınım durduysa (uzun süre geçiş yok) frekans eski bir bilgidir.
        if (lastCrossT != Long.MIN_VALUE && t - lastCrossT > nanos(20f)) return false
        if (lastBandRms < waveRmsMin) return false
        if (sqrt(highMs) > highFreqRmsMax) return false
        return true
    }

    private fun forgetTransient() {
        waveActive = false
        hasLp = false
        lpLow = 0f; lpHigh = 0f
        bandMs = 0f; highMs = 0f
        lastBandRms = 0f
        lastSign = 0
        lastCrossT = Long.MIN_VALUE
        crossings = 0
        freqEma = Float.NaN
        lastFrequencyHz = Float.NaN
        okSince = Long.MIN_VALUE
    }

    /** Tek kutuplu süzgeç katsayısı, kesim frekansından. */
    private fun alpha(dt: Float, cutoffHz: Float): Float {
        val rc = 1f / (2f * Math.PI.toFloat() * cutoffHz)
        return dt / (rc + dt)
    }

    /** Tek kutuplu süzgeç katsayısı, zaman sabitinden. */
    private fun alphaTau(dt: Float, tauSeconds: Float): Float = dt / (tauSeconds + dt)

    private fun measuredDt(t: Long): Float {
        val prev = lastAccelT
        lastAccelT = t
        if (prev == Long.MIN_VALUE) return 1f / sampleRateHz
        return ((t - prev) / 1e9f).coerceIn(1f / 400f, 0.2f)
    }

    private fun nanos(seconds: Float): Long = (seconds * 1e9f).toLong()
}
