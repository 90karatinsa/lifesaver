package com.lifesaver.alert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class CoordinatesTest {

    private val istanbul = Fix(latitude = 40.9735, longitude = 28.8189, accuracyMeters = 12f)

    /**
     * Bu testin varlık sebebi: Türkçe yerelde varsayılan biçimleyici
     * "40,973500" üretir ve "enlem,boylam" dizgisi ayrıştırılamaz hale gelir.
     * Bu, bu katmandaki en yüksek olasılıklı sessiz veri bozulması hatasıdır.
     */
    @Test
    fun `turkce yerelde bile nokta ondalik ayirici kullanilir`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val decimal = Coordinates.decimal(istanbul)
            assertEquals("40.973500,28.818900", decimal)
            assertFalse("koordinatta virgul ondalik ayirici olmamali",
                decimal.substringBefore(',').contains(','))
            assertTrue(Coordinates.mapsUrl(istanbul).endsWith("40.973500,28.818900"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `denizcilik bicimi derece ve ondalik dakika uretir`() {
        // 40.9735 derece = 40 derece + 0.9735*60 = 58.410 dakika
        val ddm = Coordinates.degreesDecimalMinutes(istanbul)
        assertEquals("40° 58.410' N  028° 49.134' E", ddm)
    }

    @Test
    fun `guney ve bati yarim kureleri dogru isaretlenir`() {
        val f = Fix(-33.8688, -151.2093, 5f)
        val ddm = Coordinates.degreesDecimalMinutes(f)
        assertTrue("guney yarim kure S olmali", ddm.contains(" S"))
        assertTrue("bati yarim kure W olmali", ddm.contains(" W"))
    }

    @Test
    fun `dakika yuvarlanmasi dereceye tasar`() {
        // 40.99999917 derece -> 59.99995 dakika -> 41 derece 00.000 dakika
        val f = Fix(40.99999917, 28.0, 5f)
        val ddm = Coordinates.degreesDecimalMinutes(f)
        assertTrue("60 dakika yazilmamali: $ddm", !ddm.contains("60.000"))
        assertTrue("dereceye tasinmali: $ddm", ddm.startsWith("41° 00.000'"))
    }

    @Test
    fun `dogruluk yaricapi insan diliyle yazilir`() {
        assertEquals("+-12m", Coordinates.accuracyText(istanbul))
        assertEquals("+-3km", Coordinates.accuracyText(Fix(0.0, 0.0, 3500f)))
        assertEquals("", Coordinates.accuracyText(Fix(0.0, 0.0, 0f)))
    }

    @Test
    fun `harita baglantisi geo uri degil https olmali`() {
        // geo: URI cogu SMS istemcisinde tiklanabilir degildir.
        assertTrue(Coordinates.mapsUrl(istanbul).startsWith("https://"))
    }
}

class Gsm7Test {

    @Test
    fun `turkce kucuk i noktasiz ve s cedilli GSM7 disindadir`() {
        // AOSP varsayilan tablosu Ç, ö, Ö, ü, Ü icerir ama bunlari icermez.
        assertFalse(Gsm7.isRepresentable('ı'))
        assertFalse(Gsm7.isRepresentable('ş'))
        assertFalse(Gsm7.isRepresentable('ğ'))
        assertFalse(Gsm7.isRepresentable('İ'))
        assertTrue(Gsm7.isRepresentable('Ç'))
        assertTrue(Gsm7.isRepresentable('ö'))
        assertTrue(Gsm7.isRepresentable('ü'))
    }

    @Test
    fun `tek turkce karakter mesaji UCS2 ye dusurur`() {
        val ascii = "Yardim lazim konum 40.973500,28.818900"
        val turkish = "Yardım lazım konum 40.973500,28.818900"

        assertEquals(SmsEncoding.GSM7, Sms.budget(ascii).encoding)
        assertEquals(SmsEncoding.UCS2, Sms.budget(turkish).encoding)

        // Ve bu, kapasiteyi 160'tan 70'e indirir.
        assertEquals(160, Sms.budget(ascii).singlePartLimit)
        assertEquals(70, Sms.budget(turkish).singlePartLimit)
    }

    @Test
    fun `genisletme tablosu karakterleri iki septet sayilir`() {
        assertEquals(1, Gsm7.septetLength("A"))
        assertEquals(2, Gsm7.septetLength("["))   // genisletme tablosunda
        assertEquals(3, Gsm7.septetLength("A["))
    }

    @Test
    fun `parca sayisi dogru hesaplanir`() {
        assertEquals(1, Sms.budget("A".repeat(160)).parts)
        assertEquals(2, Sms.budget("A".repeat(161)).parts)
        assertEquals(2, Sms.budget("A".repeat(306)).parts)   // 2 x 153
        assertEquals(3, Sms.budget("A".repeat(307)).parts)
    }

    @Test
    fun `sorunlu karakterler teshis icin listelenir`() {
        assertEquals(setOf('ı', 'ş'), Gsm7.offendingChars("Yardım lazımsş".take(14)).intersect(setOf('ı','ş')))
    }
}

class TurkishAsciiTest {

    @Test
    fun `tum turkce karakterler cevrilir`() {
        assertEquals("cCgGiIoOsSuU", TurkishAscii.convert("çÇğĞıİöÖşŞüÜ"))
    }

    @Test
    fun `cevrilen metin GSM7 uyumlu olur`() {
        val turkish = "Bora Yunusoğlu düştü, bilinci kapalı olabilir"
        assertFalse(Gsm7.isRepresentable(turkish))
        assertTrue(Gsm7.isRepresentable(TurkishAscii.convert(turkish)))
    }

    @Test
    fun `ascii metin degismeden gecer`() {
        assertEquals("Bora 40.97", TurkishAscii.convert("Bora 40.97"))
    }
}

class AlertTextsTest {

    private val ctx = AlertContext(
        userName = "Bora Yunusoğlu",
        scenario = Scenario.FALL,
        fix = Fix(40.9735, 28.8189, 12f),
        timeText = "14:32",
        callbackNumber = "+905321234567",
    )

    @Test
    fun `yakin SMS i tek parcaya sigar ve GSM7 kalir`() {
        val text = AlertTexts.contactSms(ctx)
        val budget = Sms.budget(text)
        println("YAKIN SMS (${budget.units} birim, ${budget.parts} parca): $text")

        assertEquals("turkce karakter kalmamali: ${Gsm7.offendingChars(text)}",
            SmsEncoding.GSM7, budget.encoding)
        assertTrue("tek parcaya sigmali, ${budget.units} birim", budget.isSinglePart)
    }

    @Test
    fun `112 SMS inde koordinat ve harita ilk parcada olmali`() {
        val text = AlertTexts.emergencySms112(ctx)
        val budget = Sms.budget(text)
        println("112 SMS (${budget.units} birim, ${budget.parts} parca): $text")

        assertEquals(SmsEncoding.GSM7, budget.encoding)

        val firstSegment = text.take(budget.firstSegmentCapacity)
        assertTrue("koordinat ilk parcada olmali", firstSegment.contains("40.973500,28.818900"))
        assertTrue("harita baglantisi ilk parcada olmali", firstSegment.contains("maps.google.com"))
    }

    @Test
    fun `112 SMS i otomatik oldugunu ve geri arama numarasini bildirir`() {
        // Kabahatler Kanunu m.42-A baglaminda hukuki zorunluluk.
        val text = AlertTexts.emergencySms112(ctx)
        assertTrue("makine kaynakli oldugu belirtilmeli", text.contains("OTOMATIK"))
        assertTrue("geri arama numarasi verilmeli", text.contains("+905321234567"))
    }

    @Test
    fun `konum alinamadiginda mesaj yine de gonderilebilir`() {
        val noFix = ctx.copy(fix = null)
        val contact = AlertTexts.contactSms(noFix)
        val emergency = AlertTexts.emergencySms112(noFix)

        assertTrue(contact.contains("KONUM ALINAMADI"))
        assertTrue(emergency.contains("Konum alinamadi"))
        // Konum yoksa bile mesaj kurulabilmeli — sessizce cokmemeli.
        assertTrue(Sms.budget(contact).isSinglePart)
    }

    @Test
    fun `koordinat hane hane okunur`() {
        val spoken = AlertTexts.spellCoordinates(ctx.fix!!)
        assertTrue("enlem etiketi olmali", spoken.startsWith("enlem"))
        assertTrue("nokta virgul olarak okunmali", spoken.contains("virgül"))
        assertTrue("haneler ayrilmali", spoken.contains("4 0"))
    }

    @Test
    fun `CPR yonergesi ERC sayilarini birebir icerir`() {
        val text = AlertTexts.ttsCprCoaching()
        assertTrue(text.contains("yüz ile yüz yirmi"))
        assertTrue(text.contains("beş santimetre"))
        assertTrue(text.contains("altı santimetre"))
    }

    @Test
    fun `tum senaryolar icin mesaj kurulabilir`() {
        for (s in Scenario.entries) {
            val text = AlertTexts.contactSms(ctx.copy(scenario = s))
            val budget = Sms.budget(text)
            assertEquals("$s icin GSM7 disina cikildi: ${Gsm7.offendingChars(text)}",
                SmsEncoding.GSM7, budget.encoding)
            assertTrue("$s icin mesaj cok uzun: ${budget.units} birim", budget.units <= 300)
        }
    }
}
