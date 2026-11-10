package com.lifesaver.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Eşik ayarlayıcı testleri.
 *
 * Bu testlerin çoğu sistemin ÖĞRENMEMESİ gereken durumları sınar. Sebebi:
 * iptaller yalnızca olumsuz örnek üretir, dolayısıyla denetimsiz bir
 * öğrenici tek yönlü olarak duyarsızlaşır ve kendini sessizce sağır eder.
 * Bir hayat kurtarma uygulamasında asıl risk yanlış öğrenmek değil,
 * HİÇ ÇALMAYI öğrenmektir.
 */
class ThresholdTunerTest {

    private val baselines = mapOf(
        TunableParameter.FALL_IMPACT_G to 2.6f,
        TunableParameter.FALL_MIN_SCORE to 3f,
    )

    private fun record(
        peakG: Float,
        reason: CancelReason = CancelReason.FALSE_ALARM,
        confidence: String = "MEDIUM",
        mode: String = "PEDESTRIAN",
    ) = CancellationRecord(
        timestampMs = 0,
        mode = mode,
        scenario = "FALL",
        score = 4,
        confidence = confidence,
        reason = reason,
        features = mapOf("peakG" to peakG),
    )

    // ---- ÖĞRENMEMESİ gereken durumlar --------------------------------------

    @Test
    fun `tek bir iptal hicbir seyi degistirmez`() {
        val proposals = ThresholdTuner().propose(listOf(record(2.8f)), baselines, 0)
        assertTrue("tek ornek yeterli olmamali", proposals.isEmpty())
    }

    @Test
    fun `gercek olay ama iyiyim denince OGRENILMEZ`() {
        // Bu, esigin DOGRU oldugunu soyler. Bundan ogrenmek dedektore
        // gercek dusmeleri gormezden gelmeyi ogretir.
        val records = List(5) { record(2.8f, reason = CancelReason.REAL_BUT_FINE) }
        val proposals = ThresholdTuner().propose(records, baselines, 0)
        assertTrue("gercek olaydan ogrenilmemeli", proposals.isEmpty())
    }

    @Test
    fun `yanlislikla iptalden ogrenilmez`() {
        val records = List(5) { record(2.8f, reason = CancelReason.ACCIDENTAL) }
        assertTrue(ThresholdTuner().propose(records, baselines, 0).isEmpty())
    }

    @Test
    fun `sebep belirtilmezse ogrenilmez`() {
        val records = List(5) { record(2.8f, reason = CancelReason.UNSPECIFIED) }
        assertTrue("ihtiyatli davranilmali",
            ThresholdTuner().propose(records, baselines, 0).isEmpty())
    }

    @Test
    fun `yuksek guvenli iptal daha fazla kanit ister`() {
        // Yuksek guvenli bir alarmin yanlis cikmasi modelin ciddi bicimde
        // yanildigi anlamina gelir; tek turda duyarliligi dusurmek tehlikeli.
        val three = List(3) { record(2.8f, confidence = "HIGH") }
        assertTrue("3 ornek yuksek guven icin yetmemeli",
            ThresholdTuner().propose(three, baselines, 0).isEmpty())

        val five = List(5) { record(2.8f, confidence = "HIGH") }
        assertTrue("5 ornek yeterli olmali",
            ThresholdTuner().propose(five, baselines, 0).isNotEmpty())
    }

    @Test
    fun `karisik sebepler arasindan yalnizca yanlis alarmlar sayilir`() {
        val records = listOf(
            record(2.8f, CancelReason.FALSE_ALARM),
            record(2.9f, CancelReason.REAL_BUT_FINE),
            record(2.7f, CancelReason.ACCIDENTAL),
        )
        assertTrue("yalnizca 1 ogretici ornek var, degisiklik olmamali",
            ThresholdTuner().propose(records, baselines, 0).isEmpty())
    }

    // ---- ÖĞRENMESİ gereken durum --------------------------------------------

