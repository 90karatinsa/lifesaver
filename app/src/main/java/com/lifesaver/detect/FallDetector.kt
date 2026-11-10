package com.lifesaver.detect

import kotlin.math.abs
import kotlin.math.sqrt

/** Bir düşme adayının değerlendirme sonucu. */
sealed interface FallOutcome {
    /** Düşme kabul edildi. [evidence] kararın NEDEN verildiğini taşır. */
    data class Detected(
        val peakTimeNanos: Long,
        val peakG: Float,
        val score: Int,
        val evidence: Evidence,
    ) : FallOutcome

    /** Aday reddedildi. [reason] denetim kaydına yazılır. */
    data class Rejected(
        val peakTimeNanos: Long,
        val peakG: Float,
        val reason: RejectReason,
        val score: Int,
        val evidence: Evidence,
    ) : FallOutcome
}

enum class RejectReason {
    /** Telefon taşınmıyordu (masada/çantada/şarjda) — alarm kurulmadı. */
    NOT_ON_BODY,

    /** Uzun ve derin serbest düşüş: insan değil, telefon düştü. */
    OBJECT_DROP,

    /** Sensör doydu + serbest düşüş vardı: sert zemine çarpan telefon. */
    SENSOR_SATURATION,

    /** Çukur ile tepe arası çok uzun — tek bir düşme olayı değil. */
    TROUGH_TOO_EARLY,

    /** Çarpma sonrası kişi hareket etti: düşse bile ayağa kalktı. */
    MOVED_AFTER_IMPACT,

    /** Kapılar geçildi ama destekleyici kanıt yetersiz. */
    INSUFFICIENT_EVIDENCE,
}

/** Hangi kanıtın ateşlendiği — hem puanlama hem de kullanıcıya açıklama için. */
data class Evidence(
    val onBody: Boolean = false,
    val freefallTrough: Boolean = false,
    val verticalVelocity: Boolean = false,
    val gyroImpact: Boolean = false,
    val proximityNear: Boolean = false,
    val micromotion: Boolean = false,
    val postureChange: Boolean = false,
    val darkness: Boolean = false,
    val barometer: Boolean = false,
    val measuredVerticalVelocityMs: Float = Float.NaN,
    val measuredGyroDegS: Float = Float.NaN,
    val measuredPostureDeg: Float = Float.NaN,
    val measuredMicromotionG: Float = Float.NaN,
    val measuredBaroRisePa: Float = Float.NaN,
) {
    val score: Int
        get() = (if (verticalVelocity) Weights.VERTICAL_VELOCITY else 0) +
            (if (gyroImpact) Weights.GYRO_IMPACT else 0) +
            (if (proximityNear) Weights.PROXIMITY_NEAR else 0) +
            (if (micromotion) Weights.MICROMOTION else 0) +
            (if (postureChange) Weights.POSTURE_CHANGE else 0) +
            (if (darkness) Weights.DARKNESS else 0) +
            (if (barometer) Weights.BAROMETER else 0)

    /** Kullanıcıya ve denetim kaydına gösterilecek insan-okur özet. */
    fun describeTurkish(): List<String> = buildList {
        if (freefallTrough) add("serbest düşüş çukuru")
        if (verticalVelocity) add("dikey hız %.1f m/s".format(measuredVerticalVelocityMs))
        if (gyroImpact) add("dönme %.0f derece/s".format(measuredGyroDegS))
        if (proximityNear) add("cepte/kapalı alanda")
        if (micromotion) add("solunum benzeri mikro-hareket")
        if (postureChange) add("duruş değişimi %.0f derece".format(measuredPostureDeg))
        if (darkness) add("karanlık")
        if (barometer) add("irtifa düşüşü %.0f Pa".format(measuredBaroRisePa))
    }
}

