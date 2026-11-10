package com.lifesaver

import com.lifesaver.alert.Action
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Servis ile alarm ekranı arasındaki köprü.
 *
 * Neden var: Android 10'dan beri ön plan servisi olmak, arka plandan
 * ACTIVITY BAŞLATMA hakkı VERMEZ. Yani yakınları arama (ACTION_CALL) ve
 * 112 çeviricisini açma gibi eylemler servisten çalıştırılamaz; görünür bir
 * aktivitenin bunları yürütmesi gerekir.
 *
 * Bu yüzden servis kararı verir, alarm ekranı aktivite gerektiren eylemleri
 * uygular. Ekran hiç açılamazsa (tam ekran niyet izni yoksa) bu eylemler
 * yapılamaz ve bu durum günlüğe DÜRÜSTÇE yazılır — sessizce yutulmaz.
 */
object EscalationBus {

    private val _actions = MutableSharedFlow<Action>(replay = 8, extraBufferCapacity = 32)
    val actions: SharedFlow<Action> = _actions

    private val _live = MutableStateFlow<LiveIncident?>(null)
    val live: StateFlow<LiveIncident?> = _live

    fun emit(action: Action) { _actions.tryEmit(action) }

    fun setLive(incident: LiveIncident?) { _live.value = incident }

    fun clear() {
        _live.value = null
        _actions.resetReplayCache()
    }
}

/** Alarm ekranının göstereceği canlı olay durumu. */
data class LiveIncident(
    val scenarioTurkish: String,
    val reason: List<String>,
    val secondsRemaining: Int,
    val drill: Boolean,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Float? = null,
    val escalated: Boolean = false,
    /**
     * Olay sensörden mi geldi?
     *
     * Elle başlatılan tatbikattan sonra "böyle bir şey oldu mu?" diye
     * sormak anlamsızdır ve kullanıcıyı, sistemin her iptalden öğrendiği
     * yanılgısına düşürür. Yalnızca gerçek bir algılama sorulur.
     */
    val learnable: Boolean = true,
)
