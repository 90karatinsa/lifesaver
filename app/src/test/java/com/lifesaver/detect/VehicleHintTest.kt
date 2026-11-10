package com.lifesaver.detect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/**
 * Araç ipucu testleri.
 *
 * Bu sınıf alarm üretmez, yalnızca "konumu açıp bakmaya değer" der. Yine de
 * her pozitif senaryonun karşısına bir negatif kondu: her zaman "araçta" diyen
 * bir ipucu, pili boşuna yakan ve hiçbir şey söylemeyen bir ipuçtur.
 */
class VehicleHintTest {

    private val rate = 50
    private val dt = 1_000_000_000L / rate

    private class Sim(val hint: VehicleHint, val rate: Int) {
        var t = 0L
        private val rnd = Random(7L)
        var fired = false
            private set

        fun noise(scale: Float) = ((rnd.nextFloat() - 0.5f) * 2f) * scale

        /** Yerçekimi z ekseninde; cepteki dik telefon. */
        fun push(lx: Float, ly: Float, lz: Float) {
            hint.onGravity(0f, 0f, G)
            if (hint.onAccel(t, lx, ly, G + lz)) fired = true
            t += 1_000_000_000L / rate
        }

        fun seconds(n: Float, block: (Int) -> Triple<Float, Float, Float>) {
            repeat((n * rate).toInt()) { i ->
                val (a, b, c) = block(i)
                push(a, b, c)
            }
        }

        /** Masada duran telefon: neredeyse hiç hareket yok. */
        fun onTable(n: Float) = seconds(n) { Triple(noise(0.005f), noise(0.005f), noise(0.005f)) }

        /** Duran araçta oturuyor: motor titreşimi var, kalkış yok. */
        fun idling(n: Float) = seconds(n) {
            Triple(noise(0.25f), noise(0.25f), noise(0.25f))
        }

        /** Araç kalkışı: yatay düzlemde SÜRDÜRÜLMÜŞ kuvvet. */
        fun accelerating(n: Float, ms2: Float) = seconds(n) {
            Triple(ms2 + noise(0.3f), noise(0.3f), noise(0.3f))
        }

        /** Yürüme: yatay kuvvet her adımda işaret değiştirir. */
        fun walking(n: Float) = seconds(n) { i ->
            val phase = 2f * PI.toFloat() * 1.8f * (i.toFloat() / rate)
            Triple(2.5f * sin(phase), 0.8f * sin(phase * 2f), 3.0f * sin(phase))
        }
    }

    private fun hint() = VehicleHint(sampleRateHz = rate)

    // ---- POZİTİF ------------------------------------------------------------

    @Test
    fun `araca binip kalkinca yoklama tetiklenir`() {
        val sim = Sim(hint(), rate)
        sim.idling(95f)                       // 90 sn'den uzun süre adımsız oturma
        sim.accelerating(5f, ms2 = 1.6f)      // kalkış
        assertTrue("kalkis yoklamayi tetiklemeliydi", sim.fired)
    }

    // ---- NEGATİF: yanlış tetikleme pil yakar --------------------------------

    @Test
    fun `yuruyen kisi yoklama tetiklemez`() {
        // Ayıran şey genlik değil SÜREKLİLİK: yürümenin yatay kuvveti her
        // adımda işaret değiştirir, alçak geçirilmiş bileşeni sıfıra yakındır.
        val sim = Sim(hint(), rate)
        sim.walking(120f)
        assertFalse("yuruyus yoklama tetiklememeli", sim.fired)
    }

    @Test
    fun `adim atilirsa yoklama tetiklenmez`() {
        val sim = Sim(hint(), rate)
        sim.idling(95f)
        sim.hint.onStep(sim.t)                // tek bir adım bile yeter
        sim.accelerating(5f, ms2 = 1.6f)
        assertFalse("adim goruldugunde yaya sayilmali", sim.fired)
    }

