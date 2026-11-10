package com.lifesaver.actuator

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Fener ile Mors alfabesinde SOS.
 *
 * Neden düz yanıp sönme değil de SOS: uzaktan gören biri için kendini
 * açıklar ("bu bir imdat işareti"), uluslararası olarak tanınır
 * (ITU-R M.1677-1) ve ortalama ~1.2 flaş/saniye ile fotosensitif epilepsi
 * için kabul edilen sınırların altında kalır.
 *
 * NOT: setTorchMode CAMERA iznini GEREKTİRMEZ.
 *
 * LED'in ısınıp kendini kapatmasını önlemek için 30 s yanma / 10 s dinlenme
 * görev döngüsü uygulanır.
 */
class TorchStrobe(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private companion object {
        const val TAG = "LifeSaver/Torch"
        const val UNIT_MS = 250L          // Mors temel birimi
        const val DUTY_ON_MS = 30_000L
        const val DUTY_REST_MS = 10_000L
    }

    private val cameraManager: CameraManager? =
        runCatching { context.getSystemService(CameraManager::class.java) }.getOrNull()

    private var job: Job? = null

    /** Feneri olan ilk kamera. Yoksa bu aktüatör sessizce devre dışı kalır. */
    private val torchCameraId: String? by lazy {
        runCatching {
            cameraManager?.cameraIdList?.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.onFailure { Log.w(TAG, "Kamera listesi okunamadi", it) }.getOrNull()
    }

    val isAvailable: Boolean get() = torchCameraId != null

    fun start() {
        if (job?.isActive == true) return
        val id = torchCameraId ?: run {
            Log.w(TAG, "Fener yok, bu aktuator atlaniyor")
            return
        }
        job = scope.launch {
            try {
                while (isActive) {
                    val until = System.currentTimeMillis() + DUTY_ON_MS
                    while (isActive && System.currentTimeMillis() < until) {
                        sos(id)
                    }
                    setTorch(id, false)
                    delay(DUTY_REST_MS)   // LED'in soğuması için
                }
            } finally {
                // İptal edilse bile feneri açık bırakma.
                runCatching { setTorch(id, false) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        torchCameraId?.let { runCatching { setTorch(it, false) } }
    }

    /** ... --- ...  (3 kısa, 3 uzun, 3 kısa) */
    private suspend fun sos(id: String) {
        repeat(3) { flash(id, UNIT_MS) }          // S
        delay(UNIT_MS)
        repeat(3) { flash(id, UNIT_MS * 3) }      // O
        delay(UNIT_MS)
        repeat(3) { flash(id, UNIT_MS) }          // S
        delay(UNIT_MS * 7)                        // harf arası boşluk
    }

    private suspend fun flash(id: String, onMs: Long) {
        scope.ensureActive()
        setTorch(id, true)
        delay(onMs)
        setTorch(id, false)
        delay(UNIT_MS)
    }

    /**
     * Fener durumunu değiştirir.
     * Kamera başka bir uygulama tarafından kullanılıyorsa bu çağrı hata verir;
     * o durumda SESSİZCE devam edilir — siren ve titreşim çalışmaya devam
     * etmelidir. Tek bir aktüatörün başarısızlığı zinciri düşürmez.
     */
    private fun setTorch(id: String, on: Boolean) {
        runCatching { cameraManager?.setTorchMode(id, on) }
            .onFailure { Log.w(TAG, "Fener degistirilemedi (on=$on)", it) }
    }
}
