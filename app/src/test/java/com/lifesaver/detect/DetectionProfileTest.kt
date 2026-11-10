package com.lifesaver.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kip profillerinin testi.
 *
 * Bu testlerin çoğu bir SINIRI korur, bir davranışı değil. Kip sistemi altıdan
 * üçe indirildiğinde ve otomasyon eklendiğinde tek yönlü kural konuldu:
 *
 *   Otomatik hiçbir karar duyarlılığı DÜŞÜREMEZ; yalnızca artırabilir ya da
 *   kapsam ekleyebilir.
 *
 * Sebebi asimetri: fazla duyarlı kalmanın bedeli kullanıcının iptal edebildiği
 * bir yanlış alarmdır; az duyarlı kalmanın bedeli hiç çalmayan bir alarmdır ve
 * bunun arka duvarı yoktur. Aşağıdaki testler o kuralın kodda gerçekten
 * geçerli olduğunu şart koşar.
 */
class DetectionProfileTest {

    // ---- Kip sayısı ve göç -------------------------------------------------

    @Test
    fun `uc kip vardir`() {
        assertEquals(3, ActivityMode.entries.size)
    }

    @Test
    fun `eski alti kipli adlar dogru aileye tasinir`() {
        // Göç sessiz olmamalı: eski adı tanımayıp varsayılana düşmek,
        // araçtaki bir kullanıcıyı güncelleme sonrası farkında olmadan
        // yaya profiline taşırdı.
        assertEquals(ActivityMode.DAILY, ActivityMode.fromName("PEDESTRIAN"))
        assertEquals(ActivityMode.DAILY, ActivityMode.fromName("RUNNING"))
        assertEquals(ActivityMode.VEHICLE, ActivityMode.fromName("CYCLING"))
        assertEquals(ActivityMode.VEHICLE, ActivityMode.fromName("CAR"))
        assertEquals(ActivityMode.VEHICLE, ActivityMode.fromName("MOTORCYCLE"))
        assertEquals(ActivityMode.BOAT, ActivityMode.fromName("BOAT"))
    }

    @Test
    fun `bozuk kip adi en duyarli kipe duser`() {
        assertEquals(ActivityMode.DAILY, ActivityMode.fromName(null))
        assertEquals(ActivityMode.DAILY, ActivityMode.fromName(""))
        assertEquals(ActivityMode.DAILY, ActivityMode.fromName("CAR_V2"))
    }

    // ---- Birleştirme kuralı: daha duyarlı taraf kazanır --------------------

    @Test
    fun `gunluk kip kosunun degil yayanin esigini kullanir`() {
        // Koşunun eski ayarı (4.00 g / 4 puan) yalnızca duyarlılığı
        // düşürüyordu ve kullanıcı koşudan sonra geri almayı unutursa akşama
        // kadar 39.2 m/s² kapısıyla dolaşıyordu.
        val daily = DetectionProfile.forMode(ActivityMode.DAILY)
        assertEquals(2.60f, daily.fallConfig.impactG, 0.001f)
        assertEquals(3, daily.fallConfig.minScore)
    }

    @Test
    fun `arac kipi arabanin degil bisikletin esigini kullanir`() {
        // Arabanın 4.00 g'si bir güvenlik değil KONFOR ayarıydı: yol
        // tümsekleri 2-3 g üretir. Birleştirmede daha duyarlı taraf alınır.
        val vehicle = DetectionProfile.forMode(ActivityMode.VEHICLE)
        assertEquals(3.00f, vehicle.fallConfig.impactG, 0.001f)
        assertEquals(3, vehicle.fallConfig.minScore)
        assertTrue(
            "arac esigi eski araba esiginden (4.00 g) DUSUK olmali",
            vehicle.fallConfig.impactG < 4.0f,
        )
    }

    @Test
    fun `hicbir kip gunluk kipten iki kat duyarsiz olamaz`() {
        // Üst sınır. Kipler arası fark bir konfor ayarıdır; bir kipin
        // diğerine göre sessizce sağırlaşmasına izin verilmez.
        val base = DetectionProfile.forMode(ActivityMode.DAILY).fallConfig.impactG
        ActivityMode.entries.forEach { mode ->
            val p = DetectionProfile.forMode(mode)
            assertTrue(
                "${mode.name}: darbe esigi tabanin 1.3 katini asmamali " +
                    "(${p.fallConfig.impactG} vs $base)",
                p.fallConfig.impactG <= base * 1.3f,
            )
            assertTrue(
                "${mode.name}: puan esigi tabandan yuksek olmamali",
                p.fallConfig.minScore <= DetectionProfile.forMode(ActivityMode.DAILY)
                    .fallConfig.minScore,
            )
        }
    }

    // ---- En öldürücü kolun kaldırılması ------------------------------------

