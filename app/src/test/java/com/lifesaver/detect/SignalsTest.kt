package com.lifesaver.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalsTest {

    private fun ms(v: Long) = v * 1_000_000L

    @Test
    fun `durgun telefonun buyuklugu yaklasik yercekimi`() {
        val s = Sample(0, 0f, 0f, G)
        assertEquals(9.80665f, s.magnitude, 0.001f)
    }

    @Test
    fun `halka tampon kapasiteyi asinca en eskiyi dusurur`() {
        val ring = SampleRing(3)
        repeat(5) { ring.add(ms(it.toLong()), it.toFloat(), 0f, 0f) }
        assertEquals(3, ring.size)
        assertTrue(ring.isFull)
        // en eski tutulan 2, en yeni 4
        assertEquals(2f, ring.xAt(0), 0f)
        assertEquals(4f, ring.xAt(2), 0f)
        assertEquals(4f, ring.last()!!.x, 0f)
    }

    @Test
    fun `bos tampon guvenli davranir`() {
        val ring = SampleRing(8)
        assertEquals(0, ring.size)
        assertEquals(null, ring.last())
        assertEquals(0f, ring.spanSeconds(), 0f)
        assertTrue(ring.windowStats(1f).isEmpty)
    }

    @Test
    fun `pencere istatistikleri serbest dusus ve carpmayi bulur`() {
        val ring = SampleRing(200)
        // 100 Hz'de 1 saniye: once durgun, sonra serbest dusus, sonra carpma
        var t = 0L
        repeat(50) { ring.add(t, 0f, 0f, G); t += ms(10) }        // 0.5 s durgun
        repeat(20) { ring.add(t, 0f, 0f, g(0.15f)); t += ms(10) } // 0.2 s serbest dusus
        repeat(5) { ring.add(t, 0f, 0f, g(4.2f)); t += ms(10) }   // carpma tepesi

        val stats = ring.windowStats(1.0f)
        assertEquals(g(0.15f), stats.min, 0.01f)
        assertEquals(g(4.2f), stats.max, 0.01f)
        // Serbest dusus, carpmadan ONCE gelmeli
        assertTrue("serbest dusus carpmadan once olmali", stats.minIndex < stats.maxIndex)
    }

    @Test
    fun `spanSeconds tamponun kapsadigi sureyi verir`() {
        val ring = SampleRing(100)
        var t = 0L
        repeat(50) { ring.add(t, 0f, 0f, G); t += ms(20) } // 20 ms aralik = 50 Hz
        assertEquals(0.98f, ring.spanSeconds(), 0.001f)
    }

    @Test
    fun `durgunluk yuksek sapmadan ayirt edilir`() {
        val still = SampleRing(100)
        var t = 0L
        repeat(100) { still.add(t, 0.02f, 0f, G); t += ms(20) }
        assertTrue("durgun telefonun sapmasi kucuk olmali", still.windowStats(2f).stdDev < 0.1f)

        val moving = SampleRing(100)
        t = 0L
        repeat(100) {
            val bump = if (it % 2 == 0) g(1.8f) else g(0.4f)
            moving.add(t, 0f, 0f, bump); t += ms(20)
        }
        assertTrue("hareketli telefonun sapmasi buyuk olmali", moving.windowStats(2f).stdDev > 3f)
    }

    @Test
    fun `dik ile yatay arasindaki duruş degisimi doksan derece`() {
        // Dik (ekran dikey): yercekimi -Y ekseninde. Yatay: yercekimi +Z ekseninde.
        val upright = floatArrayOf(0f, -G, 0f)
        val flat = floatArrayOf(0f, 0f, G)
        val angle = angleBetweenDegrees(
            upright[0], upright[1], upright[2],
            flat[0], flat[1], flat[2],
        )
        assertEquals(90f, angle, 0.5f)
    }

    @Test
    fun `ayni yon sifir derece zit yon yuz seksen derece`() {
        assertEquals(0f, angleBetweenDegrees(0f, 0f, G, 0f, 0f, G), 0.01f)
        assertEquals(180f, angleBetweenDegrees(0f, 0f, G, 0f, 0f, -G), 0.01f)
    }

    @Test
    fun `sifir vektor aci hesabini cokertmez`() {
        assertEquals(0f, angleBetweenDegrees(0f, 0f, 0f, 0f, 0f, G), 0f)
    }

    @Test
    fun `egim acisi ters donmus cihazi tanir`() {
        assertEquals(0f, inclinationDegrees(0f, 0f, G), 0.01f)      // ekran yukari
        assertEquals(90f, inclinationDegrees(0f, G, 0f), 0.01f)     // dik
        assertEquals(180f, inclinationDegrees(0f, 0f, -G), 0.01f)   // ekran asagi / ters
    }

    @Test
    fun `alcak gecirenli filtre yercekimini izler ve ani sicramalari yumusatir`() {
        val lp = LowPass(0.8f)
        repeat(200) { lp.update(0f, 0f, G) }
        assertEquals(G, lp.z, 0.01f)

        // Tek bir 8g darbe yercekimi tahminini buyuk olcude zayiflatilmis gecmeli:
        // alpha=0.8 icin cikis 0.8*9.81 + 0.2*78.45 = 23.5 m/s^2, yani darbenin ~%30'u.
        val spike = g(8f)
        lp.update(0f, 0f, spike)
        assertTrue("darbe en az %60 zayiflatilmali", lp.z < spike * 0.4f)

        // Darbe gectikten sonra tahmin yercekimine geri donmeli
        repeat(60) { lp.update(0f, 0f, G) }
        assertEquals("filtre yercekimine geri yakinsamali", G, lp.z, 0.05f)
    }

    @Test
    fun `doyma tespiti sensor tavaninda tetiklenir`() {
        val maxRange = 78.4f // ~8g tipik telefon ivmeolcer tavani
        assertTrue(isClipping(Sample(0, 0f, 0f, 78.0f), maxRange))
        assertTrue(isClipping(Sample(0, -78.4f, 0f, 0f), maxRange))
        assertFalse(isClipping(Sample(0, 0f, 0f, G), maxRange))
        assertFalse("tavan bilinmiyorsa doyma iddia etme", isClipping(Sample(0, 0f, 0f, 999f), 0f))
    }
}

