package com.lifesaver.alert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tırmanma zinciri testleri.
 *
 * Sahte saat kullanılır: gerçek bir acil durumun 150. saniyesinde ne olduğunu
 * beklemeden doğrulayabilmek zorundayız.
 */
class EscalationTest {

    private val contacts = listOf(
        Contact("Anne", "+905321111111"),
        Contact("Kardes", "+905322222222"),
    )

    private val ctx = AlertContext(
        userName = "Bora",
        scenario = Scenario.FALL,
        fix = Fix(40.9735, 28.8189, 12f),
        timeText = "14:32",
        callbackNumber = "+905321234567",
    )

    private fun machine(scenario: Scenario = Scenario.FALL) =
        EscalationMachine(contacts) { ctx.copy(scenario = scenario) }

    /** Saati ilerletip biriken tüm emirleri toplar. */
    private fun EscalationMachine.runUntil(seconds: Int, stepMs: Long = 1000L): List<Action> {
        val all = mutableListOf<Action>()
        var t = 0L
        while (t <= seconds * 1000L) {
            all += advanceTo(t)
            t += stepMs
        }
        return all
    }

    @Test
    fun `algilama alarmi ve konum yayinini hemen baslatir`() {
        val m = machine()
        val actions = m.onDetection(0, Scenario.FALL, Confidence.HIGH)

        assertTrue(actions.any { it is Action.StartAlarm })
        assertTrue(actions.any { it is Action.StartLocationBroadcast })
        assertEquals(EscalationState.COUNTDOWN, m.state)
    }

    @Test
    fun `geri sayim icinde yakinlara haber verilmez`() {
        val m = machine()
        m.onDetection(0, Scenario.FALL, Confidence.HIGH)
        val actions = m.runUntil(25)   // düşme planı 30 s geri sayım

        assertFalse("geri sayim bitmeden SMS gitmemeli",
            actions.any { it is Action.SendContactSms })
        assertEquals(EscalationState.COUNTDOWN, m.state)
    }

    @Test
    fun `geri sayim bitince yakinlara SMS gider ve aranir`() {
        val m = machine()
        m.onDetection(0, Scenario.FALL, Confidence.HIGH)
        val actions = m.runUntil(40)

        val sms = actions.filterIsInstance<Action.SendContactSms>()
        assertEquals("tam olarak bir SMS turu olmali", 1, sms.size)
        assertEquals(contacts, sms.first().to)

        val calls = actions.filterIsInstance<Action.CallContact>()
        assertEquals("ilk yakin aranmali", "Anne", calls.first().contact.name)
    }

    @Test
    fun `yakinlar sirayla aranir`() {
        val m = machine()
        m.onDetection(0, Scenario.FALL, Confidence.HIGH)
        val calls = m.runUntil(90).filterIsInstance<Action.CallContact>()

        assertEquals(2, calls.size)
        assertEquals("Anne", calls[0].contact.name)
        assertEquals("Kardes", calls[1].contact.name)
    }

    // ---- Hukuki kısıtların kod olarak sınanması --------------------------

    @Test
    fun `112 kanali olay basina yalnizca BIR KEZ kullanilir`() {
        // Kabahatler Kanunu m.42-A: israrla cagri birakmak ayrica cezalandirilir.
        val m = machine()
        m.onDetection(0, Scenario.FALL, Confidence.HIGH)
        val actions = m.runUntil(1200)   // 20 dakika boyunca calistir

        val emergencySms = actions.filterIsInstance<Action.Send112Sms>()
        assertEquals("112'ye birden fazla mesaj gitmemeli", 1, emergencySms.size)

        val dialer = actions.filterIsInstance<Action.Surface112Dialer>()
        assertEquals("cevirici bir kez yuzeye cikarilmali", 1, dialer.size)
    }

    @Test
    fun `dusuk guvende 112 ye otomatik SMS gonderilmez`() {
        val m = machine()
        m.onDetection(0, Scenario.FALL, Confidence.MEDIUM)
        val actions = m.runUntil(300)

        assertTrue("guven HIGH degilse 112'ye otomatik mesaj gitmemeli",
            actions.filterIsInstance<Action.Send112Sms>().isEmpty())
        // Ama cevirici yine de sunulur: karar bir insana birakilir.
        assertTrue("cevirici yine de sunulmali",
            actions.any { it is Action.Surface112Dialer })
    }

