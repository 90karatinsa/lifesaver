package com.lifesaver.detect

import kotlin.math.abs
import kotlin.math.sqrt

/** Kaza algılamanın sonucu. */
sealed interface CrashOutcome {
    data class Detected(
        val peakTimeNanos: Long,
        val motorcycle: Boolean,
        val score: Int,
        val evidence: CrashEvidence,
    ) : CrashOutcome

    data class Rejected(
        val peakTimeNanos: Long,
        val reason: CrashReject,
        val score: Int,
        val evidence: CrashEvidence,
    ) : CrashOutcome

    /**
     * Olay algılandıktan SONRA sürüşe devam edildi.
     * Servis bunu görürse devam eden tırmanmayı iptal etmelidir:
     * 15 km/s ile ilerleyen aracı bilinçli biri kullanıyordur.
     */
    data class ResumedDriving(val secondsAfterEvent: Int) : CrashOutcome
}

enum class CrashReject {
    /** Araç bağlamı yok — kaza algılama silahlanmamıştı. */
    NOT_ARMED,

    /** Araç içinde telefon yere düştü. */
    PHONE_DROPPED,

    /** Çarpma sonrası hareket sürdü: araç kullanılmaya devam ediyor. */
    STILL_MOVING,

    /** Kapılar geçildi ama puan yetersiz. */
    INSUFFICIENT_EVIDENCE,
}

data class CrashEvidence(
    val inVehicle: Boolean = false,
    val saturated: Boolean = false,
    val airbag: Boolean = false,
    val speedCollapse: Boolean = false,
    val violentRotation: Boolean = false,
    val stillAfterImpact: Boolean = false,
    val leanFall: Boolean = false,
    val highSide: Boolean = false,
    val phoneDropSuspected: Boolean = false,
    val peakAccelMs2: Float = 0f,
    val peakGyroRadS: Float = 0f,
    val speedBeforeMs: Float = Float.NaN,
    val speedAfterMs: Float = Float.NaN,
    val pressureRateHpaS: Float = Float.NaN,
    val leanDegrees: Float = Float.NaN,
) {
    fun describeTurkish(): List<String> = buildList {
        if (saturated) add("ivmeölçer doydu (%.0f m/s²)".format(peakAccelMs2))
        else if (peakAccelMs2 > 0f) add("tepe ivme %.0f m/s²".format(peakAccelMs2))
        if (airbag) add("basınç sıçraması %.0f hPa/s".format(pressureRateHpaS))
        if (speedCollapse) add("hız %.0f'dan %.0f km/s'e düştü"
            .format(speedBeforeMs * 3.6f, speedAfterMs * 3.6f))
        if (violentRotation) add("şiddetli dönme %.1f rad/s".format(peakGyroRadS))
        if (leanFall) add("motosiklet %.0f derece yattı ve kalkmadı".format(leanDegrees))
        if (highSide) add("çift darbe (yüksek taraf)")
        if (stillAfterImpact) add("çarpma sonrası hareketsiz")
        if (phoneDropSuspected) add("DİKKAT: telefon düşmesi şüphesi")
    }
}

/** Zaman damgalı hız okuması. */
private data class SpeedSample(val t: Long, val speedMs: Float, val accuracyMs: Float)

/**
 * Araç ve motosiklet kazası algılama.
 *
 * Düşme algılayıcıdan ÜÇ temel farkı vardır:
 *
 * 1. SÜREKLİ ÇALIŞMAZ. Yalnızca araç bağlamı doğrulandığında silahlanır
 *    (hız veya etkinlik tanıma). Silahlı değilken 4 g'lik bir darbe
 *    yok sayılır — çünkü kullanıcı sadece telefonunu masaya bırakmıştır.
 *
 * 2. DOYMA BİR HATA DEĞİL, KANITTIR. Gerçek bir kaza 100 g'yi aşar;
 *    telefon ivmeölçeri ±8-16 g'de doyar. Tepe genliği ÖLÇÜLEMEZ, ama
 *    doymanın kendisi şiddetin kanıtıdır ve en ağır puanı alır.
 *
 * 3. GERİYE DÖNÜK İPTAL EDİLEBİLİR. Kaza algılandıktan sonraki 60 saniye
 *    içinde araç 15 km/s ile ilerlemeye devam ederse olay iptal edilir.
 *    Bu, en güçlü tek yanlış alarm elemesidir.
 *
 * Saf Kotlin'dir; Android bağımlılığı yoktur.
 */
