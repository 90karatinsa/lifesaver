package com.lifesaver.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sentetik sinyal üreteci.
 *
 * Bu testlerin amacı motorun GERÇEK düşmeleri yakalarken YANLIŞ alarmları
 * elemesidir. Yalnızca "düşmeyi buluyor mu" diye test etmek işe yaramaz:
 * her zaman "düşme" diyen bir dedektör de o testi geçer. Bu yüzden her
 * pozitif senaryonun karşısında bir negatif senaryo vardır.
 */
private class Sim(
    val detector: FallDetector,
    val rateHz: Int = 50,
    seed: Long = 42L,
) {
    private val rnd = Random(seed)
    private val dtNanos = 1_000_000_000L / rateHz
    var t = 0L
        private set

    /** Mevcut yerçekimi yönü (birim vektör). */
    var ux = 0f; var uy = 0f; var uz = 1f

    var outcome: FallOutcome? = null
        private set

    /** Beyaz gürültü genliği (g). */
    var noiseG = 0.002f

    private fun noise() = ((rnd.nextFloat() - 0.5f) * 2f) * g(noiseG)

    private fun push(magnitude: Float, gyroDegS: Float = 0f) {
        val x = ux * magnitude + noise()
        val y = uy * magnitude + noise()
        val z = uz * magnitude + noise()
        detector.onGravity(t, ux * G, uy * G, uz * G)
        if (gyroDegS != 0f) {
            val r = Math.toRadians(gyroDegS.toDouble()).toFloat()
            detector.onGyro(t, r, 0f, 0f)
        } else {
            detector.onGyro(t, noise() * 0.01f, 0f, 0f)
        }
        val o = detector.onAccel(t, x, y, z)
        if (o != null) outcome = o
        t += dtNanos
    }

    fun setOrientation(nx: Float, ny: Float, nz: Float) {
        val n = sqrt(nx * nx + ny * ny + nz * nz)
        ux = nx / n; uy = ny / n; uz = nz / n
    }

    /** Durgun telefon: yalnızca yerçekimi + gürültü. */
    fun still(seconds: Float) = repeat((seconds * rateHz).toInt()) { push(G) }

    /**
     * Yatan bir insanın cebindeki telefon: yerçekimi + solunum modülasyonu.
     * [breathingG] = 0 ise cansız bir nesne (sert zeminde duran telefon).
     */
    fun lying(seconds: Float, breathingG: Float = 0.02f, breathHz: Float = 0.25f) {
        val n = (seconds * rateHz).toInt()
        repeat(n) { i ->
            val phase = 2f * PI.toFloat() * breathHz * (i.toFloat() / rateHz)
            push(G + g(breathingG) * sin(phase.toDouble()).toFloat())
        }
    }

    /** Yürüyüş: 2 Hz salınım + adım olayları. */
    fun walk(seconds: Float) {
        val n = (seconds * rateHz).toInt()
        var nextStep = 0
        repeat(n) { i ->
            val phase = 2f * PI.toFloat() * 2f * (i.toFloat() / rateHz)
            push(G + g(0.35f) * sin(phase.toDouble()).toFloat())
            if (i >= nextStep) {
                detector.onStep(t)
                nextStep = i + rateHz / 2
            }
        }
    }

    /** Serbest düşüş: ivme büyüklüğü [level] g'ye iner. */
    fun freefall(seconds: Float, level: Float) =
        repeat((seconds * rateHz).toInt()) { push(g(level)) }

    /** Yarım sinüs darbe: tepe [peakG], süre [ms]. */
    fun impact(peakG: Float, ms: Int = 100, gyroDegS: Float = 0f) {
        val n = (ms * rateHz / 1000).coerceAtLeast(2)
        repeat(n) { i ->
            val s = sin(PI * i / n).toFloat()
            push(G + g(peakG - 1f) * s, gyroDegS * s)
        }
    }

    /**
     * Sallanan güvertede yatan insan: solunum + 0.2 Hz dalga hareketi.
     * Dalganın tepeden tepeye genliği hareketsizlik kapısının karadaki
     * ölçütünü (0.40 g) aşar; teknede bu HER ZAMAN böyledir.
     */
    fun lyingOnDeck(seconds: Float, waveG: Float = 0.30f) {
        val n = (seconds * rateHz).toInt()
        repeat(n) { i ->
            val sec = i.toFloat() / rateHz
            val breath = g(0.02f) * sin((2f * PI.toFloat() * 0.25f * sec).toDouble()).toFloat()
            val wave = g(waveG) * sin((2f * PI.toFloat() * 0.2f * sec).toDouble()).toFloat()
            push(G + breath + wave)
        }
    }

    /** Yalpalama: yerçekimi yönü döner ve jiroskop bunu görür. */
    fun rollingDeck(seconds: Float, rollDeg: Float, freqHz: Float = 0.2f, waveG: Float = 0f) {
        val n = (seconds * rateHz).toInt()
        repeat(n) { i ->
            val sec = i.toFloat() / rateHz
            val w = 2f * PI.toFloat() * freqHz
            val ang = Math.toRadians((rollDeg * sin((w * sec).toDouble())).toFloat().toDouble())
            ux = sin(ang).toFloat(); uy = 0f; uz = cos(ang).toFloat()
            val rateDegS = rollDeg * w * cos((w * sec).toDouble()).toFloat()
            val breath = g(0.02f) * sin((2f * PI.toFloat() * 0.25f * sec).toDouble()).toFloat()
            val wave = g(waveG) * sin((w * sec).toDouble()).toFloat()
            val mag = G + breath + wave
            detector.onGravity(t, ux * G, uy * G, uz * G)
            detector.onGyro(t, Math.toRadians(rateDegS.toDouble()).toFloat(), 0f, 0f)
            val o = detector.onAccel(t, ux * mag, uy * mag, uz * mag)
            if (o != null) outcome = o
            t += 1_000_000_000L / rateHz
        }
    }

    fun significantMotion() = detector.onSignificantMotion(t)
    fun proximityNear() = detector.onProximity(t, 0f)
    fun dark() = detector.onLight(t, 2f)
}

