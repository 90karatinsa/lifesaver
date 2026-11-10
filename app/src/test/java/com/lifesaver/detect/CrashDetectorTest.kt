package com.lifesaver.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Kaza senaryosu üreteci.
 *
 * Her pozitif senaryonun karşısına, onu taklit eden ama kaza OLMAYAN bir
 * negatif senaryo konur: kasis, sert fren, araçta düşen telefon, sportif
 * viraj. Yalnızca kazayı bulan bir dedektör işe yaramaz; her zaman "kaza"
 * diyen bir dedektör de o testleri geçer.
 */
private class CrashSim(
    val detector: CrashDetector,
    val rateHz: Int = 200,
    seed: Long = 11L,
) {
    private val rnd = Random(seed)
    private val dtNanos = 1_000_000_000L / rateHz
    var t = 0L
        private set

    var outcome: CrashOutcome? = null
        private set
    val outcomes = mutableListOf<CrashOutcome>()

    /** Yerçekimi yönü. */
    var ux = 0f; var uy = 0f; var uz = 1f

    var pressureHpa = 1013.0f
    private var lastSpeedPushT = Long.MIN_VALUE
    private var lastPressureT = Long.MIN_VALUE
    var speedMs = 0f
    var speedAccuracy = 0.5f

    private fun noise(amp: Float) = (rnd.nextFloat() - 0.5f) * 2f * amp

    private fun push(magnitude: Float, gyroRadS: Float = 0f, vibration: Float = 0f) {
        val m = magnitude + noise(vibration)
        detector.onGravity(t, ux * G, uy * G, uz * G)
        detector.onGyro(t, gyroRadS, 0f, 0f)

        // Hız 1 Hz'de yayımlanır (GNSS gerçeği).
        if (lastSpeedPushT == Long.MIN_VALUE || t - lastSpeedPushT >= 1_000_000_000L) {
            detector.onSpeed(t, speedMs, speedAccuracy)
            lastSpeedPushT = t
        }
        // Barometre 40 Hz.
        if (lastPressureT == Long.MIN_VALUE || t - lastPressureT >= 25_000_000L) {
            detector.onPressureHpa(t, pressureHpa)
            lastPressureT = t
        }

        detector.onAccel(t, ux * m, uy * m, uz * m)?.let {
            outcome = it
            outcomes += it
        }
        t += dtNanos
    }

    fun setOrientation(nx: Float, ny: Float, nz: Float) {
        val n = sqrt(nx * nx + ny * ny + nz * nz)
        ux = nx / n; uy = ny / n; uz = nz / n
    }

    /** Sürüş: yol titreşimi + hız yayını. */
    fun drive(seconds: Float, kmh: Float, vibration: Float = 1.5f) {
        speedMs = kmh / 3.6f
        repeat((seconds * rateHz).toInt()) { push(G, vibration = vibration) }
    }

    /** Durma / hareketsizlik. */
    fun still(seconds: Float, kmh: Float = 0f, vibration: Float = 0.05f) {
        speedMs = kmh / 3.6f
        repeat((seconds * rateHz).toInt()) { push(G, vibration = vibration) }
    }

    /** Çarpma darbesi: yarım sinüs. */
    fun impact(peakMs2: Float, ms: Int = 120, gyroRadS: Float = 0f) {
        val n = (ms * rateHz / 1000).coerceAtLeast(3)
        repeat(n) { i ->
            val s = sin(PI * i / n).toFloat()
            push(G + (peakMs2 - G) * s, gyroRadS = gyroRadS * s)
        }
    }

    /** Hava yastığı: kabin basıncı sıçraması. */
    fun airbagSpike(deltaHpa: Float = 20f) {
        pressureHpa += deltaHpa
    }

    /** Serbest düşüş: araç içinde telefonun yere düşmesi. */
    fun freefall(seconds: Float) =
        repeat((seconds * rateHz).toInt()) { push(1.0f, vibration = 0.2f) }

    fun rollReversal(radS: Float = 10f, ms: Int = 200) {
        val n = (ms * rateHz / 1000).coerceAtLeast(4)
        repeat(n / 2) { push(G, gyroRadS = radS) }
        repeat(n / 2) { push(G, gyroRadS = -radS) }
    }
}

class CrashDetectorTest {