    @Test
    fun `112 kanali yakinlardan SONRA acilir`() {
        val m = machine()
        m.onDetection(0, Scenario.FALL, Confidence.HIGH)

        val before = m.runUntil(120)
        assertTrue("t+120s'de 112 kanali daha acilmamali",
            before.filterIsInstance<Action.Send112Sms>().isEmpty())
        assertTrue("ama yakinlara coktan haber verilmis olmali",
            before.any { it is Action.SendContactSms })

        val after = m.runUntil(200)
        assertTrue("t+150s'den sonra 112 kanali acilmali",
            after.any { it is Action.Send112Sms })
    }

    @Test
    fun `SMS tekrari sinirlidir`() {
        // Android'in SMS hiz siniri asilirsa sistem kullanici onayi ister;
        // baygin kullanici bunu veremez.
        val m = machine()
        m.onDetection(0, Scenario.FALL, Confidence.HIGH)
        val rounds = m.runUntil(7200).filterIsInstance<Action.SendContactSms>()

        assertEquals("SMS turu plandaki sinirla ayni olmali", 4, rounds.size)
    }

    // ---- İptal ve çözülme ------------------------------------------------

    @Test
    fun `iptal zinciri durdurur ve 112 ye asla ulasilmaz`() {
        val m = machine()
        m.onDetection(0, Scenario.FALL, Confidence.HIGH)
        m.runUntil(10)
        val cancel = m.onUserCancel(10_000)

        assertTrue(cancel.any { it is Action.StopAlarm })
        assertEquals(EscalationState.CANCELLED, m.state)

        val after = m.runUntil(600)
        assertTrue("iptalden sonra hicbir emir uretilmemeli", after.isEmpty())
    }

    @Test
    fun `112 kanali acildiktan sonra iptal yakinlari bilgilendirir`() {
        val m = machine()
        m.onDetection(0, Scenario.FALL, Confidence.HIGH)
        m.runUntil(200)                       // 112 kanali acildi
        val cancel = m.onUserCancel(200_000)

        val info = cancel.filterIsInstance<Action.SendContactSms>()
        assertEquals("yanlis alarm oldugu yakinlara bildirilmeli", 1, info.size)
        assertTrue(info.first().text.contains("yanlis alarm"))
    }

    @Test
    fun `yakin onay verince zincir durur ama konum yayini surer`() {
        val m = machine()
        m.onDetection(0, Scenario.FALL, Confidence.HIGH)
        m.runUntil(60)
        val resolved = m.onContactConfirmed(60_000, contacts[0])

        assertEquals(EscalationState.RESOLVED, m.state)
        assertTrue(resolved.any { it is Action.StopAlarm })
        assertTrue("gelen kisinin bulabilmesi icin yayin durmamali",
            resolved.none { it is Action.StopLocationBroadcast })

        val after = m.runUntil(600)
        assertTrue("onaydan sonra 112 kanali acilmamali",
            after.filterIsInstance<Action.Send112Sms>().isEmpty())
    }

    // ---- Senaryoya özgü zamanlama ---------------------------------------

    @Test
    fun `denizde geri sayim kisadir`() {
        val m = machine(Scenario.MARINE_OVERBOARD)
        m.onDetection(0, Scenario.MARINE_OVERBOARD, Confidence.HIGH)
        val actions = m.runUntil(25)

        assertTrue("denizde 20 s sonra yakinlara haber verilmeli",
            actions.any { it is Action.SendContactSms })
    }

    @Test
    fun `yanitsizlik senaryosunda kullaniciya en uzun sure verilir`() {
        val m = machine(Scenario.NO_RESPONSE)
        m.onDetection(0, Scenario.NO_RESPONSE, Confidence.HIGH)

        assertTrue("t+50s'de hala geri sayimda olmali",
            m.runUntil(50).none { it is Action.SendContactSms })
        assertTrue("t+65s'de haber verilmeli",
            m.runUntil(65).any { it is Action.SendContactSms })
    }

    @Test
    fun `manuel cagri hemen tirmanir`() {
        val m = machine(Scenario.MANUAL)
        m.onDetection(0, Scenario.MANUAL, Confidence.HIGH)
        val actions = m.runUntil(3)

        assertTrue("manuel cagri beklemeden haber vermeli",
            actions.any { it is Action.SendContactSms })
    }

    // ---- Sağlamlık --------------------------------------------------------

    @Test
    fun `ayni ana iki kez ilerletmek yan etki uretmez`() {
        val m = machine()
        m.onDetection(0, Scenario.FALL, Confidence.HIGH)
        m.advanceTo(35_000)
        val second = m.advanceTo(35_000)

        assertTrue("ayni zaman damgasi tekrar islenmemeli", second.isEmpty())
    }

