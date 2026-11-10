package com.lifesaver.actuator

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * Türkçe sesli anons ve TYD (temel yaşam desteği) metronomu.
 *
 * Konuşma alarm/erişilebilirlik kullanımıyla oynatılır ki sirenin üzerinden
 * duyulabilsin ve sessiz modda kaybolmasın.
 *
 * ÖNEMLİ SINIR: Bu ses bir telefon görüşmesinin karşı tarafına yalnızca
 * AKUSTİK olarak (hoparlörden mikrofona) ulaşır. Android'de üçüncü taraf bir
 * uygulamanın çağrı yukarı-bağına ses enjekte etmesi mümkün değildir ve
 * cihazın yankı bastırıcısı bu sese karşı çalışır. Bu yüzden operatöre
 * yönelik metin kısa, yavaş ve tekrarlanabilir tutulur.
 */
class Voice(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private companion object {
        const val TAG = "LifeSaver/Voice"
        const val UTTERANCE_ID = "lifesaver"
        /** ERC 2021: dakikada 100-120 bası. Ortası seçildi. */
        const val CPR_BPM = 110
        /** Bekleyen anons kuyruğunun üst sınırı. */
        const val MAX_PENDING = 4
    }

    private var tts: TextToSpeech? = null
    private var ready = false
    private var metronomeJob: Job? = null

    /**
     * TTS motoruna bağlanma ASENKRONDUR ve saniyeler sürebilir.
     * Bir olay algılandığında bağlanma henüz bitmemiş olabilir; o anda
     * söylenecek sözü ATMAK yerine burada bekletiriz ve motor hazır olur
     * olmaz söyleriz. Gerçek cihazda ilk tatbikatta bu yüzden hiçbir anons
     * duyulmamıştı: sesler sessizce düşürülüyordu.
     */
    private val pending = ArrayDeque<Pair<String, Int>>()

    /** Türkçe ses paketi cihazda var mı? Yoksa kullanıcı onboarding'de uyarılır. */
    var turkishAvailable = false
        private set

    /**
     * TTS'i başlatır ve gerekirse MOTOR DEĞİŞTİRİR.
     *
     * Gerçek cihazda görülen arıza: sistemin varsayılan TTS motoru ayarı,
     * kurulu OLMAYAN bir pakete işaret ediyordu ve init status=-1 ile
     * başarısız oluyordu. Varsayılana güvenmek yeterli değil; cihazda
     * gerçekten kurulu motorlar taranıp sırayla denenir.
     */
    fun initialise(onReady: (Boolean) -> Unit = {}) {
        if (tts != null && ready) { onReady(true); return }
        tryEngines(candidateEngines(), 0, onReady)
    }

    /** Cihazda kurulu TTS motorları. Varsayılan varsa başa alınır. */
    private fun candidateEngines(): List<String?> {
        val installed = runCatching {
            context.packageManager
                .queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
                .mapNotNull { it.serviceInfo?.packageName }
                .distinct()
        }.getOrDefault(emptyList())
        // null = sistemin varsayılanını kullan (çoğu cihazda doğru olan budur)
        return listOf(null) + installed
    }

    private fun tryEngines(engines: List<String?>, index: Int, onReady: (Boolean) -> Unit) {
        if (index >= engines.size) {
            Log.e(TAG, "Hicbir TTS motoru baslatilamadi (${engines.size} aday denendi)")
            ready = false
            VoiceStatus.report(engineReady = false, turkishVoice = false)
            onReady(false)
            return
        }
        val engine = engines[index]
        runCatching { tts?.shutdown() }
        tts = TextToSpeech(context, { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                val turkish = Locale.forLanguageTag("tr-TR")
                val result = runCatching { tts?.setLanguage(turkish) }.getOrNull()
                turkishAvailable = result == TextToSpeech.LANG_AVAILABLE ||
                    result == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                    result == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
                if (!turkishAvailable) Log.w(TAG, "Turkce ses paketi yok (sonuc=$result)")
                applyAlarmAudioAttributes()
                // Acil durumda anlaşılırlık hızdan önemlidir.
                runCatching { tts?.setSpeechRate(0.92f) }
                Log.i(TAG, "TTS hazir (motor=${engine ?: "varsayilan"}, turkce=$turkishAvailable)")
                VoiceStatus.report(engineReady = true, turkishVoice = turkishAvailable)
                flushPending()
                onReady(true)
            } else {
                Log.w(TAG, "TTS motoru basarisiz (motor=${engine ?: "varsayilan"}, status=$status)")
                // Sıradaki motoru dene.
                tryEngines(engines, index + 1, onReady)
            }
        }, engine)
    }

    private fun applyAlarmAudioAttributes() {
        runCatching {
            tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }
    }

    /** Sırayı temizleyip hemen konuşur (geri sayım duyuruları için). */
    fun sayNow(text: String) = speak(text, TextToSpeech.QUEUE_FLUSH)

    /** Sıraya ekler (uzun anonslar için). */
    fun enqueue(text: String) = speak(text, TextToSpeech.QUEUE_ADD)

    private fun speak(text: String, queueMode: Int) {
        if (!ready) {
            // Kuyruğa al ve motor hazır olunca söyle. Kuyruk sınırlıdır:
            // uzun bir olayda birikmiş onlarca duyuruyu arka arkaya okumak
            // yardımcı olmaz, en yenileri yeterlidir.
            if (pending.size >= MAX_PENDING) pending.removeFirst()
            pending.addLast(text to queueMode)
            Log.w(TAG, "TTS henuz hazir degil, kuyruga alindi: ${text.take(40)}")
            // Bağlanma hiç başlamadıysa yeniden dene.
            if (tts == null) initialise()
            return
        }
        runCatching { tts?.speak(text, queueMode, null, UTTERANCE_ID) }
            .onFailure { Log.w(TAG, "Konusma basarisiz", it) }
    }

    private fun flushPending() {
        if (!ready || pending.isEmpty()) return
        val queued = pending.toList()
        pending.clear()
        // İlk bekleyen sırayı temizler, kalanlar arkasına eklenir.
        queued.forEachIndexed { index, (text, mode) ->
            val effective = if (index == 0) mode else TextToSpeech.QUEUE_ADD
            runCatching { tts?.speak(text, effective, null, UTTERANCE_ID) }
        }
        Log.i(TAG, "${queued.size} bekleyen anons soylendi")
    }

    fun stopSpeaking() {
        pending.clear()
        runCatching { tts?.stop() }
    }

    /**
     * TYD metronomu: dakikada 110 tık.
     * Kurtarıcı bunu duyarak doğru hızda bası yapabilir.
     */
    fun startCprMetronome() {
        if (metronomeJob?.isActive == true) return
        val intervalMs = 60_000L / CPR_BPM
        val click = buildClick()
        metronomeJob = scope.launch {
            while (isActive) {
                playClick(click)
                delay(intervalMs)
            }
        }
    }

    fun stopCprMetronome() {
        metronomeJob?.cancel()
        metronomeJob = null
    }

    /** Kısa, keskin bir tık: 40 ms 2 kHz, zarf uygulanmış (tıkırtı olmaması için). */
    private fun buildClick(): ShortArray {
        val sampleRate = 44100
        val samples = sampleRate * 40 / 1000
        return ShortArray(samples) { i ->
            val envelope = 1.0 - (i.toDouble() / samples)
            val v = sin(2.0 * PI * 2000.0 * i / sampleRate) * envelope * 0.8
            (v * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun playClick(pcm: ShortArray) {
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(44100)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm, 0, pcm.size)
            track.setNotificationMarkerPosition(pcm.size)
            track.setPlaybackPositionUpdateListener(
                object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(t: AudioTrack?) { runCatching { t?.release() } }
                    override fun onPeriodicNotification(t: AudioTrack?) = Unit
                }
            )
            track.play()
        }.onFailure { Log.w(TAG, "Metronom tiki calinamadi", it) }
    }

    fun release() {
        stopCprMetronome()
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
        ready = false
    }
}

/**
 * Sesli anons katmanının son bilinen durumu.
 *
 * Hazırlık ekranının bunu okuyabilmesi gerekir: TTS'in çalışıp çalışmadığı
 * ancak motora bağlanmayı DENEDİKTEN sonra bilinebilir ve bu asenkrondur.
 * Kullanıcıya "sesli anons çalışmıyor" demek, hiç ses çıkmayan bir alarmla
 * karşılaşmasından iyidir.
 */
object VoiceStatus {
    @Volatile var checked: Boolean = false
        private set
    @Volatile var engineReady: Boolean = false
        private set
    @Volatile var turkishVoice: Boolean = false
        private set

    fun report(engineReady: Boolean, turkishVoice: Boolean) {
        this.engineReady = engineReady
        this.turkishVoice = turkishVoice
        this.checked = true
    }
}