    /** Pixel 9 Pro'nun ICM-45631'i ±16 g = 156.9 m/s^2. */
    private fun carDetector(maxRange: Float = 156.9f) =
        CrashDetector(accelMaxRange = maxRange, mode = VehicleMode.CAR, sampleRateHz = 200)

    private fun bikeDetector(maxRange: Float = 156.9f) =
        CrashDetector(accelMaxRange = maxRange, mode = VehicleMode.MOTORCYCLE, sampleRateHz = 200)

    // ---- Silahlanma --------------------------------------------------------

    @Test
    fun `yavas giderken silahlanmaz`() {
        val d = carDetector()
        val sim = CrashSim(d)
        sim.drive(20f, kmh = 15f)     // 25 km/s esiginin altinda
        assertFalse("15 km/s'de silahlanmamali", d.isArmed)
    }

    @Test
    fun `otuz km hizda on saniye sonra silahlanir`() {
        val d = carDetector()
        val sim = CrashSim(d)
        sim.drive(6f, kmh = 30f)
        assertFalse("6 saniye yetmemeli", d.isArmed)
        sim.drive(8f, kmh = 30f)
        assertTrue("14 saniye sonra silahlanmali", d.isArmed)
    }

    @Test
    fun `etkinlik tanima tek basina silahlandirir`() {
        val d = carDetector()
        val sim = CrashSim(d)
        d.onVehicleActivity(0, inVehicle = true, confidence = 85)
        assertTrue("IN_VEHICLE silahlandirmali", d.isArmed)
    }

    @Test
    fun `silahlanmamisken buyuk darbe yok sayilir`() {
        // Telefonu masaya birakmak 4 g uretir; arac baglami yoksa onemsizdir.
        val d = carDetector()
        val sim = CrashSim(d)
        sim.still(3f)
        sim.impact(peakMs2 = 60f)
        sim.still(14f)
        assertNull("silahlanmamisken sonuc uretilmemeli", sim.outcome)
    }

    // ---- POZİTİF: gerçek kazalar ------------------------------------------

    @Test
    fun `siddetli arac kazasi yuksek guvenle algilanir`() {
        val d = carDetector()
        val sim = CrashSim(d)
        sim.drive(14f, kmh = 90f)          // silahlan
        sim.speedMs = 0f                   // hiz coktu
        sim.impact(peakMs2 = 170f, ms = 100, gyroRadS = 6f)  // sensor doyuran carpma
        sim.airbagSpike(25f)               // hava yastigi carpmadan SONRA acilir
        sim.still(14f)

        val o = sim.outcome
        assertTrue("kaza algilanmaliydi: $o", o is CrashOutcome.Detected)
        o as CrashOutcome.Detected
        assertTrue("doyma kaniti olmali", o.evidence.saturated)
        assertTrue("hava yastigi kaniti olmali", o.evidence.airbag)
        assertTrue("carpma sonrasi hareketsizlik olmali", o.evidence.stillAfterImpact)
        assertEquals(DetectionConfidence.HIGH, d.confidenceFor(o.score))
    }

    @Test
    fun `hafif kaza orta guvenle algilanir`() {
        val d = carDetector()
        val sim = CrashSim(d)
        sim.drive(14f, kmh = 40f)
        sim.speedMs = 0f
        sim.impact(peakMs2 = 55f, ms = 140)   // doymuyor, hava yastigi yok
        sim.still(14f)

        val o = sim.outcome
        assertTrue("hafif kaza da algilanmali: $o", o is CrashOutcome.Detected)
        o as CrashOutcome.Detected
        assertFalse("doyma olmamali", o.evidence.saturated)
        assertTrue("hiz cokusu kaniti olmali", o.evidence.speedCollapse)
    }

    @Test
    fun `barometresiz cihazda da kaza algilanir`() {
        val d = CrashDetector(accelMaxRange = 156.9f, mode = VehicleMode.CAR, sampleRateHz = 200)
        val sim = CrashSim(d)
        sim.drive(14f, kmh = 80f)
        sim.speedMs = 0f
        sim.impact(peakMs2 = 165f, ms = 100, gyroRadS = 5f)
        sim.still(14f)
        // Basinc hic degismedi -> hava yastigi kaniti yok, ama doyma + hiz var.
        assertTrue("basinc kaniti olmadan da algilanmali: ${sim.outcome}",
            sim.outcome is CrashOutcome.Detected)
    }

    // ---- NEGATİF: kazaya benzeyen ama kaza olmayan --------------------------

