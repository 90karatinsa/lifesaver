package com.lifesaver.detect

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * "Araca binmiş olabilir" ipucu — GMS'siz, izinsiz, sıfır ek pille.
 *
 * NEDEN VAR: Günlük kipte konum akışı kapalıdır ve kaza algılamanın tek
 * otomatik giriş kapısı Google'ın etkinlik tanıma sınıflandırıcısıydı. O
 * sınıflandırıcı Play Services sürecinde çalışır ve izin/pil ayarları
 * eksikse SESSİZCE ölür — hata dönmez, sadece hiç sonuç gelmez. Yani tek
 * kapıya bağlı kalmak, görünmez bir koruma boşluğu demekti.
 *
 * Bu sınıf o boşluğu kapatır ama BİR ALARM ÜRETMEZ. Yalnızca "konumu açıp
 * bakmaya değer" der. Yanlış tetiklemenin bedeli bir alarm değil, 60
 * saniyelik bir GNSS penceresidir; bu yüzden eşikler cömert tutulabilir.
 *
 * ÜÇ KOŞUL BİRDEN aranır:
 *
 *  1. ADIM YOK. En az [noStepSeconds] saniyedir hiç adım algılanmadı.
 *     Yürüyen biri araçta değildir; bu tek başına en ucuz elemedir.
 *  2. TAŞINIYOR AMA YÜRÜMÜYOR. Yerçekimi çıkarılmış ivmenin RMS'i dar bir
 *     bantta: masada duran telefon kadar ölü değil, yürüyen biri kadar
 *     hareketli de değil. Araç titreşimi tam buraya düşer.
 *  3. KALKIŞ. Yatay düzlemde SÜRDÜRÜLMÜŞ özgül kuvvet. Buradaki incelik:
 *     ham genliğe değil ALÇAK GEÇİRİLMİŞ bileşene bakılır. Yürüyen birinin
 *     yatay kuvveti her adımda işaret değiştirir ve ortalaması sıfıra
 *     yakındır; hızlanan bir aracınki ise saniyelerce aynı yönde kalır.
 *     Ayıran şey genlik değil, SÜREKLİLİK.
 *
 * Saf Kotlin'dir ve saati dışarıdan alır: davranışı tamamen test edilebilir.
 */
