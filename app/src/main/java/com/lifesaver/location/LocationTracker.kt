package com.lifesaver.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LastLocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.lifesaver.alert.Fix
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Konum sağlayıcı.
 *
 * TASARIM: üç kaynak SIRAYLA değil, YARIŞTIRILARAK kullanılır.
 * Acil bir mesajın t=0'da 500 m doğrulukla gitmesi, t=45'te 5 m doğrulukla
 * gitmesinden iyidir — ve zaten ikisi de yapılır: en iyi bilinen konum
 * hemen yayınlanır, sonra iyileştikçe güncellenir.
 *
 * Play Services yoksa (bazı cihazlar, GrapheneOS) çerçeve LocationManager'a
 * düşülür; bu yol sessizce çalışmamak yerine daha kötü doğrulukla çalışır.
 */
class LocationTracker(private val context: Context) {

    private companion object { const val TAG = "LifeSaver/Location" }

    private val _lastFix = MutableStateFlow<Fix?>(null)
    val lastFix: StateFlow<Fix?> = _lastFix.asStateFlow()

    private val fused by lazy {
        runCatching { LocationServices.getFusedLocationProviderClient(context) }.getOrNull()
    }

    private var speedCallback: LocationCallback? = null

    /**
     * Sürüş sırasında 1 Hz konum akışı başlatır.
     *
     * Kaza algılamanın silahlanması ve hız çöküşü kanıtı buna bağlıdır.
     * 1 Hz'den hızlı istemek anlamsızdır: GNSS donanımı zaten ~1 Hz üretir.
     *
     * setWaitForAccurateLocation(false) kritiktir — varsayılan davranış ilk,
     * daha az doğru düzeltmeleri bilerek geciktirir ve kazadan hemen sonraki
     * düzeltme tam da o düzeltmedir.
     */
    @SuppressLint("MissingPermission")
    fun startSpeedUpdates(onSpeed: (speedMs: Float, accuracyMs: Float) -> Unit): Boolean {
        if (!hasPermission() || !isLocationEnabled()) return false
        val client = fused ?: return false
        stopSpeedUpdates()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                publish(location)
                if (!location.hasSpeed()) return
                val accuracy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    location.hasSpeedAccuracy()
                ) location.speedAccuracyMetersPerSecond else Float.MAX_VALUE
                onSpeed(location.speed, accuracy)
            }
        }
        return runCatching {
            client.requestLocationUpdates(
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                    .setMinUpdateIntervalMillis(1000L)
                    .setWaitForAccurateLocation(false)
                    .build(),
                callback,
                android.os.Looper.getMainLooper(),
            )
            speedCallback = callback
            true
        }.onFailure { Log.e(TAG, "Hiz akisi baslatilamadi", it) }.getOrDefault(false)
    }

    fun stopSpeedUpdates() {
        speedCallback?.let { cb -> runCatching { fused?.removeLocationUpdates(cb) } }
        speedCallback = null
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Cihaz genelinde konum servisleri açık mı? Kapalıysa hiçbir API çalışmaz. */
    fun isLocationEnabled(): Boolean = runCatching {
        val lm = context.getSystemService(LocationManager::class.java) ?: return false
        LocationManagerCompat.isLocationEnabled(lm)
    }.getOrDefault(false)

    /**
     * En hızlı kullanılabilir konumu döndürür ve arka planda daha iyisini arar.
     * Önbellekteki konum ANINDA yayınlanır; taze konum geldiğinde
     * [lastFix] güncellenir.
     */
    @SuppressLint("MissingPermission")
    suspend fun requestBestEffort(): Fix? {
        if (!hasPermission()) {
            Log.w(TAG, "Konum izni yok")
            return null
        }
        val client = fused
        if (client == null) {
            Log.w(TAG, "Play Services yok, cerceve saglayicisina dusuluyor")
            return frameworkLastKnown()?.also { _lastFix.value = it }
        }

        // 1) Önbellek — anında.
        runCatching {
            val cached = CompletableDeferred<Location?>()
            client.getLastLocation(
                LastLocationRequest.Builder()
                    .setMaxUpdateAgeMillis(Long.MAX_VALUE)
                    .setGranularity(com.google.android.gms.location.Granularity.GRANULARITY_FINE)
                    .build()
            ).addOnSuccessListener { cached.complete(it) }
                .addOnFailureListener { cached.complete(null) }
            cached.await()?.let { publish(it) }
        }.onFailure { Log.w(TAG, "Onbellekteki konum alinamadi", it) }

        // 2) Taze, yüksek doğruluklu tespit.
        runCatching {
            val fresh = CompletableDeferred<Location?>()
            client.getCurrentLocation(
                CurrentLocationRequest.Builder()
                    // Varsayılan BALANCED_POWER'dır ve her acil tespiti
                    // sessizce bozar. Açıkça HIGH_ACCURACY veriyoruz.
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setDurationMillis(20_000)
                    .setMaxUpdateAgeMillis(0)
                    .build(),
                null,
            ).addOnSuccessListener { fresh.complete(it) }
                .addOnFailureListener { fresh.complete(null) }
            fresh.await()?.let { publish(it) }
        }.onFailure { Log.w(TAG, "Taze konum alinamadi", it) }

        return _lastFix.value
    }

    @SuppressLint("MissingPermission")
    private fun frameworkLastKnown(): Fix? = runCatching {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                lm.hasProvider(LocationManager.FUSED_PROVIDER)
            ) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }
        providers.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { it.toFix() }
    }.getOrNull()

    private fun publish(location: Location) {
        val fix = location.toFix()
        val current = _lastFix.value
        // Daha eski ya da belirgin şekilde daha kötü bir tespitle üzerine yazma.
        if (current == null || fix.accuracyMeters <= current.accuracyMeters) {
            _lastFix.value = fix
        }
    }

    private fun Location.toFix(): Fix {
        val ageMs = (System.currentTimeMillis() - time).coerceAtLeast(0L)
        return Fix(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = if (hasAccuracy()) accuracy else 0f,
            ageSeconds = (ageMs / 1000L).toInt(),
        )
    }
}