/**
 * Düşme algılama durum makinesi.
 *
 * Saf Kotlin'dir — Android bağımlılığı YOKTUR. Bunun sebebi hayati: bu mantık
 * sentetik sinyallerle ve kayıtlı sensör izleriyle JVM'de test edilebilmeli,
 * doğrulaması bir telefonu yere atmaya bağlı olmamalıdır.
 *
 * Tasarım: dört sert kapı (çarpma, çukur zamanlaması, veto yok, çarpma sonrası
 * durgunluk) + ağırlıklı kanıt puanı. Saf VE-zinciri gerçek düşmelerin yarısını
 * kaçırır (Bagala ve ark.), saf puanlama ise yanlış alarm üretir.
 *
 * Kullanım: sensör geri çağrılarını ilgili `on*` metotlarına aktarın.
 * [onAccel] bir aday olgunlaştığında sonuç döndürür, aksi halde null.
 */
class FallDetector(
    val config: FallConfig = FallConfig(),
    /** Sensor.getMaximumRange() (m/s^2). 0 = bilinmiyor, doyma vetosu kapalı. */
    private val accelMaxRange: Float = 0f,
    /** Cihaza özgü gürültü tabanı (g). Kurulumda 30 s durgun ölçümle kalibre edilir. */
    var noiseFloorG: Float = 0.001f,
) {
    private val accel = SampleRing(config.bufferCapacity)
    private val gyro = SampleRing(config.bufferCapacity)
    private val gravity = SampleRing(config.bufferCapacity)

    /** Basınç: x alanında Pascal tutulur. */
    private val pressure = SampleRing(config.maxSampleRateHz * 20)

    private var lastProximityCm = Float.NaN
    private var lastProximityT = 0L
    private var lastLux = Float.NaN
    private var lastLuxT = 0L

    /**
     * Yürürlükteki hareketsizlik aralığı (g).
     *
     * Normalde profilden gelir; dalga hareketi altında [relaxStillness] ile
     * GEVŞETİLEBİLİR. Sıkılaştırılamaz — sebebi aşağıda yazılı.
     */
    var stillnessRangeG: Float = config.stillnessAccelRangeG
        private set

    /**
     * Hareketsizlik kapısını gevşetir. TEK YÖNLÜDÜR.
     *
     * Güvertede dalga hareketi hiç durmaz: karadaki 0.40 g ölçütü teknede
     * asla sağlanmaz ve gerçek bir düşme MOVED_AFTER_IMPACT diye elenirdi —
     * üstelik "elendi" olarak günlüğe yazıldığı için sistem SAĞLIKLI görünür.
     * Gevşetmek yalnızca daha çok adayın geçmesine izin verir; bedeli
     * kullanıcının iptal edebildiği bir yanlış alarmdır.
     *
     * Ters yön, otomatik bir kararın kullanıcıyı sessizce sağırlaştırması
     * demek olurdu; bu yüzden sıkılaştırma isteği sessizce değil, AÇIKÇA
     * yok sayılır.
     */
    fun relaxStillness(rangeG: Float): Boolean {
        if (rangeG <= stillnessRangeG) return false
        stillnessRangeG = rangeG
        return true
    }

    /** Profilin kendi değerine döner (karaya çıkınca / kip değişince). */
    fun resetStillnessRelax() {
        stillnessRangeG = config.stillnessAccelRangeG
    }

    private val stepTimes = ArrayDeque<Long>()
    private var lastSignificantMotionT = Long.MIN_VALUE

    /** Olgunlaşmayı bekleyen aday. */
    private var pendingPeakT = 0L
    private var pendingPeakMag = 0f
    private var hasPending = false

    /** Aynı olaydan art arda aday üretmemek için kör süre. */
    private var refractoryUntilT = Long.MIN_VALUE

    fun reset() {
        accel.clear(); gyro.clear(); gravity.clear(); pressure.clear()
        stepTimes.clear()
        hasPending = false
        refractoryUntilT = Long.MIN_VALUE
        lastProximityCm = Float.NaN; lastLux = Float.NaN
        lastSignificantMotionT = Long.MIN_VALUE
    }

    // ---- Sensör girişleri --------------------------------------------------

    fun onGyro(t: Long, x: Float, y: Float, z: Float) = gyro.add(t, x, y, z)
    fun onGravity(t: Long, x: Float, y: Float, z: Float) = gravity.add(t, x, y, z)
    fun onPressurePa(t: Long, pascals: Float) = pressure.add(t, pascals, 0f, 0f)
    fun onProximity(t: Long, cm: Float) { lastProximityT = t; lastProximityCm = cm }
    fun onLight(t: Long, lux: Float) { lastLuxT = t; lastLux = lux }
    fun onSignificantMotion(t: Long) { lastSignificantMotionT = t }

    fun onStep(t: Long) {
        stepTimes.addLast(t)
        val cutoff = t - (config.onBodyStepWindowSec * 1e9f).toLong()
        while (stepTimes.isNotEmpty() && stepTimes.first() < cutoff) stepTimes.removeFirst()
    }

    /**
     * Ana giriş. Yeni bir ivme örneği besler ve bir aday olgunlaştıysa
     * sonucunu döndürür.
     */
    fun onAccel(t: Long, x: Float, y: Float, z: Float): FallOutcome? {
        accel.add(t, x, y, z)

        val mag = sqrt(x * x + y * y + z * z)

        if (hasPending) {
            // Tepe penceresi hâlâ açıkken daha büyük bir darbe gelirse tepeyi
            // güncelle: eşiği ilk aşan örnek genelde tepe DEĞİLDİR ve doyma
            // kontrolü ile peakG raporu gerçek tepeye bağlıdır.
            if (t <= pendingPeakT + nanosMs(PEAK_REFINE_MS) && mag > pendingPeakMag) {
                pendingPeakMag = mag
                pendingPeakT = t
            }
            if (t >= pendingPeakT + nanos(config.micromotionToSec)) {
                hasPending = false
                refractoryUntilT = t + nanos(5f)
                return evaluate(pendingPeakT, pendingPeakMag)
            }
            return null
        }

        if (t < refractoryUntilT) return null

        if (mag >= g(config.impactG)) {
            pendingPeakT = t
            pendingPeakMag = mag
            hasPending = true
        }
        return null
    }

    private companion object {
        /** Eşik aşıldıktan sonra gerçek tepenin aranmaya devam ettiği süre. */
        const val PEAK_REFINE_MS = 200L
    }

    // ---- Değerlendirme -----------------------------------------------------

    private fun nanos(seconds: Float): Long = (seconds * 1e9f).toLong()
    private fun nanosMs(ms: Long): Long = ms * 1_000_000L

    private fun isOnBody(atT: Long): Boolean {
        val stepCutoff = atT - nanos(config.onBodyStepWindowSec)
        val steps = stepTimes.count { it >= stepCutoff }
        if (steps >= config.onBodyMinSteps) return true
        val smCutoff = atT - nanos(config.onBodySignificantMotionSec)
        return lastSignificantMotionT >= smCutoff
    }

    private fun evaluate(peakT: Long, peakMag: Float): FallOutcome {
        val peakG = peakMag / G
        val empty = Evidence()

        if (!isOnBody(peakT)) {
            return FallOutcome.Rejected(peakT, peakG, RejectReason.NOT_ON_BODY, 0, empty)
        }

        // --- Veto 1: nesne düşmesi (derin ve sürekli serbest düşüş) ---------
        val deepFreefallMs = longestRunBelow(
            fromT = peakT - nanosMs(config.dropVetoLookbackMs),
            toT = peakT,
            thresholdMs2 = g(config.dropVetoG),
        )
        val hadDeepFreefall = deepFreefallMs >= config.dropVetoMinMs
        if (hadDeepFreefall) {
            return FallOutcome.Rejected(peakT, peakG, RejectReason.OBJECT_DROP, 0, empty)
        }

        // --- Veto 2: sensör doyması (yalnızca serbest düşüşle birlikte) -----
        if (hadDeepFreefall && isSaturated(peakT, peakMag)) {
            return FallOutcome.Rejected(peakT, peakG, RejectReason.SENSOR_SATURATION, 0, empty)
        }

        // --- Kapı: çukur zamanlaması ---------------------------------------
        val troughT = findTrough(peakT)
        val hasTrough = troughT != null
        if (troughT != null && peakT - troughT > nanosMs(config.troughToPeakMaxMs)) {
            return FallOutcome.Rejected(peakT, peakG, RejectReason.TROUGH_TOO_EARLY, 0, empty)
        }

        // --- Kapı: çarpma sonrası durgunluk --------------------------------
        if (!isStillAfter(peakT)) {
            return FallOutcome.Rejected(peakT, peakG, RejectReason.MOVED_AFTER_IMPACT, 0, empty)
        }

        // --- Ağırlıklı kanıtlar --------------------------------------------
        val vv = verticalVelocity(peakT)
        val gyroPeak = peakGyroDegS(peakT)
        val posture = postureChangeDeg(peakT)
        val micro = micromotionRmsG(peakT)
        val baro = baroRisePa(peakT)

        val evidence = Evidence(
            onBody = true,
            freefallTrough = hasTrough,
            verticalVelocity = !vv.isNaN() && vv <= config.verticalVelocityMs,
            gyroImpact = !gyroPeak.isNaN() && gyroPeak >= config.impactGyroDegS,
            proximityNear = isProximityNear(peakT),
            micromotion = !micro.isNaN() && micro > noiseFloorG * config.micromotionNoiseMultiple,
            postureChange = !posture.isNaN() && posture >= config.postureChangeDeg,
            darkness = isDark(peakT),
            barometer = !baro.isNaN() && baro >= config.baroRisePa,
            measuredVerticalVelocityMs = vv,
            measuredGyroDegS = gyroPeak,
            measuredPostureDeg = posture,
            measuredMicromotionG = micro,
            measuredBaroRisePa = baro,
        )

        val score = evidence.score
        return if (score >= config.minScore) {
            FallOutcome.Detected(peakT, peakG, score, evidence)
        } else {
            FallOutcome.Rejected(peakT, peakG, RejectReason.INSUFFICIENT_EVIDENCE, score, evidence)
        }
    }

    // ---- Özellik çıkarıcılar -----------------------------------------------

    /** [fromT,toT] aralığında |a| eşiğin altında kaldığı en uzun kesintisiz süre (ms). */
    private fun longestRunBelow(fromT: Long, toT: Long, thresholdMs2: Float): Long {
        val from = accel.indexAtOrAfter(fromT)
        val to = accel.indexAtOrAfter(toT)
        var best = 0L
        var runStart = -1
        for (i in from until to) {
            if (accel.magnitudeAt(i) < thresholdMs2) {
                if (runStart < 0) runStart = i
            } else if (runStart >= 0) {
                best = maxOf(best, (accel.timeAt(i - 1) - accel.timeAt(runStart)) / 1_000_000L)
                runStart = -1
            }
        }
        if (runStart >= 0 && to > runStart) {
            best = maxOf(best, (accel.timeAt(to - 1) - accel.timeAt(runStart)) / 1_000_000L)
        }
        return best
    }

    private fun isSaturated(peakT: Long, peakMag: Float): Boolean {
        if (accelMaxRange <= 0f) return false
        if (peakMag > config.saturationFraction * accelMaxRange * sqrt(3f)) return true

        // Düzleşmiş tepe: art arda örnekler aynı yakın-tavan değerde takılı.
        val from = accel.indexAtOrAfter(peakT - nanosMs(100))
        val to = accel.indexAtOrAfter(peakT + nanosMs(100))
        val edge = accelMaxRange * 0.97f
        var run = 0
        for (i in from until to) {
            val clipped = abs(accel.xAt(i)) >= edge ||
                abs(accel.yAt(i)) >= edge ||
                abs(accel.zAt(i)) >= edge
            run = if (clipped) run + 1 else 0
            if (run >= config.saturationFlatSamples) return true
        }
        return false
    }

    /** Tepeden önceki 1 s içindeki en derin çukur, eşiği geçiyorsa zamanı. */
    private fun findTrough(peakT: Long): Long? {
        val from = accel.indexAtOrAfter(peakT - nanosMs(1000))
        val to = accel.indexAtOrAfter(peakT)
        if (to <= from) return null
        val stats = accel.statsBetween(from, to)
        if (stats.isEmpty || stats.min >= g(config.freefallTroughG)) return null
        return accel.timeAt(stats.minIndex)
    }

    /**
     * Çarpma sonrası [+2 s, +12 s] aralığındaki HER 1 s'lik pencerede
     * hareket eşiğin altında mı? Ayrıca bu aralıkta adım atılmamış olmalı.
     */
    private fun isStillAfter(peakT: Long): Boolean {
        val startT = peakT + nanos(config.stillnessFromSec)
        val endT = peakT + nanos(config.stillnessToSec)

        // Bu aralıkta adım algılandıysa kişi yürüyor demektir.
        if (stepTimes.any { it in startT..endT }) return false

        var winStart = startT
        var windows = 0
        while (winStart + nanos(1f) <= endT) {
            val from = accel.indexAtOrAfter(winStart)
            val to = accel.indexAtOrAfter(winStart + nanos(1f))
            val s = accel.statsBetween(from, to)
            // Veri yoksa durgunluğu ispatlayamayız; ihtiyatlı davranıp reddet.
            if (s.count < config.sampleRateHz / 2) return false
            if (s.max - s.min >= g(stillnessRangeG)) return false

            if (gyro.size > 0) {
                val gf = gyro.indexAtOrAfter(winStart)
                val gt = gyro.indexAtOrAfter(winStart + nanos(1f))
                val gs = gyro.statsBetween(gf, gt)
                if (gs.count > 0) {
                    val rangeDegS = Math.toDegrees((gs.max - gs.min).toDouble()).toFloat()
                    if (rangeDegS >= config.stillnessGyroRangeDegS) return false
                }
            }
            windows++
            winStart += nanos(1f)
        }
        return windows > 0
    }

    /**
     * Çarpmadan önceki 800 ms'de dikey hız değişimi.
     * Yerçekimi yönü çarpmadan >= 1 s önceki örnekten alınır, çünkü çarpma
     * anındaki "yerçekimi" tahmini darbeyle kirlenmiştir.
     */
    private fun verticalVelocity(peakT: Long): Float {
        val gv = meanGravityBetween(peakT - nanos(2f), peakT - nanos(1f)) ?: return Float.NaN
        val (gx, gy, gz) = gv
        val norm = sqrt(gx * gx + gy * gy + gz * gz)
        if (norm < 1e-3f) return Float.NaN
        val ux = gx / norm; val uy = gy / norm; val uz = gz / norm

        val from = accel.indexAtOrAfter(peakT - nanosMs(config.verticalVelocityWindowMs))
        val to = accel.indexAtOrAfter(peakT)
        if (to - from < 2) return Float.NaN

        var v = 0f
        for (i in from until to - 1) {
            val dt = (accel.timeAt(i + 1) - accel.timeAt(i)) / 1e9f
            if (dt <= 0f || dt > 0.5f) continue
            val proj = accel.xAt(i) * ux + accel.yAt(i) * uy + accel.zAt(i) * uz
            v += (proj - G) * dt
        }
        return v
    }

    private fun meanGravityBetween(fromT: Long, toT: Long): Triple<Float, Float, Float>? {
        // TYPE_GRAVITY varsa onu, yoksa ham ivmenin ortalamasını kullan.
        val src = if (gravity.size > 0) gravity else accel
        val from = src.indexAtOrAfter(fromT)
        val to = src.indexAtOrAfter(toT)
        if (to <= from) return null
        var sx = 0f; var sy = 0f; var sz = 0f
        for (i in from until to) { sx += src.xAt(i); sy += src.yAt(i); sz += src.zAt(i) }
        val n = (to - from).toFloat()
        return Triple(sx / n, sy / n, sz / n)
    }

    private fun peakGyroDegS(peakT: Long): Float {
        if (gyro.size == 0) return Float.NaN
        val from = gyro.indexAtOrAfter(peakT - nanosMs(500))
        val to = gyro.indexAtOrAfter(peakT + nanosMs(200))
        if (to <= from) return Float.NaN
        var maxRad = 0f
        for (i in from until to) maxRad = maxOf(maxRad, gyro.magnitudeAt(i))
        return Math.toDegrees(maxRad.toDouble()).toFloat()
    }

    private fun postureChangeDeg(peakT: Long): Float {
        val before = meanGravityBetween(peakT - nanos(3f), peakT - nanos(1.5f)) ?: return Float.NaN
        val after = meanGravityBetween(peakT + nanos(2f), peakT + nanos(4f)) ?: return Float.NaN
        return angleBetweenDegrees(
            before.first, before.second, before.third,
            after.first, after.second, after.third,
        )
    }

    /**
     * Solunum bandındaki mikro-hareketin RMS'i (g).
     * Yerde yatan insanın cebindeki telefon nefes almayla modüle olur;
     * boş telefon yalnızca sensör gürültüsü gösterir.
     */
    private fun micromotionRmsG(peakT: Long): Float {
        val from = accel.indexAtOrAfter(peakT + nanos(config.micromotionFromSec))
        val to = accel.indexAtOrAfter(peakT + nanos(config.micromotionToSec))
        val n = to - from
        if (n < config.sampleRateHz * 3) return Float.NaN

        var mean = 0f
        for (i in from until to) mean += accel.magnitudeAt(i)
        mean /= n

        // dt GERÇEK örnekleme aralığından ölçülür, yapılandırmadan DEĞİL:
        // kaza algılama silahlanınca akış 50 Hz'den 200 Hz'e çıkıyor ve sabit
        // 1/50 varsayımı bu bandı (0.15-0.60 Hz solunum) dört kat kaydırıyordu.
        val spanSec = (accel.timeAt(to - 1) - accel.timeAt(from)) / 1e9f
        val dt = if (spanSec > 0f) spanSec / (n - 1) else 1f / config.sampleRateHz
        var hpPrevIn = 0f; var hpPrevOut = 0f
        val rcHi = 1f / (2f * Math.PI.toFloat() * config.micromotionLowHz)
        val aHi = rcHi / (rcHi + dt)
        val rcLo = 1f / (2f * Math.PI.toFloat() * config.micromotionHighHz)
        val aLo = dt / (rcLo + dt)
        var lpOut = 0f
        var sumSq = 0.0
        var count = 0

        for (i in from until to) {
            val x = accel.magnitudeAt(i) - mean
            val hp = if (i == from) 0f else aHi * (hpPrevOut + x - hpPrevIn)
            hpPrevIn = x; hpPrevOut = hp
            lpOut += aLo * (hp - lpOut)
            // İlk 1 s filtre oturması — istatistiğe katma.
            if (i - from > config.sampleRateHz) { sumSq += lpOut.toDouble() * lpOut; count++ }
        }
        if (count == 0) return Float.NaN
        return (sqrt(sumSq / count) / G).toFloat()
    }

    private fun baroRisePa(peakT: Long): Float {
        if (pressure.size == 0) return Float.NaN
        val bFrom = pressure.indexAtOrAfter(peakT - nanos(5f))
        val bTo = pressure.indexAtOrAfter(peakT - nanos(2f))
        val aFrom = pressure.indexAtOrAfter(peakT + nanos(2f))
        val aTo = pressure.indexAtOrAfter(peakT + nanos(5f))
        if (bTo <= bFrom || aTo <= aFrom) return Float.NaN

        var bSum = 0f; var bSumSq = 0.0
        for (i in bFrom until bTo) { val p = pressure.xAt(i); bSum += p; bSumSq += p.toDouble() * p }
        val bN = (bTo - bFrom).toFloat()
        val bMean = bSum / bN
        val bStd = sqrt(((bSumSq / bN) - bMean.toDouble() * bMean).coerceAtLeast(0.0)).toFloat()
        // Taban çizgisi gürültülüyse (kapı, klima, rüzgâr) barometreye güvenme.
        if (bStd >= config.baroBaselineMaxStdPa) return Float.NaN

        var aSum = 0f
        for (i in aFrom until aTo) aSum += pressure.xAt(i)
        return aSum / (aTo - aFrom).toFloat() - bMean
    }

    private fun isProximityNear(peakT: Long): Boolean {
        if (lastProximityCm.isNaN()) return false
        if (lastProximityT < peakT - nanos(5f) || lastProximityT > peakT + nanos(5f)) return false
        return lastProximityCm < config.proximityNearCm
    }

    private fun isDark(peakT: Long): Boolean {
        if (lastLux.isNaN()) return false
        if (lastLuxT < peakT - nanos(5f) || lastLuxT > peakT + nanos(5f)) return false
        return lastLux < config.darkLux
    }
}