    @Test
    fun `uc yanlis alarmdan sonra esik gozlemin hemen ustune cikar`() {
        val records = listOf(record(2.7f), record(2.9f), record(2.8f))
        val tuning = ThresholdTuner().propose(records, baselines, 1000).single()

        assertEquals(TunableParameter.FALL_IMPACT_G, tuning.parameter)
        assertEquals("gozlemlenen en buyugun hemen ustu olmali", 3.0f, tuning.current, 0.01f)
        assertEquals(2.6f, tuning.baseline, 0.01f)
        assertEquals(3, tuning.supportingCancellations)
        assertTrue("gerekce yazilmali", tuning.note.contains("yanlış alarm"))
    }

    @Test
    fun `degisim gozlemlenen yanlis alarmi elemeye YETECEK kadar olur`() {
        // 2.7 g'lik yanlis alarmlar icin esigi 3.4'e cikarmak gereksizdir;
        // her fazla artis gercek dusmeleri kacirma riskidir.
        val records = List(3) { record(2.65f) }
        val tuning = ThresholdTuner().propose(records, baselines, 0).single()
        assertTrue("gereginden fazla yukselmemeli: ${tuning.current}", tuning.current < 2.8f)
    }

    // ---- SINIRLAR: sistemin kendini sağır etmesini engelleyen kurallar ------

    @Test
    fun `esik tavani asamaz`() {
        // 5 g'lik "yanlis alarmlar" gelse bile esik tavanin ustune cikamaz.
        val records = List(3) { record(5.0f) }
        val proposals = ThresholdTuner().propose(records, baselines, 0)
        val impact = proposals.firstOrNull { it.parameter == TunableParameter.FALL_IMPACT_G }
        assertNull("tavani asan darbe esigi onerilmemeli", impact)
    }

    @Test
    fun `tavana dayaninca duyarlilik yerine daha fazla kanit istenir`() {
        val records = List(3) { record(5.0f) }
        val tuning = ThresholdTuner().propose(records, baselines, 0).single()

        assertEquals("esik yerine puan yukseltilmeli",
            TunableParameter.FALL_MIN_SCORE, tuning.parameter)
        assertEquals(4f, tuning.current, 0.01f)
        assertTrue(tuning.note.contains("daha fazla"))
    }

    @Test
    fun `uygulama tavani zorlar`() {
        val tuner = ThresholdTuner()
        // Bozuk ya da kurcalanmis bir kalici kayit siniri asamaz.
        val rogue = Tuning(
            mode = "PEDESTRIAN",
            parameter = TunableParameter.FALL_IMPACT_G,
            baseline = 2.6f, current = 99f, ceiling = 99f,
            supportingCancellations = 3, updatedAtMs = 0, note = "",
        )
        assertEquals("tavan uygulama aninda da zorlanmali",
            2.6f * ThresholdTuner.MAX_RELATIVE_INCREASE, tuner.apply(2.6f, rogue), 0.01f)
    }

    @Test
    fun `ayarlama yokken taban deger kullanilir`() {
        assertEquals(2.6f, ThresholdTuner().apply(2.6f, null), 0.001f)
    }

    @Test
    fun `esik asla taban degerin ALTINA inemez`() {
        // Duyarliligi ARTIRMAK bu sistemin isi degil: gercek pozitif ornegi
        // yok, dolayisiyla esigi dusurmek icin dayanagi da yok.
        val tuner = ThresholdTuner()
        val lowering = Tuning(
            mode = "PEDESTRIAN", parameter = TunableParameter.FALL_IMPACT_G,
            baseline = 2.6f, current = 1.0f, ceiling = 3.38f,
            supportingCancellations = 3, updatedAtMs = 0, note = "",
        )
        assertEquals(2.6f, tuner.apply(2.6f, lowering), 0.001f)
    }

    // ---- Kipler birbirinden bağımsızdır -------------------------------------