class FallDetectorTest {

    private fun detector(maxRange: Float = 156.9f) =
        FallDetector(accelMaxRange = maxRange, noiseFloorG = 0.001f)

    // ---- POZİTİF: gerçek düşmeler yakalanmalı ----------------------------

    @Test
    fun `yuruyen kisinin dusmesi algilanir`() {
        val d = detector()
        val sim = Sim(d)
        sim.walk(6f)                       // taşınıyor: silahlanma kapısı açılır
        sim.freefall(0.30f, 0.60f)         // sığ çukur — insan düşmesi
        sim.setOrientation(0f, 1f, 0f)     // cep yatay konuma döner
        sim.impact(peakG = 3.4f, gyroDegS = 300f)
        sim.lying(14f, breathingG = 0.02f) // solunum mikro-hareketi

        val o = sim.outcome
        assertNotNull("bir sonuc uretilmeliydi", o)
        assertTrue("gercek dusme algilanmaliydi, sonuc: $o", o is FallOutcome.Detected)
        o as FallOutcome.Detected
        assertTrue("puan esigi gecmeli", o.score >= 3)
        assertTrue("dikey hiz kaniti bulunmali", o.evidence.verticalVelocity)
        assertTrue("mikro-hareket kaniti bulunmali", o.evidence.micromotion)
    }

    @Test
    fun `sensor kitliginda bile dusme algilanir`() {
        // Yalnızca ivmeölçer: jiroskop, yakınlık, ışık, barometre yok.
        val d = FallDetector(accelMaxRange = 0f, noiseFloorG = 0.001f)
        val sim = Sim(d)
        sim.walk(6f)
        sim.freefall(0.30f, 0.60f)
        sim.setOrientation(0f, 1f, 0f)
        sim.impact(peakG = 3.0f)
        sim.lying(14f, breathingG = 0.02f)

        assertTrue("zayif donanimda da calismali: ${sim.outcome}",
            sim.outcome is FallOutcome.Detected)
    }

    @Test
    fun `significant motion tek basina silahlanma icin yeter`() {
        val d = detector()
        val sim = Sim(d)
        sim.still(2f)
        sim.significantMotion()            // adım yok ama hareket var
        sim.still(3f)
        sim.freefall(0.30f, 0.60f)
        sim.setOrientation(0f, 1f, 0f)
        sim.impact(peakG = 3.2f, gyroDegS = 280f)
        sim.lying(14f, breathingG = 0.02f)

        assertTrue("significant motion silahlandirmali: ${sim.outcome}",
            sim.outcome is FallOutcome.Detected)
    }

    // ---- NEGATİF: yanlış alarmlar elenmeli -------------------------------