class VehicleHint(
    /**
     * YALNIZCA ilk örnek için varsayılan; gerçek zaman farkı damgalardan
     * ölçülür. Sabit hız varsaymak burada bir hata olurdu: servis kaza
     * algılayıcı silahlanınca ivmeölçeri 50 Hz'den 200 Hz'e çıkarır ve sabit
     * varsayılan süzgeç kesimini dört katına kaydırırdı.
     */
    private val sampleRateHz: Int = 50,
    /** Bu kadar süredir adım yoksa yaya sayılmaz. */
    private val noStepSeconds: Float = 90f,
    /** Taşınma bandının altı (m/s^2 RMS). Altı = masada/çantada ölü duruyor. */
    private val carriedRmsMin: Float = 0.03f,
    /** Taşınma bandının üstü (m/s^2 RMS). Üstü = yürüme/koşma. */
    private val carriedRmsMax: Float = 2.5f,
    /** Kalkış için gereken sürdürülmüş yatay kuvvet (m/s^2). */
    private val takeoffMs2: Float = 1.0f,
    /** Kalkışın sürmesi gereken süre. */
    private val takeoffSeconds: Float = 3f,
    /** İki yoklama arasındaki en kısa süre. */
    private val cooldownSeconds: Float = 300f,
    /**
     * Bundan uzun bir örnek boşluğu, biriken kanıtı geçersiz kılar.
     *
     * Ekran kapanınca Android sensör örneklerini toplu teslim edebilir.
     * Boşluk boyunca ne olduğunu BİLMİYORUZ; "kuvvet 3 saniye sürdü" demek
     * için elimizde o 3 saniyenin örnekleri olmalı. Örneğin yokluğu, kuvvetin
     * sürdüğünün kanıtı değildir.
     */
    private val maxGapSeconds: Float = 1f,
) {

    private var lastStepT = Long.MIN_VALUE
    private var lastAccelT = Long.MIN_VALUE
    private var lastProbeT = Long.MIN_VALUE
    private var takeoffSince = Long.MIN_VALUE

    /** Alçak geçiren süzgeç durumu (yatay bileşen). */
    private var lpHorizontal = 0f
    private var hasLp = false

    /** Son 1 saniyelik RMS için toplayıcı. */
    private var sumSq = 0.0
    private var count = 0
    private var windowStartT = Long.MIN_VALUE
    private var lastRms = Float.NaN

    private var gx = 0f
    private var gy = 0f
    private var gz = G
    private var hasGravity = false

    fun reset() {
        lastStepT = Long.MIN_VALUE
        lastAccelT = Long.MIN_VALUE
        lastProbeT = Long.MIN_VALUE
        takeoffSince = Long.MIN_VALUE
        lpHorizontal = 0f; hasLp = false
        sumSq = 0.0; count = 0; windowStartT = Long.MIN_VALUE
        lastRms = Float.NaN
        hasGravity = false
    }

    /**
     * Zamanla anlamını yitiren durumu unutur; ADIM ve YOKLAMA geçmişi
     * korunur, çünkü onlar boşluktan etkilenmez.
     */
    private fun forgetTransient() {
        takeoffSince = Long.MIN_VALUE
        hasLp = false
        lpHorizontal = 0f
        sumSq = 0.0; count = 0
        windowStartT = Long.MIN_VALUE
        lastRms = Float.NaN
    }

    fun onStep(t: Long) {
        lastStepT = t
        // Yürüyen biri araçta değildir: kalkış sayacı da sıfırlanır.
        takeoffSince = Long.MIN_VALUE
    }

    fun onGravity(x: Float, y: Float, z: Float) {
        gx = x; gy = y; gz = z
        hasGravity = true
    }

    /**
     * İvme örneği. Yoklama yapılması gerekiyorsa true döner (yalnızca bir
     * kez; ardından bekleme süresi işler).
     */
    fun onAccel(t: Long, x: Float, y: Float, z: Float): Boolean {
        if (lastAccelT != Long.MIN_VALUE && t - lastAccelT > nanos(maxGapSeconds)) {
            forgetTransient()
        }
        val horizontal = horizontalComponent(x, y, z)

        // Alçak geçiren: yürümenin işaret değiştiren bileşenini söndürür,
        // aracın sürdürülmüş hızlanmasını geçirir. ~0.5 Hz kesim.
        val dt = measuredDt(t)
        val rc = 1f / (2f * Math.PI.toFloat() * 0.5f)
        val a = dt / (rc + dt)
        lpHorizontal = if (!hasLp) { hasLp = true; horizontal } else {
            lpHorizontal + a * (horizontal - lpHorizontal)
        }

        updateRms(t, x, y, z)

        // Kalkış sayacı
        if (abs(lpHorizontal) >= takeoffMs2) {
            if (takeoffSince == Long.MIN_VALUE) takeoffSince = t
        } else {
            takeoffSince = Long.MIN_VALUE
        }

        return evaluate(t)
    }

    private fun evaluate(t: Long): Boolean {
        if (lastProbeT != Long.MIN_VALUE && t - lastProbeT < nanos(cooldownSeconds)) return false
        // Hiç adım görmemişsek de "adım yok" sayılır; ama en az bir tam
        // pencere veri birikmiş olmalı.
        if (lastStepT != Long.MIN_VALUE && t - lastStepT < nanos(noStepSeconds)) return false
        val rms = lastRms
        if (rms.isNaN() || rms < carriedRmsMin || rms > carriedRmsMax) return false
        if (takeoffSince == Long.MIN_VALUE) return false
        if (t - takeoffSince < nanos(takeoffSeconds)) return false

        lastProbeT = t
        takeoffSince = Long.MIN_VALUE
        return true
    }

    /** Yerçekimine dik bileşenin büyüklüğü. */
    private fun horizontalComponent(x: Float, y: Float, z: Float): Float {
        if (!hasGravity) return 0f
        val gn = sqrt(gx * gx + gy * gy + gz * gz)
        if (gn < 1e-3f) return 0f
        val ux = gx / gn; val uy = gy / gn; val uz = gz / gn
        // Doğrusal ivme = özgül kuvvet - yerçekimi
        val lx = x - gx; val ly = y - gy; val lz = z - gz
        val along = lx * ux + ly * uy + lz * uz
        val hx = lx - along * ux
        val hy = ly - along * uy
        val hz = lz - along * uz
        // İşaret taşıyabilmek için baskın eksene göre işaretlenir; süzgeç
        // ancak böyle "aynı yönde sürüyor mu" sorusunu cevaplayabilir.
        val mag = sqrt(hx * hx + hy * hy + hz * hz)
        return if (hx + hy + hz >= 0f) mag else -mag
    }

    private fun updateRms(t: Long, x: Float, y: Float, z: Float) {
        if (windowStartT == Long.MIN_VALUE) windowStartT = t
        if (!hasGravity) return
        val lx = x - gx; val ly = y - gy; val lz = z - gz
        sumSq += (lx * lx + ly * ly + lz * lz).toDouble()
        count++
        if (t - windowStartT >= nanos(1f)) {
            lastRms = if (count > 0) sqrt(sumSq / count).toFloat() else Float.NaN
            sumSq = 0.0; count = 0; windowStartT = t
        }
    }

    /**
     * İki örnek arası gerçek süre. Sınırlar kasıtlı: sensör duraklaması
     * (ekran kapanması, toplu teslimat) tek bir dev adımla süzgeci sıfırdan
     * doldurup sahte bir "sürdürülmüş kuvvet" yaratabilirdi.
     */
    private fun measuredDt(t: Long): Float {
        val prev = lastAccelT
        lastAccelT = t
        if (prev == Long.MIN_VALUE) return 1f / sampleRateHz
        val dt = (t - prev) / 1e9f
        return dt.coerceIn(1f / 400f, 0.2f)
    }

    private fun nanos(seconds: Float): Long = (seconds * 1e9f).toLong()
}