    @Test
    fun `kasis alarm uretmez`() {
        // Sert darbe var ama surus devam ediyor.
        val d = carDetector()
        val sim = CrashSim(d)
        sim.drive(14f, kmh = 70f)
        sim.impact(peakMs2 = 50f, ms = 60)
        sim.drive(14f, kmh = 70f)

        val o = sim.outcome
        assertTrue("kasis reddedilmeli: $o", o is CrashOutcome.Rejected)
        assertEquals(CrashReject.STILL_MOVING, (o as CrashOutcome.Rejected).reason)
    }

    @Test
    fun `sert fren tek basina alarm uretmez`() {
        // Hiz cokuyor ama carpma yok ve surucu hareket etmeye devam ediyor.
        val d = carDetector()
        val sim = CrashSim(d)
        sim.drive(14f, kmh = 60f)
        sim.speedMs = 0f
        sim.drive(3f, kmh = 3f)          // durdu
        sim.drive(14f, kmh = 40f)        // sonra devam etti

        val o = sim.outcome
        if (o != null) {
            assertTrue("sert fren alarm uretmemeli: $o", o is CrashOutcome.Rejected)
        }
    }

    @Test
    fun `arac icinde dusen telefon reddedilir`() {
        // Google Research bu hatayi acikca adlandiriyor.
        val d = carDetector()
        val sim = CrashSim(d)
        sim.drive(14f, kmh = 60f)
        d.onVehicleActivity(sim.t, inVehicle = true, confidence = 30)  // zayif baglam
        sim.freefall(0.35f)              // serbest dusus
        sim.impact(peakMs2 = 120f, ms = 40)
        sim.still(14f, kmh = 60f)        // arac gidiyor ama telefon yerde durgun

        val o = sim.outcome
        assertTrue("telefon dusmesi reddedilmeli: $o", o is CrashOutcome.Rejected)
        assertEquals(CrashReject.PHONE_DROPPED, (o as CrashOutcome.Rejected).reason)
    }

    @Test
    fun `normal surus hic aday uretmez`() {
        val d = carDetector()
        val sim = CrashSim(d)
        sim.drive(60f, kmh = 100f, vibration = 0.8f)
        assertNull("normal surus aday uretmemeli", sim.outcome)
    }

    @Test
    fun `kaza sonrasi surusa devam edilirse olay iptal edilir`() {
        val d = carDetector()
        val sim = CrashSim(d)
        sim.drive(14f, kmh = 70f)
        sim.speedMs = 0f
        sim.impact(peakMs2 = 160f, ms = 100, gyroRadS = 5f)
        sim.still(12f)                   // once algilanir
        assertTrue("once algilanmali", sim.outcomes.any { it is CrashOutcome.Detected })

        sim.drive(15f, kmh = 40f)        // sonra surus devam etti

        assertTrue("surusa devam iptal uretmeli",
            sim.outcomes.any { it is CrashOutcome.ResumedDriving })
    }

    // ---- Motosiklet ---------------------------------------------------------

    @Test
    fun `motosiklet dusuk taraf dususu algilanir`() {
        val d = bikeDetector()
        val sim = CrashSim(d)
        sim.drive(14f, kmh = 60f)
        sim.speedMs = 0f
        sim.impact(peakMs2 = 35f, ms = 200, gyroRadS = 5f)  // kayarak, dusuk tepe
        sim.setOrientation(1f, 0f, 0f)   // motosiklet yan yatti
        sim.still(14f)

        val o = sim.outcome
        assertTrue("dusuk taraf dususu algilanmali: $o", o is CrashOutcome.Detected)
        o as CrashOutcome.Detected
        assertTrue("motosiklet olarak isaretlenmeli", o.motorcycle)
        assertTrue("yatma kaniti olmali", o.evidence.leanFall)
    }

    @Test
    fun `sportif viraj alarm uretmez`() {
        // 55 derece yatiliyor ama motosiklet geri kalkiyor: ayirt edici olan
        // acinin kendisi degil, KALICILIGI.
        val d = bikeDetector()
        val sim = CrashSim(d)
        sim.drive(14f, kmh = 80f)
        sim.setOrientation(0.82f, 0f, 0.57f)   // ~55 derece yatik
        sim.impact(peakMs2 = 28f, ms = 150, gyroRadS = 4.5f)
        sim.setOrientation(0f, 0f, 1f)         // dik konuma dondu
        sim.drive(14f, kmh = 80f)              // ve surmeye devam etti

        val o = sim.outcome
        assertTrue("sportif viraj alarm uretmemeli: $o", o is CrashOutcome.Rejected)
        assertEquals(CrashReject.STILL_MOVING, (o as CrashOutcome.Rejected).reason)
    }