    @Test
    fun `kaza algilama HER kipte kuruludur`() {
        // Eskiden kipe bağlıydı: yaya kipinde kalmış bir motosikletli için
        // 145 puanlık kaza kanıt havuzunun TAMAMI devre dışıydı. Dedektörü
        // kurmak yalnızca kapsam ekler, hiçbir eşiği gevşetmez.
        ActivityMode.entries.forEach { mode ->
            val p = DetectionProfile.forMode(mode)
            assertTrue("${mode.name}: kaza dedektoru kurulmali", p.crashEnabled)
            assertTrue(
                "${mode.name}: kaza kipi OFF olmamali",
                p.crashVehicleMode != VehicleMode.OFF,
            )
        }
    }

    @Test
    fun `surekli konum yalnizca arac kipinde acilir`() {
        // Kipin gerçek pil bedeli konum akışıdır, dedektörün varlığı değil.
        assertTrue(DetectionProfile.forMode(ActivityMode.VEHICLE).continuousLocation)
        assertFalse(DetectionProfile.forMode(ActivityMode.DAILY).continuousLocation)
        assertFalse(DetectionProfile.forMode(ActivityMode.BOAT).continuousLocation)
    }

    // ---- Birleşik kaza yapılandırması --------------------------------------

    @Test
    fun `silahlanma hizi birlesimin EN DUSUGUDUR`() {
        // 15 km/s bisikletten gelir. Silahlanma yalnızca dedektör eklediği
        // için yüksek eşik seçmek bisikletliyi ve yavaş motosikletliyi
        // kapsam dışı bırakırdı.
        ActivityMode.entries.forEach { mode ->
            val c = DetectionProfile.forMode(mode).crashConfig
            assertEquals("${mode.name}", 4.17f, c.armSpeedMs, 0.01f)
        }
    }

    @Test
    fun `tepe ivme kapisi birlesimin EN DUSUGUDUR`() {
        // Arabaya motosiklet kapısını (25) uygulamak fazladan aday üretir —
        // bu iptal edilebilir. Motosiklete araba kapısını (40) uygulamak
        // gerçek bir düşük-taraf düşüşünü görünmez yapar ve bunun arka
        // duvarı yoktur.
        ActivityMode.entries.forEach { mode ->
            val p = DetectionProfile.forMode(mode)
            val gate = p.crashConfig.peakAccelFor(p.crashVehicleMode)
            assertEquals("${mode.name}", 25f, gate, 0.01f)
        }
    }

    @Test
    fun `iki tekerlek kanitlari her kipte aranir`() {
        // Araba/motosiklet ayrımı platformda da literatürde de güvenilir
        // yapılamıyor; yapıyormuş gibi davranmak yerine iki tekerlek
        // kanıtları her araçta hesaplanır.
        ActivityMode.entries.forEach { mode ->
            assertEquals(
                "${mode.name}: iki tekerlek kanitlari acik olmali",
                VehicleMode.MOTORCYCLE,
                DetectionProfile.forMode(mode).crashVehicleMode,
            )
        }
    }

    // ---- Tekne: eksikliğini söylemek zorunda -------------------------------

    @Test
    fun `tekne kipi eksikligini acikca bildirir`() {
        val boat = DetectionProfile.forMode(ActivityMode.BOAT)
        assertTrue("Tekne kipi eksiklerini listelemeli", boat.gaps.isNotEmpty())
        assertTrue(
            "Kip aciklamasi eksikligi soylemeli",
            ActivityMode.BOAT.description.contains("YOK"),
        )
    }

    @Test
    fun `tekne kipi teknenin sallanmasina karsi daha toleransli`() {
        // Güvertede dalga hareketi hiç durmaz: günlük kipin hareketsizlik
        // ölçütü teknede asla sağlanmaz ve gerçek düşme MOVED_AFTER_IMPACT
        // ile elenirdi.
        val boat = DetectionProfile.forMode(ActivityMode.BOAT)
        val daily = DetectionProfile.forMode(ActivityMode.DAILY)
        assertTrue(
            boat.fallConfig.stillnessAccelRangeG > daily.fallConfig.stillnessAccelRangeG,
        )
    }

    // ---- Genel sağlık ------------------------------------------------------

    @Test
    fun `her kipin dusme esikleri makul sinirlar icinde`() {
        ActivityMode.entries.forEach { mode ->
            val p = DetectionProfile.forMode(mode)
            assertTrue("${mode.name}: dusme algilama acik olmali", p.fallEnabled)
            assertTrue(
                "${mode.name}: darbe esigi 2 g altina inmemeli (gurultu)",
                p.fallConfig.impactG >= 2.0f,
            )
            assertTrue(
                "${mode.name}: darbe esigi 4 g ustune cikmamali",
                p.fallConfig.impactG <= 4.0f,
            )
            assertTrue("${mode.name}: en az 2 kanit istenmeli", p.fallConfig.minScore >= 2)
        }
    }

    @Test
    fun `her kipin pil notu ve aciklamasi doludur`() {
        ActivityMode.entries.forEach { mode ->
            val p = DetectionProfile.forMode(mode)
            assertTrue("${mode.name}: Turkce ad bos", mode.turkish.isNotBlank())
            assertTrue("${mode.name}: aciklama bos", mode.description.isNotBlank())
            assertTrue("${mode.name}: pil notu bos", p.batteryNote.isNotBlank())
        }
    }
}