    @Test
    fun `masadaki telefon dusurulunce alarm verilmez`() {
        // Sahadaki yanlis alarmlarin %62'si budur.
        val d = detector()
        val sim = Sim(d)
        sim.walk(6f)                        // taşınıyordu (kapı açık)
        sim.freefall(0.45f, 0.05f)          // derin, uzun serbest düşüş
        sim.impact(peakG = 9.0f, ms = 40)   // sert zemine çarpma
        sim.lying(14f, breathingG = 0f)     // cansız: solunum yok

        val o = sim.outcome
        assertTrue("telefon dusmesi reddedilmeliydi, sonuc: $o", o is FallOutcome.Rejected)
        assertEquals(RejectReason.OBJECT_DROP, (o as FallOutcome.Rejected).reason)
    }

    @Test
    fun `tasinmayan telefon alarm kurmaz`() {
        val d = detector()
        val sim = Sim(d)
        sim.still(10f)                      // masada duruyor, adım yok
        sim.freefall(0.30f, 0.60f)
        sim.impact(peakG = 3.5f)
        sim.lying(14f)

        val o = sim.outcome
        assertTrue("silahlanmamis olmaliydi: $o", o is FallOutcome.Rejected)
        assertEquals(RejectReason.NOT_ON_BODY, (o as FallOutcome.Rejected).reason)
    }

    @Test
    fun `dustukten sonra ayaga kalkan kisi icin alarm verilmez`() {
        val d = detector()
        val sim = Sim(d)
        sim.walk(6f)
        sim.freefall(0.30f, 0.60f)
        sim.setOrientation(0f, 1f, 0f)
        sim.impact(peakG = 3.4f, gyroDegS = 300f)
        sim.lying(3f)                       // kısa süre yerde
        sim.walk(12f)                       // sonra kalkıp yürüdü

        val o = sim.outcome
        assertTrue("kalkan kisi icin alarm olmamali: $o", o is FallOutcome.Rejected)
        assertEquals(RejectReason.MOVED_AFTER_IMPACT, (o as FallOutcome.Rejected).reason)
    }

    @Test
    fun `normal yuruyus hic aday uretmez`() {
        val d = detector()
        val sim = Sim(d)
        sim.walk(30f)
        assertNull("yuruyus aday uretmemeli", sim.outcome)
    }

    @Test
    fun `kosmaya benzer sert adimlar aday uretse bile elenir`() {
        val d = detector()
        val sim = Sim(d)
        sim.walk(6f)
        // Serbest düşüş çukuru olmadan tek sert darbe (sert oturma / zıplama)
        sim.impact(peakG = 3.0f)
        sim.walk(14f)                       // hareket devam ediyor

        val o = sim.outcome
        if (o != null) {
            assertTrue("hareket devam ederken alarm olmamali: $o", o is FallOutcome.Rejected)
        }
    }

    @Test
    fun `sert oturma durgunluk gecse bile kanit yetersizse elenir`() {
        val d = FallDetector(accelMaxRange = 156.9f, noiseFloorG = 0.001f)
        val sim = Sim(d)
        sim.walk(6f)
        // Çukur yok, dönme yok, duruş değişmiyor, solunum modülasyonu yok
        // (telefon masaya konmuş gibi): yalnızca darbe + durgunluk.
        sim.impact(peakG = 2.9f)
        sim.lying(14f, breathingG = 0f)

        val o = sim.outcome
        assertTrue("kanit yetersiz kalmali: $o", o is FallOutcome.Rejected)
        assertEquals(RejectReason.INSUFFICIENT_EVIDENCE, (o as FallOutcome.Rejected).reason)
    }

    // ---- Ayırt edici özelliğin kendisi -----------------------------------

    @Test
    fun `solunum mikro hareketi insan ile cansiz nesneyi ayirir`() {
        fun run(breathing: Float): FallOutcome? {
            val d = detector()
            val sim = Sim(d)
            sim.walk(6f)
            sim.freefall(0.30f, 0.60f)
            sim.setOrientation(0f, 1f, 0f)
            sim.impact(peakG = 3.2f)
            sim.lying(14f, breathingG = breathing)
            return sim.outcome
        }

        val withPerson = run(0.02f)
        val withoutPerson = run(0.0f)

        assertTrue("insan varken kanit ateslenmeli",
            (withPerson as? FallOutcome.Detected)?.evidence?.micromotion == true)

        val noPersonEvidence = when (val o = withoutPerson) {
            is FallOutcome.Detected -> o.evidence
            is FallOutcome.Rejected -> o.evidence
            null -> null
        }
        assertTrue("cansiz nesnede mikro-hareket kaniti olmamali",
            noPersonEvidence?.micromotion == false)
    }