    @Test
    fun `kipler ayri ogrenir`() {
        val records = List(3) { record(2.8f, mode = "PEDESTRIAN") } +
            List(3) { record(3.9f, mode = "RUNNING") }
        val proposals = ThresholdTuner().propose(records, baselines, 0)

        assertEquals("her kip icin ayri ayarlama olmali", 2, proposals.size)
        val modes = proposals.map { it.mode }.toSet()
        assertEquals(setOf("PEDESTRIAN", "RUNNING"), modes)
    }

    @Test
    fun `bir kipteki ogrenme digerini etkilemez`() {
        val records = List(3) { record(2.8f, mode = "RUNNING") }
        val proposals = ThresholdTuner().propose(records, baselines, 0)
        assertTrue("yalnizca kosu kipi degismeli",
            proposals.all { it.mode == "RUNNING" })
    }

    // ---- Şeffaflık ----------------------------------------------------------

    @Test
    fun `her ayarlama insan tarafindan okunabilir gerekce tasir`() {
        val records = List(3) { record(2.8f) }
        val tuning = ThresholdTuner().propose(records, baselines, 0).single()
        assertTrue("gerekce bos olmamali", tuning.note.isNotBlank())
        assertTrue("kac ornekten ogrenildigi yazmali", tuning.note.contains("3"))
        assertTrue("degisim yuzdesi hesaplanabilmeli", tuning.changePercent in 1..30)
    }

    @Test
    fun `tavana dayanan ayarlama isaretlenir`() {
        val atCeiling = Tuning(
            mode = "PEDESTRIAN", parameter = TunableParameter.FALL_IMPACT_G,
            baseline = 2.6f, current = 3.38f, ceiling = 3.38f,
            supportingCancellations = 5, updatedAtMs = 0, note = "",
        )
        assertTrue("tavandaki ayarlama kullaniciya bildirilmeli", atCeiling.isAtCeiling)
    }

    // ---- Puan eşiğinin uygulanması ------------------------------------

    @Test
    fun `puan esigi mutlak sinirla uygulanir`() {
        // Göreli tavan (%30) tam sayı puanda anlamsızdır: 3 × 1.30 = 3.9,
        // toInt() ile 3'e döner ve ayarlama HİÇ uygulanmazdı. Bu test o
        // sessiz etkisizliğe karşıdır.
        val tuning = Tuning(
            mode = "PEDESTRIAN",
            parameter = TunableParameter.FALL_MIN_SCORE,
            baseline = 3f, current = 4f, ceiling = 5f,
            supportingCancellations = 4, updatedAtMs = 0L, note = "",
        )
        assertEquals(4, ThresholdTuner().applyScore(3, tuning))
    }

    @Test
    fun `puan esigi tavani asamaz`() {
        val rogue = Tuning(
            mode = "PEDESTRIAN",
            parameter = TunableParameter.FALL_MIN_SCORE,
            baseline = 3f, current = 99f, ceiling = 5f,
            supportingCancellations = 4, updatedAtMs = 0L, note = "bozuk kayit",
        )
        // Bozuk ya da elle kurcalanmış bir kayıt sistemi sağır edemez.
        assertEquals(3 + ThresholdTuner.MAX_SCORE_INCREASE, ThresholdTuner().applyScore(3, rogue))
    }

    @Test
    fun `puan esigi asla dusurulemez`() {
        val lowering = Tuning(
            mode = "PEDESTRIAN",
            parameter = TunableParameter.FALL_MIN_SCORE,
            baseline = 3f, current = 1f, ceiling = 5f,
            supportingCancellations = 4, updatedAtMs = 0L, note = "",
        )
        // Öğrenme yalnızca duyarlılığı DÜŞÜREBİLİR yönde çalışır; eşiği
        // gevşetip yanlış alarm üretmesi bu sistemin işi değildir.
        assertEquals(3, ThresholdTuner().applyScore(3, lowering))
    }

    @Test
    fun `ayarlama yoksa puan esigi degismez`() {
        assertEquals(3, ThresholdTuner().applyScore(3, null))
    }
}