/**
 * Sensör canlılık testleri.
 *
 * Bu testler, "sensör var görünüyor ama veri sahte" durumunu yakalar —
 * uygulamanın kullanıcıyı koruduğunu sanırken korumadığı senaryo.
 */
class SensorLivenessTest {

    private fun feed(l: SensorLiveness, n: Int, mag: Float, noise: Float = 0f, startMs: Long = 0) {
        var t = startMs
        val rnd = java.util.Random(7)
        repeat(n) {
            val jitter = if (noise > 0f) (rnd.nextFloat() - 0.5f) * 2f * noise else 0f
            l.onSample(0f, 0f, mag + jitter, t)
            t += 20
        }
    }

    @Test
    fun `hic olay gelmediyse NO_EVENTS`() {
        val l = SensorLiveness()
        assertEquals(Liveness.NO_EVENTS, l.evaluate(1000))
    }

    @Test
    fun `yeterli ornek yokken WARMING`() {
        val l = SensorLiveness()
        feed(l, 50, G, noise = 0.02f)
        assertEquals(Liveness.WARMING, l.evaluate(1000))
    }

    @Test
    fun `normal durgun telefon OK`() {
        val l = SensorLiveness()
        feed(l, 250, G, noise = 0.02f)
        assertEquals(Liveness.OK, l.evaluate(5000))
    }

    @Test
    fun `tamamen sabit sinyal ZEROED olarak isaretlenir`() {
        // Gercek bir MEMS sensoru asla tam sabit deger uretmez.
        val l = SensorLiveness()
        feed(l, 250, G, noise = 0f)
        assertEquals(Liveness.ZEROED, l.evaluate(5000))
    }

    @Test
    fun `sifir veri ZEROED olarak isaretlenir`() {
        // GrapheneOS sensor izni reddedildiginde tam olarak bu gorulur.
        val l = SensorLiveness()
        feed(l, 250, 0f, noise = 0.00001f)
        assertEquals(Liveness.ZEROED, l.evaluate(5000))
    }

    @Test
    fun `yercekimsiz veri IMPLAUSIBLE`() {
        val l = SensorLiveness()
        feed(l, 250, 2.5f, noise = 0.05f)
        assertEquals(Liveness.IMPLAUSIBLE, l.evaluate(5000))
    }

    @Test
    fun `olaylar durursa NO_EVENTS e doner`() {
        val l = SensorLiveness()
        feed(l, 250, G, noise = 0.02f)
        assertEquals(Liveness.OK, l.evaluate(5000))
        // 25 saniye sonra hala veri yoksa
        assertEquals(Liveness.NO_EVENTS, l.evaluate(5000 + 25_000))
    }

    @Test
    fun `her durum icin turkce aciklama var`() {
        val l = SensorLiveness()
        for (state in Liveness.entries) {
            assertTrue("$state icin aciklama bos", l.describe(state).isNotBlank())
        }
    }
}