class CrashDetector(
    val config: CrashConfig = CrashConfig(),
    private val accelMaxRange: Float = 0f,
    var mode: VehicleMode = VehicleMode.CAR,
    /** Örnekleme hızı tahmini; tampon boyutlandırması için. */
    sampleRateHz: Int = 200,
) {
    private val capacity = (sampleRateHz * 22).coerceAtLeast(512)
    private val accel = SampleRing(capacity)
    private val gyro = SampleRing(capacity)
    // Yatma açısı, çarpmadan 3 s ÖNCESİ ile 5 s SONRASINI karşılaştırır;
    // bu yüzden yerçekimi halkası da tam pencereyi taşımak zorundadır.
    private val gravity = SampleRing(capacity)

    /** Basınç: x alanında hPa. */
    private val pressure = SampleRing(64 * 22)

    private val speeds = ArrayDeque<SpeedSample>()

    /** Etkinlik tanımadan gelen araç bağlamı (0-100). */
    private var vehicleConfidence = 0
    private var vehicleContextUntil = Long.MIN_VALUE

    private var armedSinceT = Long.MIN_VALUE
    private var fastAboveArmSpeedSince = Long.MIN_VALUE
    private var belowDisarmSpeedSince = Long.MIN_VALUE

    /**
     * Silahlanmayı HAKLI KILAN en son kanıtın zamanı.
     *
     * CİHAZDA BULUNAN HATA: silahtan çıkma yalnızca [onSpeed] içinde
     * değerlendiriliyordu. Konum akışı susarsa (tünel, kapalı otopark,
     * konum servisi kapalı, GMS'siz cihaz) dedektör SONSUZA KADAR silahlı
     * kalıyor, ivmeölçer 200 Hz'de kilitleniyor ve pil sessizce tükeniyordu.
     * Bu alan ivme akışından işleyen bir zaman aşımı sağlar: kanıt gelmiyorsa
     * silah kendiliğinden düşer.
     */
    private var lastArmingEvidenceT = Long.MIN_VALUE

    private var pendingPeakT = 0L
    private var pendingPeakMag = 0f
    private var pendingPeakGyro = 0f
    private var hasPending = false
    private var secondPeakT = 0L
    private var sawRollReversal = false

    /** Olay yayımlandıktan sonra sürüşe devam izleme penceresi. */
    private var watchResumedUntil = Long.MIN_VALUE
    private var resumedSpeedSince = Long.MIN_VALUE
    private var lastEventT = 0L
    private var resumedReported = false

    val isArmed: Boolean get() = armedSinceT != Long.MIN_VALUE

    fun reset() {
        accel.clear(); gyro.clear(); gravity.clear(); pressure.clear()
        speeds.clear()
        armedSinceT = Long.MIN_VALUE
        fastAboveArmSpeedSince = Long.MIN_VALUE
        belowDisarmSpeedSince = Long.MIN_VALUE
        lastArmingEvidenceT = Long.MIN_VALUE
        hasPending = false
        watchResumedUntil = Long.MIN_VALUE
        resumedSpeedSince = Long.MIN_VALUE
        resumedReported = false
        vehicleConfidence = 0
    }

    // ---- Girişler ----------------------------------------------------------

    fun onGyro(t: Long, x: Float, y: Float, z: Float) = gyro.add(t, x, y, z)
    fun onGravity(t: Long, x: Float, y: Float, z: Float) = gravity.add(t, x, y, z)
    fun onPressureHpa(t: Long, hPa: Float) = pressure.add(t, hPa, 0f, 0f)

    /** Etkinlik tanıma sonucu. [confidence] 0-100. */
    fun onVehicleActivity(t: Long, inVehicle: Boolean, confidence: Int) {
        vehicleConfidence = if (inVehicle) confidence else 0
        if (inVehicle && confidence >= 50) {
            vehicleContextUntil = t + nanos(config.disarmSustainSeconds)
            lastArmingEvidenceT = t
            if (armedSinceT == Long.MIN_VALUE) armedSinceT = t
        }
    }

    /** Konum sağlayıcıdan gelen hız. Silahlanmayı da bu yönetir. */
    fun onSpeed(t: Long, speedMs: Float, accuracyMs: Float) {
        speeds.addLast(SpeedSample(t, speedMs, accuracyMs))
        val cutoff = t - nanos(config.disarmSustainSeconds + 10f)
        while (speeds.isNotEmpty() && speeds.first().t < cutoff) speeds.removeFirst()

        // Silahlanma
        if (speedMs >= config.armSpeedMs) {
            lastArmingEvidenceT = t
            if (fastAboveArmSpeedSince == Long.MIN_VALUE) fastAboveArmSpeedSince = t
            if (t - fastAboveArmSpeedSince >= nanos(config.armSustainSeconds) &&
                armedSinceT == Long.MIN_VALUE
            ) {
                armedSinceT = t
            }
        } else {
            fastAboveArmSpeedSince = Long.MIN_VALUE
        }

        // Silahtan çıkma
        if (speedMs < config.disarmSpeedMs) {
            if (belowDisarmSpeedSince == Long.MIN_VALUE) belowDisarmSpeedSince = t
            if (t - belowDisarmSpeedSince >= nanos(config.disarmSustainSeconds) &&
                t > vehicleContextUntil && !hasPending
            ) {
                armedSinceT = Long.MIN_VALUE
            }
        } else {
            belowDisarmSpeedSince = Long.MIN_VALUE
        }
    }

    /**
     * Silahlanma kanıtı bayatladıysa silahtan çıkar.
     *
     * Hız geri çağrısına bağlı OLMAYAN tek silahtan çıkma yoludur; ivme akışı
     * her zaman aktığı için konum sussa bile işler. Koşul negatif kurulur
     * ("kanıt GELMEDİ"), çünkü pozitif kurulum ("her örnek yavaş") hiç örnek
     * gelmediğinde asla sağlanmaz — hatanın kaynağı tam olarak buydu.
     */
    private fun disarmIfEvidenceStale(t: Long) {
        if (armedSinceT == Long.MIN_VALUE || hasPending) return
        if (lastArmingEvidenceT == Long.MIN_VALUE) return
        if (t <= vehicleContextUntil) return
        if (t - lastArmingEvidenceT >= nanos(config.disarmSustainSeconds)) {
            armedSinceT = Long.MIN_VALUE
            fastAboveArmSpeedSince = Long.MIN_VALUE
            belowDisarmSpeedSince = Long.MIN_VALUE
        }
    }

    /**
     * Ana giriş. Bir olay olgunlaştığında sonucunu döndürür.
     * Ayrıca olay sonrası "sürüşe devam" vetosunu da burada değerlendirir.
     */
    fun onAccel(t: Long, x: Float, y: Float, z: Float): CrashOutcome? {
        accel.add(t, x, y, z)
        disarmIfEvidenceStale(t)

        // Olay sonrası: araç yeniden hızlandı mı?
        if (!resumedReported && t <= watchResumedUntil) {
            checkResumedDriving(t)?.let { return it }
        }

        val mag = sqrt(x * x + y * y + z * z)

        if (hasPending) {
            if (t <= pendingPeakT + nanosMs(PEAK_REFINE_MS) && mag > pendingPeakMag) {
                pendingPeakMag = mag
                pendingPeakT = t
            }
            // Motosiklette ikinci darbe (yüksek taraf) aranır.
            if (mode == VehicleMode.MOTORCYCLE && secondPeakT == 0L) {
                val dt = (t - pendingPeakT) / 1e9f
                if (dt in config.highSideSecondPeakMinSeconds..config.highSideSecondPeakMaxSeconds &&
                    mag >= config.motorcyclePeakAccelMs2
                ) {
                    secondPeakT = t
                }
            }
            val matureAt = pendingPeakT +
                nanos(config.stillnessStartSeconds + config.stillnessWindowSeconds)
            if (t >= matureAt) {
                hasPending = false
                lastEventT = pendingPeakT
                watchResumedUntil = t + nanos(config.resumedWatchSeconds)
                resumedSpeedSince = Long.MIN_VALUE
                resumedReported = false
                return evaluate(pendingPeakT, pendingPeakMag)
            }
            return null
        }

        // ---- Aşama 1: aday tetikleyiciler ---------------------------------
        if (!isArmed) return null

        val gyroMag = gyro.last()?.magnitude ?: 0f
        val accelTrigger = mag >= config.peakAccelFor(mode)
        val gyroTrigger = gyroMag >= config.peakGyroRadS
        val speedTrigger = detectSpeedCollapse(t) != null

        if (accelTrigger || gyroTrigger || speedTrigger) {
            pendingPeakT = t
            pendingPeakMag = mag
            pendingPeakGyro = gyroMag
            hasPending = true
            secondPeakT = 0L
            sawRollReversal = detectRollReversal(t)
        }
        return null
    }

    // ---- Değerlendirme -----------------------------------------------------

    private fun nanos(seconds: Float): Long = (seconds * 1e9f).toLong()
    private fun nanosMs(ms: Long): Long = ms * 1_000_000L

    private fun evaluate(peakT: Long, peakMag: Float): CrashOutcome {
        if (!isArmed) {
            return CrashOutcome.Rejected(peakT, CrashReject.NOT_ARMED, 0, CrashEvidence())
        }

        val saturated = accelMaxRange > 0f && isSaturatedNear(peakT)
        val airbagRate = pressureRateHpaS(peakT)
        val airbag = !airbagRate.isNaN() && airbagRate >= config.airbagPressureRateHpaS
        val collapse = detectSpeedCollapse(peakT)
        val gyroPeak = peakGyroBetween(peakT - nanos(1f), peakT + nanos(1f))
        val still = isStillAfter(peakT)
        val lean = leanAngleAfter(peakT)
        val leanFall = mode == VehicleMode.MOTORCYCLE && !lean.isNaN() &&
            lean >= config.leanFallDegrees
        val highSide = mode == VehicleMode.MOTORCYCLE && secondPeakT != 0L && sawRollReversal
        val dropped = isPhoneDropSuspected(peakT)

        val evidence = CrashEvidence(
            inVehicle = vehicleConfidence >= 50,
            saturated = saturated,
            airbag = airbag,
            speedCollapse = collapse != null,
            violentRotation = gyroPeak >= config.peakGyroRadS,
            stillAfterImpact = still,
            leanFall = leanFall,
            highSide = highSide,
            phoneDropSuspected = dropped,
            peakAccelMs2 = peakMag,
            peakGyroRadS = gyroPeak,
            speedBeforeMs = collapse?.first ?: Float.NaN,
            speedAfterMs = collapse?.second ?: Float.NaN,
            pressureRateHpaS = airbagRate,
            leanDegrees = lean,
        )

        // Çarpma sonrası hareket sürüyorsa kimse yardıma ihtiyaç duymuyor demektir.
        if (!still) {
            return CrashOutcome.Rejected(peakT, CrashReject.STILL_MOVING, 0, evidence)
        }

        var score = 0
        // Darbe şiddeti kendi başına kanıttır. Doyma olmayan ama eşiğin
        // belirgin üstünde kalan bir darbe (orta şiddetli kaza) yalnızca
        // hız çöküşü + hareketsizlikle eşiğin altında kalıyordu.
        val threshold = config.peakAccelFor(mode)
        if (peakMag >= threshold * 1.5f) score += CrashWeights.IMPACT_SEVERE
        else if (peakMag >= threshold) score += CrashWeights.IMPACT_MODERATE
        if (saturated) score += CrashWeights.SATURATION
        if (airbag) score += CrashWeights.AIRBAG
        if (collapse != null) score += CrashWeights.SPEED_COLLAPSE
        if (evidence.violentRotation) score += CrashWeights.GYRO
        if (still) score += CrashWeights.STILLNESS
        if (leanFall) score += CrashWeights.LEAN_FALL
        if (highSide) score += CrashWeights.HIGH_SIDE
        if (evidence.inVehicle) score += CrashWeights.IN_VEHICLE

        if (dropped) {
            // Araç bağlamı zayıfsa telefon düşmesi sert veto; güçlüyse ceza.
            if (vehicleConfidence < 50) {
                return CrashOutcome.Rejected(peakT, CrashReject.PHONE_DROPPED, 0, evidence)
            }
            score -= config.dropVetoPenalty
        }
        score = score.coerceIn(0, 100)

        return if (score >= config.mediumConfidenceScore) {
            CrashOutcome.Detected(peakT, mode == VehicleMode.MOTORCYCLE, score, evidence)
        } else {
            CrashOutcome.Rejected(peakT, CrashReject.INSUFFICIENT_EVIDENCE, score, evidence)
        }
    }

    /** Puandan güven düzeyi. Geri sayım süresini bu belirler. */
    fun confidenceFor(score: Int): DetectionConfidence = when {
        score >= config.highConfidenceScore -> DetectionConfidence.HIGH
        score >= config.mediumConfidenceScore -> DetectionConfidence.MEDIUM
        else -> DetectionConfidence.LOW
    }

    // ---- Özellik çıkarıcılar ------------------------------------------------

    private fun isSaturatedNear(peakT: Long): Boolean {
        val edge = accelMaxRange * config.saturationFraction
        val from = accel.indexAtOrAfter(peakT - nanosMs(200))
        val to = accel.indexAtOrAfter(peakT + nanosMs(200))
        for (i in from until to) {
            if (abs(accel.xAt(i)) >= edge || abs(accel.yAt(i)) >= edge ||
                abs(accel.zAt(i)) >= edge
            ) return true
        }
        return false
    }

    /** Çarpmadan sonraki 2 s içindeki en büyük basınç türevi (hPa/s). */
    private fun pressureRateHpaS(peakT: Long): Float {
        if (pressure.size < 2) return Float.NaN
        // Hava yastığı çarpmadan ~20-50 ms sonra açılır, ama barometre örneği
        // çarpma tepesiyle aynı ana denk gelebilir; pencereyi biraz geriye al.
        val from = pressure.indexAtOrAfter(peakT - nanos(0.2f))
        val to = pressure.indexAtOrAfter(peakT + nanos(config.airbagWindowSeconds))
        if (to - from < 2) return Float.NaN
        var maxRate = 0f
        for (i in from until to - 1) {
            val dt = (pressure.timeAt(i + 1) - pressure.timeAt(i)) / 1e9f
            if (dt <= 0f) continue
            val rate = abs(pressure.xAt(i + 1) - pressure.xAt(i)) / dt
            if (rate > maxRate) maxRate = rate
        }
        return maxRate
    }

    /**
     * Hız çöküşü varsa (öncesi, sonrası) hızları döndürür.
     *
     * GNSS düzeltmeleri ~1 Hz gelir, dolayısıyla çarpma anının "öncesi" örneği
     * bir saniye geride olabilir. Bu yüzden pencere çarpmanın ETRAFINA
     * yerleştirilir; çarpma anından geriye bakmak, tek bir örnek kayması
     * yüzünden gerçek kazaları kaçırır.
     */
    private fun detectSpeedCollapse(centerT: Long): Pair<Float, Float>? {
        if (speeds.size < 2) return null
        val window = nanos(config.speedCollapseWindowSeconds)
        val span = nanos(3f)
        val recent = speeds.filter { it.t in (centerT - span)..(centerT + span) }
        if (recent.size < 2) return null

        for (i in 0 until recent.size - 1) {
            val before = recent[i]
            if (before.speedMs < config.speedCollapseFromMs) continue
            if (before.accuracyMs > config.speedAccuracyMaxMs) continue
            for (j in i + 1 until recent.size) {
                val after = recent[j]
                if (after.t - before.t > window) break
                if (after.accuracyMs > config.speedAccuracyMaxMs) continue
                if (after.speedMs <= config.speedCollapseToMs) {
                    return before.speedMs to after.speedMs
                }
            }
        }
        return null
    }

    private fun peakGyroBetween(fromT: Long, toT: Long): Float {
        if (gyro.size == 0) return 0f
        val from = gyro.indexAtOrAfter(fromT)
        val to = gyro.indexAtOrAfter(toT)
        var max = 0f
        for (i in from until to) max = maxOf(max, gyro.magnitudeAt(i))
        return max
    }

    /** Yüksek taraf imzası: dönme hızının işaret değiştirmesi. */
    private fun detectRollReversal(atT: Long): Boolean {
        if (gyro.size < 4) return false
        val from = gyro.indexAtOrAfter(atT - nanos(1f))
        val to = gyro.indexAtOrAfter(atT)
        var sawPositive = false
        var sawNegative = false
        for (i in from until to) {
            val x = gyro.xAt(i)
            if (x >= config.highSideRollReversalRadS) sawPositive = true
            if (x <= -config.highSideRollReversalRadS) sawNegative = true
        }
        return sawPositive && sawNegative
    }

    /**
     * Çarpma sonrası durgunluk: yerçekimi çıkarılmış ivmenin RMS'i.
     * Her ticari sistemin dayandığı ölçüt budur.
     */
    private fun isStillAfter(peakT: Long): Boolean {
        val from = accel.indexAtOrAfter(peakT + nanos(config.stillnessStartSeconds))
        val to = accel.indexAtOrAfter(
            peakT + nanos(config.stillnessStartSeconds + config.stillnessWindowSeconds)
        )
        val n = to - from
        if (n < 10) return false

        var mean = 0f
        for (i in from until to) mean += accel.magnitudeAt(i)
        mean /= n

        var sumSq = 0.0
        for (i in from until to) {
            val d = accel.magnitudeAt(i) - mean
            sumSq += d.toDouble() * d
        }
        return sqrt(sumSq / n).toFloat() < config.stillnessRmsMs2
    }

    /**
     * Motosiklet yatma açısı: çarpma sonrası yerçekimi yönünün, çarpma
     * öncesindeki yönden sapması. Ayırt edici olan açı değil KALICILIĞIDIR:
     * spor sürücüler 60 dereceye yatar ama motosiklet geri kalkar.
     */
    private fun leanAngleAfter(peakT: Long): Float {
        val before = meanGravity(peakT - nanos(3f), peakT - nanos(1f)) ?: return Float.NaN
        val persistFrom = peakT + nanos(config.stillnessStartSeconds)
        val persistTo = persistFrom + nanos(config.leanPersistSeconds)
        val after = meanGravity(persistFrom, persistTo) ?: return Float.NaN
        return angleBetweenDegrees(
            before.first, before.second, before.third,
            after.first, after.second, after.third,
        )
    }

    private fun meanGravity(fromT: Long, toT: Long): Triple<Float, Float, Float>? {
        val src = if (gravity.size > 0) gravity else accel
        val from = src.indexAtOrAfter(fromT)
        val to = src.indexAtOrAfter(toT)
        if (to <= from) return null
        var sx = 0f; var sy = 0f; var sz = 0f
        for (i in from until to) { sx += src.xAt(i); sy += src.yAt(i); sz += src.zAt(i) }
        val n = (to - from).toFloat()
        return Triple(sx / n, sy / n, sz / n)
    }

    private fun isPhoneDropSuspected(peakT: Long): Boolean {
        val from = accel.indexAtOrAfter(peakT - nanosMs(config.dropVetoLookbackMs))
        val to = accel.indexAtOrAfter(peakT)
        var runStart = -1
        for (i in from until to) {
            if (accel.magnitudeAt(i) < config.dropVetoAccelMs2) {
                if (runStart < 0) runStart = i
                val ms = (accel.timeAt(i) - accel.timeAt(runStart)) / 1_000_000L
                if (ms >= config.dropVetoMinMs) return true
            } else {
                runStart = -1
            }
        }
        return false
    }

    private fun checkResumedDriving(t: Long): CrashOutcome.ResumedDriving? {
        val latest = speeds.lastOrNull() ?: return null
        if (latest.speedMs >= config.resumedSpeedMs) {
            if (resumedSpeedSince == Long.MIN_VALUE) resumedSpeedSince = latest.t
            if (latest.t - resumedSpeedSince >= nanos(config.resumedSustainSeconds)) {
                resumedReported = true
                return CrashOutcome.ResumedDriving(
                    secondsAfterEvent = ((t - lastEventT) / 1_000_000_000L).toInt()
                )
            }
        } else {
            resumedSpeedSince = Long.MIN_VALUE
        }
        return null
    }

    private companion object { const val PEAK_REFINE_MS = 150L }
}

/** Algılama güveni. Geri sayım süresini bu belirler. */
enum class DetectionConfidence { LOW, MEDIUM, HIGH }
