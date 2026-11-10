package com.lifesaver.actuator

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/**
 * Siren.
 *
 * Ses varlık dosyası GÖMÜLMEZ, üretilir: kodek sürprizi yok, APK yükü yok.
 * Dalga biçimi 1000-3000 Hz arası faz-sürekli bir süpürmedir; ISO 7731'in
 * 500-2500 Hz uyarı bandının içinde ve kulağın en duyarlı olduğu 2-4 kHz
 * bölgesine yakın kalırken telefon hoparlörünün verimli olduğu aralıkta durur.
 *
 * İKİ KATMANLI ÇALAR — sebebi önemli:
 *  1. USAGE_ALARM + FLAG_AUDIBILITY_ENFORCED: sessiz modu ve "yalnızca
 *     öncelikli" Rahatsız Etmeyin ayarını aşar.
 *  2. USAGE_ASSISTANCE_ACCESSIBILITY: Android'in ZenModeHelper'ının hiçbir
 *     zaman susturmadığı tek kullanım türü; "Tam sessizlik" modunda ayakta
 *     kalan katman budur.
 *
 * Uygulama Rahatsız Etmeyin'i KAPATAMAZ (targetSdk 35+ sonrası
 * setInterruptionFilter yalnızca birleştirilen bir kural oluşturur ve en
 * kısıtlayıcı olan kazanır). Bu yüzden çözüm bastırmak değil, atlatmaktır.
 */
class Siren(private val context: Context) {

    private companion object {
        const val TAG = "LifeSaver/Siren"
        const val SAMPLE_RATE = 44100
        const val SWEEP_LOW_HZ = 1000.0
        const val SWEEP_HIGH_HZ = 3000.0
        const val LEG_MS = 400
        const val AMPLITUDE = 0.9f
    }

    private var tracks = mutableListOf<AudioTrack>()
    private var previousAlarmVolume: Int? = null

    val isPlaying: Boolean get() = tracks.isNotEmpty()

    fun start() {
        if (isPlaying) return
        raiseAlarmVolume()

        val pcm = buildSweep()
        // Katman 1: alarm akışı, duyulabilirlik zorlanmış.
        createTrack(
            usage = AudioAttributes.USAGE_ALARM,
            enforceAudibility = true,
            pcm = pcm,
        )?.let { tracks += it }

        // Katman 2: erişilebilirlik akışı — tam sessizlikte bile susturulmaz.
        createTrack(
            usage = AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
            enforceAudibility = false,
            pcm = pcm,
        )?.let { tracks += it }

        if (tracks.isEmpty()) Log.e(TAG, "Hicbir ses katmani baslatilamadi")
    }

    fun stop() {
        tracks.forEach { track ->
            runCatching { track.pause(); track.flush(); track.stop() }
            runCatching { track.release() }
        }
        tracks.clear()
        restoreAlarmVolume()
    }

    /**
     * Alarm akışını sonuna kadar açar ve önceki değeri saklar.
     * Yanlış alarmdan sonra kullanıcının ses ayarını bozulmuş bırakmak
     * kabul edilemez; [stop] eski değeri geri koyar.
     */
    private fun raiseAlarmVolume() {
        runCatching {
            val am = context.getSystemService(AudioManager::class.java) ?: return
            previousAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(
                AudioManager.STREAM_ALARM,
                am.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0,
            )
        }.onFailure { Log.w(TAG, "Alarm sesi yukseltilemedi", it) }
    }

    private fun restoreAlarmVolume() {
        val previous = previousAlarmVolume ?: return
        previousAlarmVolume = null
        runCatching {
            context.getSystemService(AudioManager::class.java)
                ?.setStreamVolume(AudioManager.STREAM_ALARM, previous, 0)
        }
    }

    private fun createTrack(usage: Int, enforceAudibility: Boolean, pcm: ShortArray): AudioTrack? =
        runCatching {
            val attributesBuilder = AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            if (enforceAudibility) {
                attributesBuilder.setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
            }

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            val track = AudioTrack.Builder()
                .setAudioAttributes(attributesBuilder.build())
                .setAudioFormat(format)
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(pcm, 0, pcm.size)
            track.setLoopPoints(0, pcm.size, -1)   // -1 = sonsuz döngü
            track.setVolume(AudioTrack.getMaxVolume())
            track.play()
            track
        }.onFailure { Log.e(TAG, "Ses katmani olusturulamadi (usage=$usage)", it) }
            .getOrNull()

    /**
     * Faz-sürekli yukarı-aşağı süpürme üretir.
     *
     * Faz sürekliliği şart: her örnekte frekansı değiştirip fazı sıfırlamak
     * duyulabilir "tık" sesleri yaratır ve sinyali kirletir. Bu yüzden faz
     * biriktirilir, hesaplanmaz.
     */
    private fun buildSweep(): ShortArray {
        val legSamples = SAMPLE_RATE * LEG_MS / 1000
        val total = legSamples * 2
        val out = ShortArray(total)
        var phase = 0.0

        for (i in 0 until total) {
            val progress = if (i < legSamples) {
                i.toDouble() / legSamples                    // yukarı
            } else {
                1.0 - (i - legSamples).toDouble() / legSamples // aşağı
            }
            val freq = SWEEP_LOW_HZ + (SWEEP_HIGH_HZ - SWEEP_LOW_HZ) * progress
            phase += 2.0 * PI * freq / SAMPLE_RATE
            if (phase > 2.0 * PI) phase -= 2.0 * PI
            out[i] = (sin(phase) * AMPLITUDE * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }
}