    @Test
    fun `kanit listesi turkce aciklanabilir`() {
        val d = detector()
        val sim = Sim(d)
        sim.walk(6f)
        sim.proximityNear()
        sim.dark()
        sim.freefall(0.30f, 0.60f)
        sim.setOrientation(0f, 1f, 0f)
        sim.impact(peakG = 3.4f, gyroDegS = 300f)
        sim.lying(14f, breathingG = 0.02f)

        val o = sim.outcome as FallOutcome.Detected
        val açıklama = o.evidence.describeTurkish()
        assertTrue("aciklama bos olmamali", açıklama.isNotEmpty())
        println("Algilama gerekcesi: ${açıklama.joinToString(", ")} (puan ${o.score})")
    }

    // ---- GERİLEME: örnekleme hızı değişimi (cihazda bulunan hata) --------

    @Test
    fun `kaza algilama silahliyken 200 Hz akista dusme hala algilanir`() {
        // CİHAZDA BULUNAN HATA: kaza algılama silahlanınca aynı ivmeölçer
        // akışı 200 Hz'e çıkıyor ve bu dedektöre de o hızda geliyor. Halka
        // tampon 50 Hz'e göre boyutlanmış olduğu için 20 saniye yerine 5
        // saniye tutuyordu; çarpma sonrası durgunluk kapısı [tepe+2 s,
        // tepe+12 s] penceresini bulamadığından HER aday MOVED_AFTER_IMPACT
        // ile eleniyordu. Yani araç kiplerinde düşme algılama tamamen ölüydü
        // ve günlüğe "elendi" yazdığı için çalışıyor gibi görünüyordu.
        val d = detector()
        val sim = Sim(d, rateHz = 200)
        sim.walk(6f)
        sim.freefall(0.30f, 0.60f)
        sim.setOrientation(0f, 1f, 0f)
        sim.impact(peakG = 3.4f, gyroDegS = 300f)
        sim.lying(14f, breathingG = 0.02f)

        val o = sim.outcome
        assertNotNull("200 Hz akista da bir sonuc uretilmeliydi", o)
        assertTrue("200 Hz akista gercek dusme algilanmaliydi, sonuc: $o",
            o is FallOutcome.Detected)
    }

    @Test
    fun `50 Hz ve 200 Hz ayni dusmede ayni karari verir`() {
        // Örnekleme hızı bir ALGILAMA parametresi değildir; kararı
        // değiştirmemelidir. Bu test iki hızın da aynı sonucu vermesini
        // şart koşar, böylece ileride hız değişimi sessizce davranış
        // değiştiremez.
        fun run(rate: Int): FallOutcome? {
            val sim = Sim(detector(), rateHz = rate)
            sim.walk(6f)
            sim.freefall(0.30f, 0.60f)
            sim.setOrientation(0f, 1f, 0f)
            sim.impact(peakG = 3.4f, gyroDegS = 300f)
            sim.lying(14f, breathingG = 0.02f)
            return sim.outcome
        }
        val slow = run(50)
        val fast = run(200)
        assertTrue("50 Hz: $slow", slow is FallOutcome.Detected)
        assertTrue("200 Hz: $fast", fast is FallOutcome.Detected)
    }

    @Test
    fun `200 Hz akista cansiz nesne hala elenir`() {
        // Tamponu büyütmek duyarlılığı körü körüne artırmamalı: aynı hızda
        // solunumsuz (cansız) senaryo hâlâ elenmelidir. Aksi halde hata
        // düzeltmesi yerine yanlış alarm kaynağı üretmiş oluruz.
        val sim = Sim(detector(), rateHz = 200)
        sim.walk(6f)
        sim.freefall(0.30f, 0.60f)
        sim.setOrientation(0f, 1f, 0f)
        sim.impact(peakG = 3.4f, gyroDegS = 300f)
        sim.lying(14f, breathingG = 0f)   // solunum YOK

        val o = sim.outcome
        assertTrue("cansiz nesne 200 Hz'de de mikro-hareket kaniti almamali",
            o !is FallOutcome.Detected || !(o as FallOutcome.Detected).evidence.micromotion)
    }

    // ---- Tekne: gevşetme tek yönlüdür --------------------------------------

