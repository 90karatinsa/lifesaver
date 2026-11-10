package com.lifesaver.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import com.lifesaver.detect.ActivityMode
import com.lifesaver.learn.CancelReason
import com.lifesaver.learn.CancellationRecord
import com.lifesaver.learn.Tuning
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class UserProfile(
    val name: String = "",
    /** Kendi numarası. 112 mesajında geri arama numarası olarak zorunludur. */
    val phone: String = "",
    val bloodType: String = "",
    val allergies: String = "",
    val conditions: String = "",
    val medications: String = "",
    /** "B Blok, 3. kat, daire 7, kapı kodu 1234" gibi bulunma tarifi. */
    val locationNote: String = "",
) {
    val isComplete: Boolean get() = name.isNotBlank() && phone.isNotBlank()
}

@Serializable
data class EmergencyContact(val name: String, val phone: String)

@Serializable
data class Config(
    val armed: Boolean = false,
    val fallDetection: Boolean = true,
    val crashDetection: Boolean = true,
    val marineMode: Boolean = false,
    /**
     * TATBİKAT MODU — varsayılan olarak AÇIK.
     *
     * Açıkken zincirin tamamı çalışır, alarm çalar, ekranlar açılır ve
     * mesajlar OLUŞTURULUR; ama hiçbir SMS gönderilmez, hiçbir arama yapılmaz.
     * Kullanıcı en az bir başarılı tatbikat yapıp bunu bilinçli olarak
     * kapatana kadar gerçek bir mesaj dışarı çıkmaz.
     *
     * Sebebi: Türkiye'de asılsız ihbarın ekiplerce tespiti idari para
     * cezasıdır ve iyi niyet istisnası yoktur.
     */
    val drillMode: Boolean = true,
    /** Güven HIGH olduğunda 112'ye otomatik SMS gönderilsin mi? */
    val autoSms112: Boolean = true,
    /** Kurulumda ölçülen cihaza özgü sensör gürültü tabanı (g). */
    val noiseFloorG: Float = 0.001f,
    /**
     * Etkinlik kipi: PEDESTRIAN, RUNNING, CYCLING, CAR, MOTORCYCLE, BOAT.
     *
     * Yaya olarak başlar. Araç kipleri 1 Hz konum + silahlıyken 200 Hz
     * ivmeölçer demektir; bu pil maliyetini kullanıcı bilerek kabul etmelidir.
     */
    val activityMode: String = "DAILY",
    /**
     * Öğrenilmiş eşik ayarlamaları.
     * Boş liste = hiçbir şey öğrenilmedi, literatür değerleri geçerli.
     */
    val tunings: List<Tuning> = emptyList(),
    /** Öğrenme tamamen kapatılabilir. */
    val learningEnabled: Boolean = true,
    /**
     * Etkinlik tanımadan EN SON ne zaman sonuç alındı (0 = hiç).
     *
     * Kalıcı olması şart: sınıflandırıcı sessizce ölebildiği için "hiç
     * çalışmadı" ile "servis yeni başladı" ayrımı yalnızca diske yazarak
     * yapılabilir.
     */
    val activityRecognitionSeenAtMs: Long = 0L,
    /**
     * ESKİ ALAN — yalnızca göç için okunur, artık yazılmaz.
     *
     * Önceki sürümde kip yalnızca OFF/CAR/MOTORCYCLE idi. Bu alan
     * silinseydi ignoreUnknownKeys yüzünden sessizce yok sayılır ve
     * arabadaki bir kullanıcı güncelleme sonrası farkında olmadan yaya
     * kipine düşerdi: gerçek çarpışma "sıradan sarsıntı" sayılırdı.
     */
    val vehicleMode: String? = null,
)

@Serializable
data class AppState(
    val profile: UserProfile = UserProfile(),
    val contacts: List<EmergencyContact> = emptyList(),
    val config: Config = Config(),
) {
    /** Uygulama gerçekten koruyabilir durumda mı? */
    val isUsable: Boolean get() = profile.isComplete && contacts.isNotEmpty()
}

/**
 * Uygulama durumunun kalıcı deposu.
 *
 * Düz JSON dosyası kullanılır. Sebebi bilinçlidir: acil bir olayda okunacak
 * veri, veritabanı şeması geçişi ya da kod üretimi gibi kırılabilecek hiçbir
 * katmanın arkasında olmamalıdır. Dosya bozulursa varsayılana dönülür ve
 * uygulama yine de açılır.
 */
class Storage(context: Context) {

