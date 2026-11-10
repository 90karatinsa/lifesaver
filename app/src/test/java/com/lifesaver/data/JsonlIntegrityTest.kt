package com.lifesaver.data

import com.lifesaver.learn.CancelReason
import com.lifesaver.learn.CancellationRecord
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JSONL bütünlüğü.
 *
 * Bu test bir cihaz denemesinde bulunan gerçek bir hatadan doğdu: kayıt
 * biçimlendirici prettyPrint ile kuruluydu, dolayısıyla tek satır olması
 * gereken kayıt çok satıra yayıldı ve dosya bir daha okunamaz hale geldi.
 * Sonuç sessizdi: hata yoktu, kayıtlar yalnızca yok oldu — öğrenme verisi
 * ve asılsız ihbara karşı savunma delili birlikte kayboldu.
 */
class JsonlIntegrityTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    private val record = CancellationRecord(
        timestampMs = 1_700_000_000_000L,
        mode = "MOTORCYCLE",
        scenario = "MOTORCYCLE_CRASH",
        score = 62,
        confidence = "MEDIUM",
        reason = CancelReason.FALSE_ALARM,
        features = mapOf("peakG" to 2.9f),
        evidenceFired = listOf("darbe", "hareketsizlik"),
        signalWindow = List(30) { it * 0.1f },
    )

    @Test
    fun `kayit tek satira sigar`() {
        val line = json.encodeToString(CancellationRecord.serializer(), record).replace("\n", " ")
        assertEquals("kayit tek satir olmali", 1, line.lines().size)
    }

    @Test
    fun `tek satira indirgenmis kayit geri okunabilir`() {
        val line = json.encodeToString(CancellationRecord.serializer(), record).replace("\n", " ")
        val back = json.decodeFromString<CancellationRecord>(line)
        assertEquals(record.mode, back.mode)
        assertEquals(record.reason, back.reason)
        assertEquals(record.signalWindow.size, back.signalWindow.size)
        assertEquals(2.9f, back.features["peakG"]!!, 0.001f)
    }

    @Test
    fun `sebep guncellemesi satir sayisini artirmaz`() {
        // Dosya yeniden yazılırken güncellenen kayıt da tek satır kalmalıdır.
        val updated = record.copy(reason = CancelReason.REAL_BUT_FINE)
        val line = json.encodeToString(CancellationRecord.serializer(), updated).replace("\n", " ")
        assertEquals(1, line.lines().size)
        assertEquals(
            CancelReason.REAL_BUT_FINE,
            json.decodeFromString<CancellationRecord>(line).reason,
        )
    }
}