    @Test
    fun `hareketsizlik kapisi yalnizca gevsetilebilir`() {
        // Otomatik bir karar duyarlılığı DÜŞÜREMEZ. Sıkılaştırma isteği
        // sessizce değil, AÇIKÇA reddedilir: çağıran taraf false görür.
        val d = detector()
        val base = d.stillnessRangeG
        assertFalse("sikilastirma kabul edilmemeli", d.relaxStillness(base - 0.1f))
        assertEquals(base, d.stillnessRangeG, 0.0001f)
        assertFalse("ayni deger degisiklik degil", d.relaxStillness(base))

        assertTrue("gevsetme kabul edilmeli", d.relaxStillness(0.80f))
        assertEquals(0.80f, d.stillnessRangeG, 0.0001f)
        assertFalse("gevsettikten sonra geri sikilastirilamaz", d.relaxStillness(0.50f))
        assertEquals(0.80f, d.stillnessRangeG, 0.0001f)

        d.resetStillnessRelax()
        assertEquals(base, d.stillnessRangeG, 0.0001f)
    }

    @Test
    fun `kaba denizde dusme ancak gevsetmeden sonra algilanir`() {
        // Bu testin koruduğu şey SESSİZ bir boşluktur: gevşetme olmadan
        // gerçek bir düşme MOVED_AFTER_IMPACT diye elenir ve günlüğe
        // "elendi" yazıldığı için sistem dışarıdan SAĞLIKLI görünür.
        //
        // SINIR ÖLÇÜLDÜ, VARSAYILMADI. 0.2 Hz dalgada ivme kapısı ancak
        // 0.35 g genlikten (~3.4 m/s^2 düşey salınım) sonra tıkanır:
        //
        //   genlik 0.05 g -> algılandı     genlik 0.35 g -> MOVED_AFTER_IMPACT
        //   genlik 0.20 g -> algılandı     genlik 0.50 g -> MOVED_AFTER_IMPACT
        //
        // ISO 2631-1 ölçeğinde 3.4 m/s^2 "aşırı rahatsız edici" bandın
        // içindedir: yani bu boşluk hafif dalgada DEĞİL, kaba denizde açılır.
        // İnsanların gerçekten düştüğü koşul da tam olarak odur.
        //
        // Yalpalama ayrı bir kapı ve ÖLÇÜLDÜĞÜNDE tıkanmadı: 0.2 Hz'de 30
        // dereceye kadar jiroskop aralığı 60 derece/s ölçütünün altında
        // kaldı. Bu yüzden gevşetilen tek şey ivme kapısıdır.
        fun run(relax: Boolean): FallOutcome? {
            val d = detector()
            if (relax) d.relaxStillness(0.80f)
            val sim = Sim(d)
            sim.walk(6f)                       // guvertede yuruyor
            sim.proximityNear(); sim.dark()
            sim.freefall(0.30f, 0.60f)
            sim.setOrientation(0f, 1f, 0f)
            sim.impact(peakG = 3.4f, gyroDegS = 300f)
            sim.lyingOnDeck(14f, waveG = 0.45f) // kaba deniz; dalga hic durmaz
            return sim.outcome
        }

        val without = run(relax = false)
        assertTrue(
            "gevsetme olmadan kaba denizdeki dusme elenir, sonuc: $without",
            without is FallOutcome.Rejected &&
                without.reason == RejectReason.MOVED_AFTER_IMPACT,
        )
        val with = run(relax = true)
        assertTrue(
            "gevsetmeden sonra kaba denizdeki dusme ALGILANMALI, sonuc: $with",
            with is FallOutcome.Detected,
        )
    }


    @Test
    fun `yalpalama tek basina hareketsizlik kapisini tikamaz`() {
        // Yukarıdaki testin dayandığı ölçüm bir yorum değil, bir ŞART olsun.
        // Tekne iki ayrı şey yapar: DÜŞEY salınım (heave) ve YALPALAMA (roll).
        // Gevşetilen kapı ivme kapısıdır; bu test yalpalamanın o kapıyı
        // tıkamadığını, dolayısıyla jiroskop ölçütüne dokunmaya gerek
        // olmadığını sabitler. Tıkasaydı, gevşetilen tek şeyin ivme kapısı
        // olması sessiz bir boşluk bırakırdı.
        listOf(10f, 20f, 30f).forEach { roll ->
            val d = detector()
            val sim = Sim(d)
            sim.walk(6f); sim.proximityNear(); sim.dark()
            sim.freefall(0.30f, 0.60f)
            sim.setOrientation(0f, 1f, 0f)
            sim.impact(peakG = 3.4f, gyroDegS = 300f)
            sim.rollingDeck(14f, rollDeg = roll, waveG = 0.05f)
            assertTrue(
                "$roll derece yalpalamada dusme algilanmaliydi, sonuc: ${sim.outcome}",
                sim.outcome is FallOutcome.Detected,
            )
        }
    }
}
