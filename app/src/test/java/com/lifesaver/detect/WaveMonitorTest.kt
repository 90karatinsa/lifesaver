package com.lifesaver.detect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/**
 * Dalga imzası testleri.
 *
 * Her pozitif senaryonun karşısına bir negatif kondu. Sebebi asimetri
 * DEĞİL — burada asimetri yok: sürekli "teknedesin" diyen bir izleyici
 * hareketsizlik kapısını karada da gevşetir ve yanlış alarm üretir. Bu
 * yüzden bu sınıfın negatifleri en az pozitifleri kadar önemlidir.
 */
class WaveMonitorTest {

    private val rate = 50

    private class Sim(val w: WaveMonitor, val rate: Int) {
        var t = 0L
        private val rnd = Random(11L)
        var fired = false
            private set

        fun noise(scale: Float) = ((rnd.nextFloat() - 0.5f) * 2f) * scale

        fun seconds(n: Float, block: (Float) -> Triple<Float, Float, Float>) {
            repeat((n * rate).toInt()) {
                val sec = t / 1e9f
                val (x, y, z) = block(sec)
                if (w.onAccel(t, x, y, z)) fired = true
                t += 1_000_000_000L / rate
            }
        }

        /** Güverte: [freqHz] frekansında düşey salınım. */
        fun deck(n: Float, freqHz: Float, amplitude: Float) = seconds(n) { sec ->
            val a = amplitude * sin(2f * PI.toFloat() * freqHz * sec)
            Triple(noise(0.05f), noise(0.05f), G + a + noise(0.05f))
        }

        /** Masa/sabit zemin. */
        fun still(n: Float) = seconds(n) {
            Triple(noise(0.02f), noise(0.02f), G + noise(0.02f))
        }

        /** Yürüme: 1.8 Hz, dalga bandının çok üstünde. */
        fun walking(n: Float) = seconds(n) { sec ->
            val p = 2f * PI.toFloat() * 1.8f * sec
            Triple(2.5f * sin(p), 0.8f * sin(p * 2f), G + 3.0f * sin(p))
        }
    }

    private fun monitor() = WaveMonitor(sampleRateHz = rate)

    // ---- POZİTİF ------------------------------------------------------------

    @Test
    fun `guvertedeki surekli dalga hareketi bildirilir`() {
        // 0.2 Hz = 5 saniyelik periyot; tipik bir kıyı dalgası.
        val sim = Sim(monitor(), rate)
        sim.deck(200f, freqHz = 0.2f, amplitude = 0.6f)
        assertTrue("guvertedeki dalga bildirilmeliydi", sim.fired)
    }

    @Test
    fun `bandin iki ucu da dalga sayilir`() {
        listOf(0.10f, 0.30f).forEach { f ->
            val sim = Sim(monitor(), rate)
            sim.deck(300f, freqHz = f, amplitude = 0.6f)
            assertTrue("$f Hz dalga bandinda sayilmaliydi", sim.fired)
        }
    }

    @Test
    fun `dusme kapisini tikayan dalga mutlaka bildirilir`() {
        // İKİ SINIFI BAĞLAYAN ŞART. FallDetectorTest ölçtü: 0.2 Hz'de düşey
        // salınım genliği ~0.35 g'yi (3.4 m/s^2) aşınca hareketsizlik kapısı
        // tıkanır ve gerçek düşme MOVED_AFTER_IMPACT diye elenir.
        //
        // O halde bu izleyici, kapıyı tıkayan HER dalgayı görmek zorundadır.
        // Göremezse gevşetme hiç tetiklenmez ve boşluk sessizce açık kalır —
        // üstelik günlükte "elendi" yazdığı için sistem sağlıklı görünür.
        val sim = Sim(monitor(), rate)
        sim.deck(200f, freqHz = 0.2f, amplitude = 3.4f)
        assertTrue("kapiyi tikayan dalga bildirilmeliydi", sim.fired)
    }

    // ---- NEGATİF: yanlış bildirim karada yanlış alarm demektir ---------------

    @Test
    fun `sabit zeminde dalga bildirilmez`() {
        val sim = Sim(monitor(), rate)
        sim.still(400f)
        assertFalse("sabit zeminde dalga olmamali", sim.fired)
    }

    @Test
    fun `yuruyus dalga sayilmaz`() {
        // 1.8 Hz, bandın beş katı. Alçak geçiren süzgeç bunu söndürmeli.
        val sim = Sim(monitor(), rate)
        sim.walking(400f)
        assertFalse("yuruyus dalga sayilmamali", sim.fired)
    }

    @Test
    fun `adim atilirken dalga bildirilmez`() {
        // Kanıt karışıksa kanıt yoktur: hareketin kaynağı belirsizdir.
        val w = monitor()
        val sim = Sim(w, rate)
        repeat(40) {
            sim.deck(5f, freqHz = 0.2f, amplitude = 0.6f)
            w.onStep(sim.t)
        }
        assertFalse("adim varken dalga bildirilmemeli", sim.fired)
    }

    @Test
    fun `bandin ustundeki hizli salinim dalga sayilmaz`() {
        // 0.8 Hz: tekne değil, sallanan bir araç ya da sallanan el.
        val sim = Sim(monitor(), rate)
        sim.deck(400f, freqHz = 0.8f, amplitude = 0.6f)
        assertFalse("0.8 Hz dalga bandinda degil", sim.fired)
    }

    @Test
    fun `bandin altindaki cok yavas surukleme dalga sayilmaz`() {
        val sim = Sim(monitor(), rate)
        sim.deck(600f, freqHz = 0.03f, amplitude = 0.6f)
        assertFalse("0.03 Hz dalga bandinda degil", sim.fired)
    }

    @Test
    fun `cok kucuk salinim dalga sayilmaz`() {
        // Masada duran telefonun mikro titreşimi doğru frekansa düşse bile
        // genliği yoktur.
        val sim = Sim(monitor(), rate)
        sim.deck(400f, freqHz = 0.2f, amplitude = 0.02f)
        assertFalse("gurultu duzeyindeki salinim dalga sayilmamali", sim.fired)
    }

    @Test
    fun `kisa sureli sallanma dalga sayilmaz`() {
        // Süreklilik şartı: iki dakikadan kısa hiçbir şey tekne değildir.
        val sim = Sim(monitor(), rate)
        sim.deck(60f, freqHz = 0.2f, amplitude = 0.6f)
        sim.still(30f)
        assertFalse("60 saniyelik sallanma yetmemeli", sim.fired)
    }

    // ---- Dayanıklılık -------------------------------------------------------

    @Test
    fun `akis 200 Hz e ciktiginda ayni karar verilir`() {
        listOf(50, 200).forEach { hz ->
            val sim = Sim(WaveMonitor(sampleRateHz = 50), hz)
            sim.deck(200f, freqHz = 0.2f, amplitude = 0.6f)
            assertTrue("$hz Hz akista dalga kacirildi", sim.fired)
        }
    }

    @Test
    fun `art arda bildirim yapilmaz`() {
        val sim = Sim(monitor(), rate)
        sim.deck(200f, freqHz = 0.2f, amplitude = 0.6f)
        assertTrue(sim.fired)
        val before = sim.t
        var second = false
        val w = sim.w
        var t = before
        repeat(300 * rate) { i ->
            val sec = t / 1e9f
            val a = 0.6f * sin(2f * PI.toFloat() * 0.2f * sec)
            if (w.onAccel(t, 0f, 0f, G + a)) second = true
            t += 1_000_000_000L / rate
        }
        assertFalse("bekleme suresi icinde ikinci bildirim olmamali", second)
    }
}