    @Test
    fun `motosiklet esigi aractan dusuktur`() {
        val car = CrashConfig()
        assertTrue("motosiklet esigi daha dusuk olmali",
            car.peakAccelFor(VehicleMode.MOTORCYCLE) < car.peakAccelFor(VehicleMode.CAR))
        assertEquals(25f, car.peakAccelFor(VehicleMode.MOTORCYCLE), 0.01f)
        assertEquals(40f, car.peakAccelFor(VehicleMode.CAR), 0.01f)
    }

    // ---- Güven ve açıklanabilirlik -----------------------------------------

    @Test
    fun `guven esikleri arastirmadaki gibi`() {
        val d = carDetector()
        assertEquals(DetectionConfidence.HIGH, d.confidenceFor(70))
        assertEquals(DetectionConfidence.MEDIUM, d.confidenceFor(50))
        assertEquals(DetectionConfidence.LOW, d.confidenceFor(49))
    }

    @Test
    fun `kanit listesi turkce aciklanabilir`() {
        val d = carDetector()
        val sim = CrashSim(d)
        sim.drive(14f, kmh = 90f)
        sim.speedMs = 0f
        sim.impact(peakMs2 = 170f, ms = 100, gyroRadS = 6f)
        sim.airbagSpike(25f)
        sim.still(14f)

        val o = sim.outcome as CrashOutcome.Detected
        val açıklama = o.evidence.describeTurkish()
        assertTrue("aciklama bos olmamali", açıklama.isNotEmpty())
        println("Kaza gerekcesi (puan ${o.score}): ${açıklama.joinToString(", ")}")
    }

    // ---- GERİLEME: silahtan çıkma hız akışına bağlı olmamalı ------------

    @Test
    fun `hiz akisi susunca silah kendiliginden duser`() {
        // CİHAZDA BULUNAN HATA: silahtan çıkma yalnızca onSpeed içinde
        // değerlendiriliyordu ve koşulu "her örnek disarmSpeedMs altında"
        // biçimindeydi. Hiç örnek gelmezse koşul asla sağlanmaz: tünelde,
        // kapalı otoparkta, konum kapalıyken ya da GMS'siz cihazda dedektör
        // SONSUZA KADAR silahlı kalıyordu. Bu, ivmeölçeri 200 Hz'de kilitler
        // (pil) ve düşme algılamayı da bozardı.
        val d = CrashDetector(accelMaxRange = 156.9f, mode = VehicleMode.CAR, sampleRateHz = 200)
        var t = 0L
        fun sec(n: Float) = (n * 1e9f).toLong()

        // 15 saniye şehir içi hızda: silahlanır.
        repeat(15) {
            d.onSpeed(t, 20f, 1f)
            t += sec(1f)
        }
        assertTrue("hizla silahlanmaliydi", d.isArmed)

        // Konum akışı KESİLİR. Yalnızca ivme akmaya devam eder.
        repeat(320) {
            d.onAccel(t, 0f, 0f, G)
            t += sec(1f)
        }
        assertFalse(
            "hiz ornegi gelmese de disarmSustainSeconds sonunda silah dusmeliydi",
            d.isArmed,
        )
    }

    @Test
    fun `hiz akisi surerken silah erken dusmez`() {
        // Düzeltmenin ters yönü: gerçekten sürerken silah düşmemeli.
        val d = CrashDetector(accelMaxRange = 156.9f, mode = VehicleMode.CAR, sampleRateHz = 200)
        var t = 0L
        fun sec(n: Float) = (n * 1e9f).toLong()
        repeat(15) { d.onSpeed(t, 20f, 1f); t += sec(1f) }
        assertTrue(d.isArmed)

        // 10 dakika boyunca sürüş: her saniye ivme, her 5 saniyede bir hız.
        repeat(600) {
            d.onAccel(t, 0f, 0f, G)
            if (it % 5 == 0) d.onSpeed(t, 20f, 1f)
            t += sec(1f)
        }
        assertTrue("surerken silah dusmemeliydi", d.isArmed)
    }
}