    @Test
    fun `masada duran telefon uzun sure beklese de yoklama tetiklemez`() {
        // Bandın ALT ucunun işi bu: ölü duran telefon, sensör gürültüsü
        // yüzünden saatte defalarca GNSS penceresi açtırmamalı.
        //
        // DİKKAT — kasten test ETMEDİĞİMİZ şey: masadaki bir telefona 3
        // saniye boyunca sürdürülmüş 1.6 m/s^2 yatay kuvvet uygulanırsa
        // ipucu ateşler. Bu bir kusur değil: bir masa böyle bir şey yapmaz.
        // O sinyali üretebilen şey, telefonun hareket eden bir aracın
        // içinde (koltukta, torpidoda) olmasıdır ve orada yoklama DOĞRU
        // davranıştır.
        val sim = Sim(hint(), rate)
        sim.onTable(600f)
        assertFalse("masadaki telefon icin konum acilmamali", sim.fired)
    }

    @Test
    fun `masada kaydirilan telefon yoklama tetiklemez`() {
        // Gerçek masa senaryosu: telefon itiliyor/kaydırılıyor. Yatay kuvvet
        // var ama bir saniyeden kısa.
        val sim = Sim(hint(), rate)
        sim.onTable(95f)
        repeat(3) {
            sim.accelerating(0.8f, ms2 = 2.5f)
            sim.onTable(4f)
        }
        assertFalse("kaydirma kalkis sayilmamali", sim.fired)
    }

    @Test
    fun `kisa bir sarsinti yoklama tetiklemez`() {
        val sim = Sim(hint(), rate)
        sim.idling(95f)
        sim.accelerating(1.5f, ms2 = 3f)      // 3 sn'den kisa
        sim.idling(5f)
        assertFalse("kisa sarsinti kalkis sayilmamali", sim.fired)
    }

    @Test
    fun `duran aracta beklerken yoklama tetiklenmez`() {
        val sim = Sim(hint(), rate)
        sim.idling(200f)
        assertFalse("kalkis olmadan yoklama olmamali", sim.fired)
    }

    // ---- Örnekleme hızından bağımsızlık --------------------------------------

    @Test
    fun `akis 200 Hz e ciktiginda ipucu ayni karari verir`() {
        // Servis, kaza algılayıcı silahlanınca ivmeölçeri 50 Hz'den 200 Hz'e
        // çıkarır. İpucu bir kez kurulur; sabit hız varsayan bir süzgeç
        // kesimi dört katına kaydırır ve davranışı sessizce değiştirirdi.
        // Aynı FİZİKSEL senaryo iki hızda da aynı sonucu vermeli.
        listOf(50, 200).forEach { hz ->
            val sim = Sim(VehicleHint(sampleRateHz = 50), hz)
            sim.idling(95f)
            sim.accelerating(5f, ms2 = 1.6f)
            assertTrue("$hz Hz akista kalkis kacirildi", sim.fired)
        }
    }

    @Test
    fun `sensor duraklamasi sahte kalkis uretmez`() {
        // Ekran kapanınca sensör örnekleri toplu gelebilir. Ölçülen zaman
        // farkı sınırlanmazsa tek bir dev adım süzgeci anında doldurur ve
        // hiç yaşanmamış bir "sürdürülmüş kuvvet" yaratırdı.
        val h = VehicleHint(sampleRateHz = 50)
        var fired = false
        var t = 0L
        repeat(200) {                          // 95 sn boşluklu, adımsız
            h.onGravity(0f, 0f, G)
            if (h.onAccel(t, 1.6f, 0f, G)) fired = true
            t += 30_000_000_000L               // 30 saniyelik sıçramalar
        }
        assertFalse("duraklamadan sonra iki ornekle kalkis ilan edilmemeli", fired)
    }

    // ---- Bekleme süresi -----------------------------------------------------

    @Test
    fun `art arda yoklama yapilmaz`() {
        // Her yoklama bir GNSS penceresi demek; bekleme süresi olmadan
        // yanlış bir tetikleyici pili boşaltabilir.
        val h = hint()
        val sim = Sim(h, rate)
        sim.idling(95f)
        sim.accelerating(5f, ms2 = 1.6f)
        assertTrue(sim.fired)

        var second = false
        repeat(60 * rate) {                   // 60 saniye daha kalkış
            h.onGravity(0f, 0f, G)
            if (h.onAccel(sim.t, 1.6f, 0f, G)) second = true
            sim.t += 1_000_000_000L / rate
        }
        assertFalse("bekleme suresi icinde ikinci yoklama olmamali", second)
    }
}