    private companion object {
        const val TAG = "LifeSaver/Storage"
        const val MAX_CANCELLATIONS = 200
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val stateFile = File(context.filesDir, "lifesaver-state.json")
    private val incidentFile = File(context.filesDir, "incidents.jsonl")
    private val cancellationFile = File(context.filesDir, "cancellations.jsonl")

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private fun load(): AppState = runCatching {
        if (!stateFile.exists()) return@runCatching AppState()
        migrate(json.decodeFromString<AppState>(stateFile.readText()))
    }.onFailure {
        Log.e(TAG, "Durum okunamadi, varsayilana donuluyor", it)
    }.getOrDefault(AppState())

    /**
     * Eski iki durumlu sürüş kipini yeni etkinlik kipine taşır.
     * Kullanıcının seçtiği kip güncellemede sessizce kaybolmamalıdır.
     */
    private fun migrate(state: AppState): AppState {
        // 1) Kip adını normalleştir. ActivityMode.fromName eski altı kipli
        //    adları da tanır; burada DİSKE de yazılacak hale getiriyoruz ki
        //    eski ad sonsuza kadar dolaşmasın.
        val fromLegacyVehicle = when (state.config.vehicleMode) {
            "CAR", "MOTORCYCLE" -> ActivityMode.VEHICLE.name
            else -> null
        }
        val normalised = fromLegacyVehicle
            ?: ActivityMode.fromName(state.config.activityMode).name

        // 2) Öğrenilmiş ayarlamaların kip adlarını da taşı, yoksa eşleşmeyip
        //    sessizce uygulanmaz hale gelirler.
        val tunings = state.config.tunings.map {
            it.copy(mode = ActivityMode.fromName(it.mode).name)
        }

        val changed = normalised != state.config.activityMode ||
            state.config.vehicleMode != null ||
            tunings != state.config.tunings
        if (!changed) return state

        Log.i(TAG, "Kip tasindi: ${state.config.activityMode}/" +
            "${state.config.vehicleMode} -> $normalised")
        return state.copy(
            config = state.config.copy(
                activityMode = normalised,
                vehicleMode = null,
                tunings = tunings,
            )
        )
    }

    suspend fun update(transform: (AppState) -> AppState) = withContext(Dispatchers.IO) {
        val next = transform(_state.value)
        _state.value = next
        runCatching {
            // Atomik yazma: yarım yazılmış bir durum dosyası uygulamayı
            // bir dahaki açılışta sıfırlar.
            val tmp = File(stateFile.parentFile, stateFile.name + ".tmp")
            tmp.writeText(json.encodeToString(next))
            tmp.renameTo(stateFile)
        }.onFailure { Log.e(TAG, "Durum yazilamadi", it) }
    }

    // ---- Olay günlüğü ---------------------------------------------------

    /**
     * Adli günlük.
     *
     * Bu yalnızca hata ayıklama değildir: valilik asılsız ihbar dosyası açarsa
     * kullanıcının tek savunması budur. Her tetikleme, her puan bileşeni,
     * her iptal ve gönderilen her metin saklanır.
     */
    suspend fun appendIncident(entry: IncidentEntry) = withContext(Dispatchers.IO) {
        runCatching {
            incidentFile.appendText(json.encodeToString(entry).replace("\n", " ") + "\n")
        }.onFailure { Log.e(TAG, "Olay kaydi yazilamadi", it) }
    }

    suspend fun readIncidents(limit: Int = 100): List<IncidentEntry> = withContext(Dispatchers.IO) {
        runCatching {
            if (!incidentFile.exists()) return@runCatching emptyList()
            incidentFile.readLines()
                .takeLast(limit)
                .mapNotNull { line ->
                    runCatching { json.decodeFromString<IncidentEntry>(line) }.getOrNull()
                }
                .reversed()
        }.getOrDefault(emptyList())
    }

    // ---- İptal kayıtları (öğrenmenin ham verisi) ------------------------

    suspend fun appendCancellation(record: CancellationRecord) = withContext(Dispatchers.IO) {
        runCatching {
            cancellationFile.appendText(
                json.encodeToString(record).replace("\n", " ") + "\n"
            )
        }.onFailure { Log.e(TAG, "Iptal kaydi yazilamadi", it) }
        Unit
    }

    suspend fun readCancellations(limit: Int = 200): List<CancellationRecord> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!cancellationFile.exists()) return@runCatching emptyList()
                cancellationFile.readLines().takeLast(limit).mapNotNull { line ->
                    runCatching { json.decodeFromString<CancellationRecord>(line) }.getOrNull()
                }
            }.getOrDefault(emptyList())
        }

    /**
     * Son iptal kaydının sebebini günceller.
     *
     * Alarm iptal edildiği anda kayıt SEBEPSİZ yazılır (UNSPECIFIED), çünkü
     * siren derhal susmalıdır; sebep sorusu ondan sonra gelir. Kullanıcı
     * sebebi seçerse kayıt burada tamamlanır. Hiç seçmezse kayıt
     * UNSPECIFIED kalır ve öğretmez — güvenli varsayılan budur.
     */
    suspend fun updateLastCancellationReason(reason: CancelReason): CancellationRecord? =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!cancellationFile.exists()) return@runCatching null
                val lines = cancellationFile.readLines().filter { it.isNotBlank() }
                if (lines.isEmpty()) return@runCatching null
                val last = json.decodeFromString<CancellationRecord>(lines.last())
                val updated = last.copy(reason = reason)
                // Dosya sınırlıdır (son 200 kayıt); tamamı yeniden yazılır.
                // prettyPrint AÇIK: satır sonları temizlenmezse JSONL bozulur
                // ve o kayıt bir daha okunamaz. Ekleme yolunda da aynı
                // temizlik yapılır.
                val kept = (lines.dropLast(1) + json.encodeToString(updated).replace("\n", " "))
                    .takeLast(MAX_CANCELLATIONS)
                cancellationFile.writeText(kept.joinToString("\n") + "\n")
                updated
            }.onFailure { Log.e(TAG, "Iptal sebebi yazilamadi", it) }.getOrNull()
        }

    suspend fun clearCancellations() = withContext(Dispatchers.IO) {
        runCatching { cancellationFile.delete() }
        Unit
    }

    suspend fun clearIncidents() = withContext(Dispatchers.IO) {
        runCatching { incidentFile.delete() }
        Unit
    }
}

@Serializable
data class IncidentEntry(
    val timestampMs: Long,
    val kind: String,
    val scenario: String = "",
    val confidence: String = "",
    val score: Int = 0,
    val evidence: List<String> = emptyList(),
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Float? = null,
    val note: String = "",
    val drill: Boolean = false,
) {
    companion object {
        const val KIND_DETECTED = "algilandi"
        const val KIND_REJECTED = "elendi"
        const val KIND_ACTION = "eylem"
        const val KIND_CANCELLED = "iptal"
        const val KIND_DRILL = "tatbikat"
        const val KIND_SERVICE = "servis"
    }
}