    @Test
    fun `aktif olay varken yeni algilama yok sayilir`() {
        val m = machine()
        m.onDetection(0, Scenario.FALL, Confidence.HIGH)
        val second = m.onDetection(5_000, Scenario.VEHICLE_CRASH, Confidence.HIGH)

        assertTrue("ikinci tetikleme alarm baslatmamali",
            second.none { it is Action.StartAlarm })
    }

    @Test
    fun `geri sayim son on saniyede her saniye duyurulur`() {
        val m = machine()
        // İlk duyuru (30 s) tetiklenme anında üretilir; toplarken onu da dahil et.
        val opening = m.onDetection(0, Scenario.FALL, Confidence.HIGH)
        val announcements = (opening + m.runUntil(30))
            .filterIsInstance<Action.AnnounceCountdown>()
            .map { it.secondsRemaining }

        assertTrue("son 10 saniye tek tek duyurulmali",
            (1..10).all { it in announcements })
        assertTrue("20 ve 30 da duyurulmali", 20 in announcements && 30 in announcements)
    }

    @Test
    fun `konum yoksa zincir yine de calisir`() {
        val m = EscalationMachine(contacts) { ctx.copy(fix = null) }
        m.onDetection(0, Scenario.FALL, Confidence.HIGH)
        val actions = m.runUntil(200)

        val sms = actions.filterIsInstance<Action.SendContactSms>()
        assertTrue("konum yokken de SMS gitmeli", sms.isNotEmpty())
        assertTrue(sms.first().text.contains("KONUM ALINAMADI"))
    }

    @Test
    fun `denetim goruntusu tum kritik alanlari icerir`() {
        val m = machine()
        m.onDetection(0, Scenario.FALL, Confidence.HIGH)
        m.runUntil(200)
        val snap = m.snapshot(200_000)

        assertEquals(true, snap["112_kullanildi"])
        assertEquals("EMERGENCY", snap["durum"])
        assertEquals(200, snap["gecen_saniye"])
    }
}

/** Kazaya özgü, güvene bağlı zamanlama testleri. */
class CrashEscalationTest {

    private val contacts = listOf(Contact("Anne", "+905321111111"))
    private val ctx = AlertContext(
        userName = "Bora",
        scenario = Scenario.VEHICLE_CRASH,
        fix = Fix(40.9735, 28.8189, 12f),
        timeText = "14:32",
        callbackNumber = "+905321234567",
    )

    private fun run(confidence: Confidence, seconds: Int): List<Action> {
        val m = EscalationMachine(contacts) { ctx }
        val all = mutableListOf<Action>()
        all += m.onDetection(0, Scenario.VEHICLE_CRASH, confidence)
        var t = 0L
        while (t <= seconds * 1000L) { all += m.advanceTo(t); t += 1000L }
        return all
    }

    @Test
    fun `siddetli kazada geri sayim on saniyedir`() {
        assertTrue("t+8s'de henuz haber verilmemeli",
            run(Confidence.HIGH, 8).none { it is Action.SendContactSms })
        assertTrue("t+12s'de haber verilmeli",
            run(Confidence.HIGH, 12).any { it is Action.SendContactSms })
    }

    @Test
    fun `orta guvenli kazada kullaniciya bir dakika verilir`() {
        assertTrue("t+45s'de henuz haber verilmemeli",
            run(Confidence.MEDIUM, 45).none { it is Action.SendContactSms })
        assertTrue("t+65s'de haber verilmeli",
            run(Confidence.MEDIUM, 65).any { it is Action.SendContactSms })
    }

    @Test
    fun `orta guvenli kazada 112 ye otomatik mesaj gitmez`() {
        assertTrue("guven HIGH degilse 112'ye otomatik mesaj yok",
            run(Confidence.MEDIUM, 400).filterIsInstance<Action.Send112Sms>().isEmpty())
    }

    @Test
    fun `dusme plani kazadan etkilenmez`() {
        val m = EscalationMachine(contacts) { ctx.copy(scenario = Scenario.FALL) }
        m.onDetection(0, Scenario.FALL, Confidence.HIGH)
        var t = 0L
        val all = mutableListOf<Action>()
        while (t <= 25_000L) { all += m.advanceTo(t); t += 1000L }
        assertTrue("dusmede geri sayim hala 30 s",
            all.none { it is Action.SendContactSms })
    }
}
