package com.lifesaver.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Eski kayıt biçiminin okunabilirliği.
 *
 * ignoreUnknownKeys açık olduğu için kaldırılan bir alan HATA VERMEZ,
 * sessizce yok sayılır. Bu testin varlık sebebi tam olarak budur: arabadaki
 * bir kullanıcının kipi güncelleme sırasında sessizce yayaya düşerse, gerçek
 * bir çarpışma "sıradan sarsıntı" sayılır ve kimse bunu fark etmez.
 */
class ConfigMigrationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `eski surus kipi alani hala okunabilir`() {
        val legacy = """{"config":{"armed":false,"vehicleMode":"MOTORCYCLE"}}"""
        val state = json.decodeFromString<AppState>(legacy)
        assertEquals("MOTORCYCLE", state.config.vehicleMode)
        // Göç uygulanmadan önce yeni alan varsayılanda kalır.
        assertEquals("DAILY", state.config.activityMode)
    }

    @Test
    fun `yeni alan varsa eski alan bos gelir`() {
        val modern = """{"config":{"activityMode":"BOAT"}}"""
        val state = json.decodeFromString<AppState>(modern)
        assertEquals("BOAT", state.config.activityMode)
        assertNull(state.config.vehicleMode)
    }

    @Test
    fun `bilinmeyen alanlar cokme yapmaz`() {
        val future = """{"config":{"activityMode":"VEHICLE","gelecektekiAlan":42}}"""
        val state = json.decodeFromString<AppState>(future)
        assertEquals("VEHICLE", state.config.activityMode)
    }

    @Test
    fun `ogrenilen ayarlamalar diske yazilip geri okunabilir`() {
        val tuning = com.lifesaver.learn.Tuning(
            mode = "DAILY",
            parameter = com.lifesaver.learn.TunableParameter.FALL_IMPACT_G,
            baseline = 2.6f, current = 2.9f, ceiling = 3.38f,
            supportingCancellations = 3, updatedAtMs = 1_700_000_000_000L,
            note = "3 kez yanlış alarm iptal edildi.",
        )
        val state = AppState(config = Config(tunings = listOf(tuning)))
        val round = json.decodeFromString<AppState>(json.encodeToString(AppState.serializer(), state))
        assertEquals(1, round.config.tunings.size)
        assertEquals(2.9f, round.config.tunings.first().current, 0.001f)
        // Gerekçe metni de kalıcı olmalı: kullanıcı neyin neden değiştiğini
        // aylar sonra da okuyabilmelidir.
        assertEquals(tuning.note, round.config.tunings.first().note)
    }
}
