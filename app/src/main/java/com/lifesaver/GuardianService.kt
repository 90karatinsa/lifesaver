package com.lifesaver

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.lifesaver.alert.Action
import com.lifesaver.alert.AlertContext
import com.lifesaver.alert.AlertTexts
import com.lifesaver.alert.Confidence
import com.lifesaver.alert.Contact
import com.lifesaver.alert.EscalationMachine
import com.lifesaver.alert.EscalationPlan
import com.lifesaver.alert.EscalationState
import com.lifesaver.alert.Fix
import com.lifesaver.alert.Scenario
import com.lifesaver.actuator.Haptics
import com.lifesaver.actuator.Siren
import com.lifesaver.actuator.TorchStrobe
import com.lifesaver.actuator.Voice
import com.lifesaver.data.IncidentEntry
import com.lifesaver.data.Storage
import com.lifesaver.detect.FallDetector
import com.lifesaver.detect.FallOutcome
import com.lifesaver.detect.ActivityMode
import com.lifesaver.detect.DetectionProfile
import com.lifesaver.detect.CrashDetector
import com.lifesaver.detect.CrashOutcome
import com.lifesaver.detect.DetectionConfidence
import com.lifesaver.detect.Liveness
import com.lifesaver.detect.SensorLiveness
import com.lifesaver.detect.VehicleHint
import com.lifesaver.detect.WaveMonitor
import com.lifesaver.detect.VehicleMode
import com.lifesaver.learn.CancelReason
import com.lifesaver.learn.CancellationRecord
import com.lifesaver.learn.ThresholdTuner
import com.lifesaver.learn.TunableParameter
import com.google.android.gms.location.ActivityRecognition
import com.lifesaver.location.LocationTracker
import com.lifesaver.ui.AlertActivity
import com.lifesaver.ui.MainActivity
import com.lifesaver.ui.ModePickerActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Koruma servisi — uygulamanın kalbi.
 *
 * Sürekli sensör okur, [FallDetector]'a besler, bir olay algılandığında
 * [EscalationMachine]'i çalıştırır ve makinenin ürettiği emirleri uygular.
 *
 * DAYANIKLILIK NOTLARI (araştırmadan doğrulanmış):
 *  - Android 9'dan beri ARKA PLANDAKİ bir uygulama sürekli sensör olayı ALMAZ.
 *    Ön plan servisi bir optimizasyon değil, zorunluluktur.
 *  - Bu cihazın ivmeölçeri wake-up DEĞİLDİR: kısmi wake lock olmadan SoC
 *    uykuya dalar ve donanım FIFO'su taşınca EN ESKİ örnekler düşürülür.
 *    Bu, düşme anının sessizce kaybolması demektir. Wake lock şarttır ve
 *    bunun pil bedeli kullanıcıya açıkça söylenir.
 *  - startForeground'a verilen tip maskesi ÇALIŞMA ZAMANINDA hesaplanır:
 *    konum izni yoksa 'location' tipiyle çağrı SecurityException atar ve
 *    servis hiç başlamaz.
 */
class GuardianService : LifecycleService(), SensorEventListener {

    private companion object {
        const val TAG = "LifeSaver/Guardian"
        const val ACTION_START = "com.lifesaver.START"
        const val ACTION_STOP = "com.lifesaver.STOP"
        const val ACTION_DRILL = "com.lifesaver.DRILL"
        const val ACTION_CANCEL = "com.lifesaver.CANCEL"
        const val ACTION_SET_MODE = "com.lifesaver.SET_MODE"
        const val ACTION_CANCEL_REASON = "com.lifesaver.CANCEL_REASON"
        const val EXTRA_REASON = "com.lifesaver.extra.REASON"
        const val SAMPLE_PERIOD_US = 20_000          // 50 Hz (düşme için yeterli)
        /**
         * Kaza darbesi 50-150 ms sürer. 50 Hz'de bu 2.5-7.5 örnek eder ve
         * darbenin ŞEKLİ okunamaz. 200 Hz'de 10-30 örnek olur — kullanılabilir
         * en düşük hız budur. 200 Hz, HIGH_SAMPLING_RATE_SENSORS izni
         * gerektirmeden alınabilen üst sınırdır.
         */
        const val CRASH_SAMPLE_PERIOD_US = 5_000     // 200 Hz
        /** Ekran kapalıyken toplu bildirim: SoC daha uzun uyuyabilir. */
        const val BATCH_LATENCY_US = 2_000_000       // 2 s
        const val TICK_MS = 500L

        /**
         * Etkinlik tanıma sonucunun tazelik sınırı.
         * Bunun olmaması, saatler önceki bir "araçta" okumasının dedektörü
         * silahlandırmasına izin veriyordu.
         */
        const val ACTIVITY_FRESH_MS = 90_000L

        /**
         * Araç kipinin ömrü. Her gerçek silahlanmada yenilenir, yani 12
         * saatlik bir yolculuk asla düşmez; buna karşılık akşam arabaya binip
         * kipi kapatmayı unutan kullanıcı sabaha Günlük kipindedir.
         */
        const val VEHICLE_MODE_TTL_MS = 3 * 60 * 60 * 1000L

        /**
         * Araçtan indiğine karar vermek için gereken adım sayısı.
         * ~140-160 m yürümeye denk gelir: trafikte bekleyen sürücü 0 adım
         * atar, park edip uzaklaşan biri bunu birkaç dakikada aşar.
         */
        const val STEPS_TO_LEAVE_VEHICLE = 200

        /** Son yüksek hız okumasından sonra beklenen süre. */
        const val QUIET_AFTER_DRIVING_MS = 10 * 60 * 1000L

        /**
         * Araç ipucu ateşleyince konum akışının açık kalacağı süre.
         *
         * Bu bir kanıt penceresidir, bir alarm değil: ipucu "araçta olabilir"
         * der, GNSS ise hızı ölçüp kararı verir. Doğruysa dedektör silahlanır
         * ve pencere kendiliğinden uzar; yanlışsa 60 saniyede kapanır.
         */
        const val HINT_PROBE_MS = 60_000L

        /**
         * Dalga hareketi altında kullanılacak hareketsizlik aralığı (g).
         * Tekne profilinin kendi değeriyle aynıdır.
         */
        const val WAVE_STILLNESS_RANGE_G = 0.80f

        /** Dalga bu kadar süredir yoksa gevşetme geri alınır. */
        const val WAVE_RELAX_TTL_MS = 10 * 60 * 1000L
    }

    private lateinit var storage: Storage
    private lateinit var sensorManager: SensorManager
    private lateinit var locationTracker: LocationTracker

    private lateinit var siren: Siren
    private lateinit var torch: TorchStrobe
    private lateinit var haptics: Haptics
    private lateinit var voice: Voice

    private var wakeLock: PowerManager.WakeLock? = null
    private var detector: FallDetector? = null
    private var crashDetector: CrashDetector? = null
    private var crashArmed = false
    private var vehicleMode: VehicleMode = VehicleMode.OFF
    private var profile: DetectionProfile = DetectionProfile.forMode(ActivityMode.DAILY)

    /**
     * Adli/öğrenme penceresi: son 30 saniyenin |a| değeri, 10 Hz'e indirgenmiş.
     * Bir alarm iptal edildiğinde bu pencere kaydedilir; eşik ayarı ancak
     * gerçek saha verisiyle yapılabilir.
     */
    private val forensicWindow = ArrayDeque<Float>(300)
    private var lastForensicMs = 0L
    private var lastDetectionScore = 0
    private var lastDetectionConfidence = Confidence.LOW
    private var lastDetectionFeatures = mapOf<String, Float>()
    private var lastDetectionEvidence = listOf<String>()
    private var machine: EscalationMachine? = null
    private var tickJob: Job? = null
    private var currentScenario: Scenario = Scenario.FALL
    private var drillActive = false
    /** Olay sensörden mi geldi? Elle başlatılan tatbikattan ÖĞRENİLMEZ. */
    private var incidentSensorDriven = false
    /** Canlı olay yüzünden ertelenmiş bir yeniden kurulum var mı? */
    private var reloadPending = false

    /** Konum akışı şu an açık mı? */
    private var speedStreamOn = false

    /**
     * Araç ipucu: GMS'siz, izinsiz, sıfır ek pille "araca binmiş olabilir".
     *
     * NEDEN VAR: Günlük kipte kaza algılamanın tek otomatik giriş kapısı
     * Google'ın etkinlik tanıma sınıflandırıcısıydı. O sınıflandırıcı Play
     * Services sürecinde çalışır ve izin/pil ayarları eksikse SESSİZCE ölür —
     * hata dönmez, sadece hiç sonuç gelmez. Tek kapıya bağlı kalmak, görünmez
     * bir koruma boşluğu demekti. Bu ipucu ikinci kapıdır ve tek yönlü kurala
     * uyar: yalnızca kapsam EKLER, hiçbir eşiği gevşetmez.
     */
    private val vehicleHint = VehicleHint()
    /** İpucunun açtığı doğrulama penceresinin bitiş anı. */
    private var hintProbeUntilMs = 0L

    /**
     * Dalga imzası: "bu telefon su üstünde bir şeyin üzerinde".
     *
     * NEDEN VAR: Tekne kipini AÇMAYI UNUTMAK sessiz bir arızadır. Güvertede
     * dalga hiç durmaz; karadaki 0.40 g hareketsizlik ölçütü teknede asla
     * sağlanmaz ve gerçek bir düşme MOVED_AFTER_IMPACT diye elenir. Günlüğe
     * "elendi" yazıldığı için sistem SAĞLIKLI görünür.
     *
     * Bu izleyicinin yetkisi bilinçli olarak dardır: kipi DEĞİŞTİRMEZ,
     * yalnızca hareketsizlik kapısını gevşetir — yani daha çok adayın
     * geçmesine izin verir. Tam tekne kipine geçmek kullanıcının kararıdır.
     */
    private val waveMonitor = WaveMonitor()
    private var stillnessRelaxed = false
    private var lastWaveActiveMs = 0L
    /** Araç kipinden Günlük'e otomatik dönüş için sayaçlar. */
    private var lastFastSpeedMs = 0L
    private var stepsSinceVehicle = 0
    private var modeExpiresAtMs = 0L

    private val liveness = SensorLiveness()
    private var lastLiveness: Liveness = Liveness.WARMING
    private var livenessJob: Job? = null
    private var activityJob: Job? = null

    private var significantMotionSensor: Sensor? = null
    private val significantMotionListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            detector?.onSignificantMotion(System.nanoTime())
            // Tek atımlıdır: her tetiklemeden sonra kendini kapatır,
            // bu yüzden yeniden istenmesi ZORUNLUDUR.
            rearmSignificantMotion()
        }
    }

    override fun onCreate() {
        super.onCreate()
        storage = (application as LifeSaverApp).storage
        sensorManager = getSystemService(SensorManager::class.java)
        locationTracker = LocationTracker(this)
        siren = Siren(this)
        torch = TorchStrobe(this, lifecycleScope)
        haptics = Haptics(this)
        voice = Voice(this, lifecycleScope).also { it.initialise() }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { stopGuarding(); return START_NOT_STICKY }
            ACTION_CANCEL -> { cancelIncident(); return START_STICKY }
            ACTION_SET_MODE -> return onModeChanged()
            ACTION_CANCEL_REASON -> {
                applyCancelReason(
                    CancelReason.entries.firstOrNull {
                        it.name == intent.getStringExtra(EXTRA_REASON)
                    } ?: CancelReason.UNSPECIFIED
                )
                return START_STICKY
            }
            ACTION_DRILL -> { startForegroundSafely(); runDrill() }
            else -> { startForegroundSafely(); startGuarding() }
        }
        // START_STICKY: süreç öldürülürse sistem servisi yeniden kursun.
        return START_STICKY
    }

    // ---- Etkinlik kipi ----------------------------------------------------

    /**
     * Kip değiştiğinde çağrılır (bildirimdeki "Kip değiştir" veya ana ekran).
     *
     * Kipi DİSKE YAZAN taraf çağıran aktivitedir; servis burada yalnızca
     * yeniden yükler. Koruma kapalıyken bu komut servisi ayakta tutmaz:
     * kullanıcı korumayı açmadan servis çalışmamalıdır.
     */
    private fun onModeChanged(): Int {
        if (detector == null && crashDetector == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val mode = ActivityMode.fromName(storage.state.value.config.activityMode)
        logIncident(IncidentEntry.KIND_SERVICE, note = "Etkinlik kipi degisti: ${mode.turkish}")
        reloadDetection()
        return START_STICKY
    }

    /** Algılayıcıları yeni kip profiliyle baştan kurar. */
    private fun reloadDetection() {
        // Koruma kapalıyken yeniden kurma: kullanıcının kapattığı korumayı
        // bir kip değişikliği ya da öğrenme turu geri açmamalıdır.
        if (detector == null && crashDetector == null) return

        // CANLI OLAY SIRASINDA ERTELE. Yeniden kurmak sensör akışını keser,
        // kanıt tamponlarını boşaltır ve olayın altından zeminini çeker.
        // Değişiklik kaybolmaz: olay kapanınca uygulanır.
        val live = machine?.state
        if (live != null && live != EscalationState.IDLE &&
            live != EscalationState.CANCELLED && live != EscalationState.RESOLVED
        ) {
            reloadPending = true
            logIncident(
                IncidentEntry.KIND_SERVICE,
                note = "Kip degisimi ertelendi: canli olay surerken algilayici yeniden kurulmaz",
            )
            return
        }
        reloadPending = false
        sensorManager.unregisterListener(this)
        significantMotionSensor?.let {
            runCatching { sensorManager.cancelTriggerSensor(significantMotionListener, it) }
        }
        activityJob?.cancel()
        livenessJob?.cancel()
        liveness.reset()
        lastLiveness = Liveness.WARMING
        locationTracker.stopSpeedUpdates()
        detector = null
        crashDetector = null
        startGuarding()
        updateGuardNotification(Liveness.WARMING, "Kip değişti, sensörler yeniden kuruluyor")
    }

    // ---- Ön plan servisi --------------------------------------------------

    private fun startForegroundSafely() {
        val notification = buildGuardNotification("Koruma etkin", "Sensörler izleniyor")
        val type = computeForegroundType()
        runCatching {
            ServiceCompat.startForeground(this, LifeSaverApp.NOTIFICATION_GUARD, notification, type)
        }.onFailure {
            Log.e(TAG, "startForeground basarisiz (tip=$type)", it)
            // Konum tipiyle olmadıysa yalnızca specialUse ile yeniden dene:
            // korumanın hiç başlamamasındansa konumsuz başlaması iyidir.
            runCatching {
                ServiceCompat.startForeground(
                    this, LifeSaverApp.NOTIFICATION_GUARD, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            }.onFailure { inner -> Log.e(TAG, "specialUse ile de basarisiz", inner) }
        }
    }

    /**
     * Tip maskesini çalışma zamanında hesaplar.
     * 'location' tipi yalnızca konum izni VE cihaz genelinde konum servisi
     * açıksa yasaldır; ikinci koşul uygulamanın kontrolünde değildir.
     */
    private fun computeForegroundType(): Int {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (locationTracker.hasPermission() && locationTracker.isLocationEnabled()) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return type
    }

    private fun buildGuardNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, GuardianService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Kip seçici: bildirimden tek dokunuşla açılır. Kip yanlışsa algılama
        // yanlış eşiklerle çalışır, bu yüzden değiştirmesi kolay olmalıdır.
        val pickMode = PendingIntent.getActivity(
            this, 2, Intent(this, ModePickerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val mode = ActivityMode.fromName(storage.state.value.config.activityMode)
        return NotificationCompat.Builder(this, LifeSaverApp.CHANNEL_GUARD)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$title · ${mode.turkish}")
            .setContentText(text)
            .setSubText(mode.shortLabel)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n${mode.description}"))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Kip değiştir", pickMode)
            .addAction(0, "Korumayı durdur", stop)
            .build()
    }

    // ---- Sensörler ---------------------------------------------------------

    private fun startGuarding() {
        if (detector != null) return
        acquireWakeLock()

        val config = storage.state.value.config
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accel == null) {
            Log.e(TAG, "Ivmeolcer yok; koruma calisamaz")
            logIncident(IncidentEntry.KIND_SERVICE, note = "Ivmeolcer bulunamadi, koruma baslatilamadi")
            return
        }

        // Kip profili: hangi dedektörün hangi eşiklerle çalıştığı buradan gelir.
        val mode = ActivityMode.fromName(config.activityMode)
        profile = DetectionProfile.forMode(mode)

        // Öğrenilmiş ayarlamaları uygula. Tavan burada da zorlanır.
        val tuner = ThresholdTuner()
        val tunedFall = if (config.learningEnabled) {
            val impactTuning = config.tunings.firstOrNull {
                it.mode == mode.name && it.parameter == TunableParameter.FALL_IMPACT_G
            }
            val scoreTuning = config.tunings.firstOrNull {
                it.mode == mode.name && it.parameter == TunableParameter.FALL_MIN_SCORE
            }
            profile.fallConfig.copy(
                impactG = tuner.apply(profile.fallConfig.impactG, impactTuning),
                minScore = tuner.applyScore(profile.fallConfig.minScore, scoreTuning),
            )
        } else profile.fallConfig

        detector = if (profile.fallEnabled) {
            FallDetector(
                config = tunedFall,
                accelMaxRange = accel.maximumRange,
                noiseFloorG = config.noiseFloorG,
            )
        } else null

        // CANLI OLAY KORUMASI: burada koşulsuz yeni bir makine atamak, geri
        // sayım sürerken yapılan bir kip değişikliğinde canlı olayı SESSİZCE
        // öldürüyordu — makine IDLE bir yenisiyle değişiyor, StopAlarm'ın tek
        // kaynağı olan onUserCancel artık boş liste döndürüyor ve siren
        // susturulamaz hale geliyordu.
        if (machine == null || machine?.state == EscalationState.IDLE) {
            machine = EscalationMachine(contacts()) { alertContext() }
        }
        startCrashDetection(accel.maximumRange)

        register(accel)
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { register(it) }
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let { register(it) }
        sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { register(it) }
        sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { register(it) }
        sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { register(it) }
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let { register(it) }

        significantMotionSensor = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        rearmSignificantMotion()

        logIncident(
            IncidentEntry.KIND_SERVICE,
            note = "Koruma baslatildi. Ivmeolcer tavani=${accel.maximumRange} m/s2, " +
                "FIFO=${accel.fifoReservedEventCount}, wakeUp=${accel.isWakeUpSensor}",
        )
        startLivenessWatch()
        Log.i(TAG, "Koruma basladi")
    }

    /**
     * Sensör akışının gerçekten canlı olup olmadığını izler.
     *
     * Bu kontrol olmadan uygulama, sensör verisi sıfırlanmış geldiğinde
     * "her şey sakin" sanır ve kullanıcı korunduğunu zanneder. getDefaultSensor()
     * bu durumda null DÖNMEZ, dolayısıyla nesne varlığına bakmak yetmez.
     * Bağlı test cihazı (GrapheneOS) bunu somut bir risk yapıyor; stok
     * Android'de de hızlı ayarlardaki "Sensörler kapalı" anahtarı aynı sonucu
     * verir ve o anahtarın durumunu okumanın API'si yoktur.
     */
    private fun startLivenessWatch() {
        livenessJob?.cancel()
        livenessJob = lifecycleScope.launch {
            // İlk değerlendirmeden önce tamponun dolmasını bekle.
            delay(8_000)
            while (isActive) {
                val now = liveness.evaluate(System.currentTimeMillis())
                if (now != lastLiveness) {
                    lastLiveness = now
                    val text = liveness.describe(now)
                    logIncident(IncidentEntry.KIND_SERVICE, note = "Sensor canliligi: $now - $text")
                    updateGuardNotification(now, text)
                }
                delay(15_000)
            }
        }
    }

    private fun updateGuardNotification(state: Liveness, text: String) {
        val title = if (state == Liveness.OK) "Koruma etkin" else "KORUMA ÇALIŞMIYOR"
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this)
                .notify(LifeSaverApp.NOTIFICATION_GUARD, buildGuardNotification(title, text))
        }
    }

    /**
     * Kaza algılamayı kurar.
     *
     * DEDEKTÖR ARTIK HER KİPTE KURULUR. Eskiden kipe bağlıydı ve bu, en
     * öldürücü arıza koluydu: kipini değiştirmeyi unutan motosikletli için
     * 145 puanlık kaza kanıt havuzunun tamamı devre dışı kalıyordu. Dedektörü
     * kurmak yalnızca KAPSAMA EKLER — hiçbir eşiği gevşetmez — bu yüzden
     * otomatik olması güvenlidir.
     *
     * Kipe bağlı olan tek şey KONUM AKIŞIDIR, çünkü asıl pil bedeli odur.
     * Günlük kipte konum kapalıdır ve yalnızca etkinlik tanıma "araçta"
     * dediğinde fırsatçı olarak açılır.
     */
    private fun startCrashDetection(accelMaxRange: Float) {
        vehicleMode = profile.crashVehicleMode
        if (!profile.crashEnabled || vehicleMode == VehicleMode.OFF) {
            crashDetector = null
            locationTracker.stopSpeedUpdates()
            speedStreamOn = false
            return
        }
        crashDetector = CrashDetector(
            config = profile.crashConfig,
            accelMaxRange = accelMaxRange,
            mode = vehicleMode,
            sampleRateHz = 200,
        )
        // Kip değişince ipucunun biriktirdiği kanıt artık başka bir dünyaya
        // aittir; sıfırdan başlasın.
        vehicleHint.reset()
        hintProbeUntilMs = 0L
        waveMonitor.reset()
        stillnessRelaxed = false
        lastWaveActiveMs = 0L
        if (profile.continuousLocation) startSpeedStream() else stopSpeedStream()
        requestActivityUpdates()
        logIncident(
            IncidentEntry.KIND_SERVICE,
            note = "Kaza algilama kuruldu (kip=${profile.mode.name}, " +
                "surekli konum=${profile.continuousLocation}, hiz akisi=$speedStreamOn)",
        )
    }

    private fun startSpeedStream() {
        if (speedStreamOn) return
        speedStreamOn = locationTracker.startSpeedUpdates { speedMs, accuracyMs ->
            crashDetector?.onSpeed(System.nanoTime(), speedMs, accuracyMs)
            if (speedMs >= profile.crashConfig.armSpeedMs) {
                lastFastSpeedMs = System.currentTimeMillis()
                stepsSinceVehicle = 0
                modeExpiresAtMs = System.currentTimeMillis() + VEHICLE_MODE_TTL_MS
            }
        }
    }

    /**
     * İpucu şu an dinlenmeli mi?
     *
     * Zaten konum akan bir durumda ipucu ateşlemek bekleme süresini boşa
     * harcardı. Beslemenin aralıklı olması güvenlidir: [VehicleHint] örnek
     * boşluğu görünce biriken kanıtı zaten unutur.
     */
    private fun wantsVehicleHint(): Boolean =
        crashDetector != null &&
            !profile.continuousLocation &&
            !speedStreamOn &&
            crashDetector?.isArmed != true

    /**
     * İpucu ateşledi: KONUMU AÇ, ALARM ÜRETME.
     *
     * Buradaki ayrım tasarımın özü. İpucu bir karar değil bir sorudur; cevabı
     * GNSS'in ölçtüğü hız verir. Bu yüzden yanlış tetiklemenin bedeli bir
     * yanlış alarm değil, 60 saniyelik bir konum penceresidir.
     */
    private fun onVehicleHintFired() {
        startSpeedStream()
        if (!speedStreamOn) return          // konum izni yok; sessizce vazgeç
        hintProbeUntilMs = System.currentTimeMillis() + HINT_PROBE_MS
        logIncident(
            IncidentEntry.KIND_SERVICE,
            note = "Arac ipucu: adimsiz surus benzeri hareket; hizi olcmek " +
                "icin konum ${HINT_PROBE_MS / 1000} sn acildi",
        )
    }

    /**
     * Dalga hareketi doğrulandı: KAPIYI GEVŞET, KİPİ DEĞİŞTİRME.
     *
     * Yapılan tek değişiklik hareketsizlik aralığını genişletmektir ve bu
     * tek yönlü kurala uyar: yalnızca daha çok adayın geçmesine izin verir,
     * hiçbir şeyi görünmez yapmaz. Bedeli iptal edilebilir bir yanlış
     * alarmdır; yapılmamasının bedeli ise hiç çalmayan bir alarmdır.
     *
     * Tam tekne kipi SORULUR, uygulanmaz: o kip darbe eşiğini de yükseltir
     * ve karada duyarlılığı düşürür. Duyarlılık düşüren hiçbir karar
     * otomatik verilmez.
     */
    private fun onWaveConfirmed() {
        val d = detector ?: return
        if (!d.relaxStillness(WAVE_STILLNESS_RANGE_G)) return
        stillnessRelaxed = true
        val hz = waveMonitor.lastFrequencyHz
        logIncident(
            IncidentEntry.KIND_SERVICE,
            note = "Dalga imzasi dogrulandi (%.2f Hz, %.2f m/s2); hareketsizlik "
                .format(hz, waveMonitor.lastBandRms) +
                "kapisi ${d.stillnessRangeG} g'ye gevsetildi. Kip DEGISMEDI.",
        )
        askAboutBoatMode(hz)
    }

    /**
     * Gevşetmeyi geri alır. Karaya çıkıldığında karadaki eşiğe dönülmelidir:
     * gevşemiş kapı karada yanlış alarm üretir.
     */
    private fun maybeUndoWaveRelax() {
        if (!stillnessRelaxed) return
        if (waveMonitor.waveActive) return
        val since = lastWaveActiveMs
        if (since > 0L && System.currentTimeMillis() - since < WAVE_RELAX_TTL_MS) return
        detector?.resetStillnessRelax()
        stillnessRelaxed = false
        logIncident(
            IncidentEntry.KIND_SERVICE,
            note = "Dalga hareketi kesildi; hareketsizlik kapisi profil " +
                "degerine dondu",
        )
    }

    /**
     * Tekne kipini SORAR. Kendiliğinden geçmez.
     *
     * Tekne kipi darbe eşiğini 2.60 g'den 3.20 g'ye çıkarır; bu bir
     * duyarlılık DÜŞÜŞÜDÜR ve otomatik verilemez. Yanlış bir çıkarım
     * kullanıcıyı karada sessizce sağır bırakırdı.
     */
    private fun askAboutBoatMode(hz: Float) {
        if (ActivityMode.fromName(storage.state.value.config.activityMode) ==
            ActivityMode.BOAT
        ) return
        val pick = PendingIntent.getActivity(
            this, 14, Intent(this, ModePickerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val period = if (hz > 0f) "%.0f".format(1f / hz) else "?"
        val notification = NotificationCompat.Builder(this, LifeSaverApp.CHANNEL_GUARD)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Teknede misiniz?")
            .setContentText("Dalga hareketi algılandı; düşme kapısı gevşetildi.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Yaklaşık $period saniyelik düzenli bir salınım iki dakikadan " +
                        "uzun sürdü. Düşme algılamanın hareketsizlik kapısı buna " +
                        "göre gevşetildi; bunun için bir şey yapmanız gerekmiyor.\n\n" +
                        "Teknedeyseniz Tekne kipine geçmek diğer eşikleri de " +
                        "uyarlar. DİKKAT: Tekne kipinde devrilme ve denize düşme " +
                        "algılama YOKTUR."
                )
            )
            .setSilent(true)
            .setAutoCancel(true)
            .setTimeoutAfter(30 * 60_000L)
            .addAction(0, "Kip değiştir", pick)
            .build()
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this)
                .notify(LifeSaverApp.NOTIFICATION_WAVE, notification)
        }
    }

    private fun stopSpeedStream() {
        if (!speedStreamOn) return
        locationTracker.stopSpeedUpdates()
        speedStreamOn = false
    }

    /** Etkinlik tanıma: hızın üretilemediği yerlerde araç bağlamını kapatır. */
    private fun requestActivityUpdates() {
        runCatching {
            val intent = Intent(this, VehicleActivityReceiver::class.java)
                .setAction(VehicleActivityReceiver.ACTION)
            val pending = PendingIntent.getBroadcast(
                this, 7, intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            ActivityRecognition.getClient(this).requestActivityUpdates(20_000L, pending)
        }.onFailure { Log.w(TAG, "Etkinlik tanima acilamadi", it) }

        // Alıcının yazdığı bağlamı algılayıcıya aktar.
        activityJob?.cancel()
        activityJob = lifecycleScope.launch {
            while (isActive) {
                val ctx = VehicleContext
                // TEŞHİS: etkinlik tanımanın gerçekten çalıştığını diske
                // yaz. Aşırı yazmamak için en fazla saatte bir.
                if (ctx.lastResultAtMs > 0 &&
                    ctx.lastResultAtMs - storage.state.value.config
                        .activityRecognitionSeenAtMs > 3_600_000L
                ) {
                    val at = ctx.lastResultAtMs
                    storage.update {
                        it.copy(config = it.config.copy(activityRecognitionSeenAtMs = at))
                    }
                }
                // TAZELİK KAPISI: eskiden yalnızca updatedAtMs > 0 bakılıyordu,
                // yani saatler önceki bir IN_VEHICLE okuması dedektörü
                // silahlandırabiliyordu.
                val fresh = ctx.updatedAtMs > 0 &&
                    System.currentTimeMillis() - ctx.updatedAtMs <= ACTIVITY_FRESH_MS
                if (fresh) {
                    crashDetector?.onVehicleActivity(
                        System.nanoTime(), ctx.inVehicle, ctx.confidence,
                    )
                }
                // FIRSATÇI KONUM: Günlük kipte konum kapalıdır; etkinlik
                // tanıma "araçta" derse yalnızca kapsam EKLEMEK için açılır.
                // Bu, tek yönlü otomasyonun kalbidir: hiçbir eşik gevşemez.
                if (!profile.continuousLocation) {
                    val wantsSpeed = fresh && ctx.inVehicle && ctx.confidence >= 50
                    if (wantsSpeed && !speedStreamOn) {
                        startSpeedStream()
                        logIncident(
                            IncidentEntry.KIND_SERVICE,
                            note = "Etkinlik tanima 'aracta' dedi (guven=${ctx.confidence}); " +
                                "konum akisi firsatci olarak acildi",
                        )
                    } else if (!wantsSpeed && speedStreamOn &&
                        crashDetector?.isArmed != true &&
                        System.currentTimeMillis() >= hintProbeUntilMs
                    ) {
                        stopSpeedStream()
                    }
                }
                maybeReturnToDaily()
                maybeUndoWaveRelax()
                delay(5_000)
            }
        }
    }

    /** 10 Hz'de |a| örneği tutar; 30 saniyelik pencere. */
    /**
     * ARAÇ kipinden GÜNLÜK kipine otomatik dönüş.
     *
     * Otomasyonun en değerli parçası budur ve tek yönlü kurala uyar: bu geçiş
     * duyarlılığı ARTIRIR (3.00 g → 2.60 g), bu yüzden otomatik olabilir.
     * Ters yönü — Günlük'ten Araç'a otomatik geçiş — YASAKTIR, çünkü o yön
     * duyarlılığı düşürür ve yanlış bir çıkarım kullanıcıyı sessizce sağır
     * bırakır.
     *
     * Çözdüğü sorun elle kipin en sinsi arızası: kipi değiştirmeyi unutmak.
     * O arıza sessiz ve KALICIDIR — dün akşam arabada bırakılan kip bugün
     * yürürken de yürürlüktedir ve kimse söylemez.
     */
    private fun maybeReturnToDaily() {
        if (profile.mode != ActivityMode.VEHICLE) return
        if (crashDetector?.isArmed == true) return
        val now = System.currentTimeMillis()

        val walkedAway = stepsSinceVehicle >= STEPS_TO_LEAVE_VEHICLE &&
            lastFastSpeedMs > 0L && now - lastFastSpeedMs >= QUIET_AFTER_DRIVING_MS
        val expired = modeExpiresAtMs > 0L && now >= modeExpiresAtMs

        if (!walkedAway && !expired) return

        val why = if (walkedAway) {
            "$stepsSinceVehicle adim yuruldu ve son yuksek hizdan bu yana " +
                "${(now - lastFastSpeedMs) / 60_000} dk gecti"
        } else "arac kipi 3 saat boyunca yenilenmedi"

        lifecycleScope.launch {
            storage.update {
                it.copy(config = it.config.copy(activityMode = ActivityMode.DAILY.name))
            }
            logIncident(
                IncidentEntry.KIND_SERVICE,
                note = "Arac kipinden Gunluk kipine otomatik donuldu: $why",
            )
            reloadDetection()
        }
    }

    /** 10 Hz'de |a| örneği tutar; 30 saniyelik pencere. */
    private fun recordForensicSample(x: Float, y: Float, z: Float) {
        val now = System.currentTimeMillis()
        if (now - lastForensicMs < 100) return
        lastForensicMs = now
        if (forensicWindow.size >= 300) forensicWindow.removeFirst()
        forensicWindow.addLast(kotlin.math.sqrt(x * x + y * y + z * z))
    }

    private fun register(sensor: Sensor) {
        val ok = sensorManager.registerListener(
            this, sensor, SAMPLE_PERIOD_US,
            if (sensor.fifoReservedEventCount > 0) BATCH_LATENCY_US else 0,
        )
        if (!ok) Log.w(TAG, "Sensor kaydedilemedi: ${sensor.name}")
    }

    private fun rearmSignificantMotion() {
        val sensor = significantMotionSensor ?: return
        runCatching { sensorManager.requestTriggerSensor(significantMotionListener, sensor) }
    }

    @Suppress("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LifeSaver::guard")?.apply {
                setReferenceCounted(false)
                // Zaman aşımı YOK: koruma kullanıcı kapatana kadar sürer.
                // Bunun pil bedeli (saatte ~%2) onboarding'de açıkça söylenir.
                acquire()
            }
        }.onFailure { Log.e(TAG, "Wake lock alinamadi", it) }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        val d = detector ?: return
        val t = e.timestamp
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                liveness.onSample(e.values[0], e.values[1], e.values[2], System.currentTimeMillis())
                recordForensicSample(e.values[0], e.values[1], e.values[2])
                d.onAccel(t, e.values[0], e.values[1], e.values[2])?.let(::handleOutcome)
                crashDetector?.onAccel(t, e.values[0], e.values[1], e.values[2])
                    ?.let(::handleCrashOutcome)
                if (wantsVehicleHint() &&
                    vehicleHint.onAccel(t, e.values[0], e.values[1], e.values[2])
                ) {
                    onVehicleHintFired()
                }
                if (waveMonitor.onAccel(t, e.values[0], e.values[1], e.values[2])) {
                    onWaveConfirmed()
                }
                if (waveMonitor.waveActive) lastWaveActiveMs = System.currentTimeMillis()
                syncCrashSampleRate()
            }
            Sensor.TYPE_GYROSCOPE -> {
                d.onGyro(t, e.values[0], e.values[1], e.values[2])
                crashDetector?.onGyro(t, e.values[0], e.values[1], e.values[2])
            }
            Sensor.TYPE_GRAVITY -> {
                d.onGravity(t, e.values[0], e.values[1], e.values[2])
                crashDetector?.onGravity(t, e.values[0], e.values[1], e.values[2])
                vehicleHint.onGravity(e.values[0], e.values[1], e.values[2])
            }
            Sensor.TYPE_PRESSURE -> {
                d.onPressurePa(t, e.values[0] * 100f)         // hPa -> Pa
                crashDetector?.onPressureHpa(t, e.values[0])  // hPa
            }
            Sensor.TYPE_PROXIMITY -> d.onProximity(t, e.values[0])
            Sensor.TYPE_LIGHT -> d.onLight(t, e.values[0])
            Sensor.TYPE_STEP_DETECTOR -> {
                d.onStep(t)
                vehicleHint.onStep(t)
                waveMonitor.onStep(t)
                if (stepsSinceVehicle < Int.MAX_VALUE) stepsSinceVehicle++
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ---- Olay yönetimi -----------------------------------------------------

    /**
     * Kaza algılayıcı silahlandığında/silahtan çıktığında örnekleme hızını
     * değiştirir.
     *
     * Sürekli 200 Hz pil yakar; sürekli 50 Hz ise kaza darbesinin şeklini
     * okuyamaz. Bu yüzden hız araç bağlamına bağlanır: sadece sürüş
     * sırasında 200 Hz'e çıkılır ve toplu bildirim (batching) kapatılır.
     */
    private fun syncCrashSampleRate() {
        val armed = crashDetector?.isArmed == true
        if (armed == crashArmed) return
        crashArmed = armed
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val pressure = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

        val period = if (armed) CRASH_SAMPLE_PERIOD_US else SAMPLE_PERIOD_US
        val latency = if (armed) 0 else BATCH_LATENCY_US

        sensorManager.unregisterListener(this, accel)
        sensorManager.registerListener(this, accel, period, latency)
        gyro?.let {
            sensorManager.unregisterListener(this, it)
            sensorManager.registerListener(this, it, period, latency)
        }
        pressure?.let {
            sensorManager.unregisterListener(this, it)
            sensorManager.registerListener(this, it, period, latency)
        }
        logIncident(
            IncidentEntry.KIND_SERVICE,
            note = if (armed) {
                "Arac baglami dogrulandi: kaza algilama silahlandi, ornekleme 200 Hz"
            } else {
                "Arac baglami bitti: kaza algilama silahtan cikti, ornekleme 50 Hz"
            },
        )
    }

    private fun handleCrashOutcome(outcome: CrashOutcome) {
        when (outcome) {
            is CrashOutcome.Rejected -> logIncident(
                IncidentEntry.KIND_REJECTED,
                scenario = if (vehicleMode == VehicleMode.MOTORCYCLE) {
                    Scenario.MOTORCYCLE_CRASH.name
                } else Scenario.VEHICLE_CRASH.name,
                score = outcome.score,
                note = "${outcome.reason} (${outcome.evidence.describeTurkish().joinToString(", ")})",
            )

            is CrashOutcome.ResumedDriving -> {
                // SENARYO KAPISI: bu veto YALNIZCA kaza olaylarını iptal
                // edebilir. Kapı olmadan, canlı bir DÜŞME olayı (yayanın
                // düşmesi) aracın yeniden hızlanmasıyla iptal ediliyordu —
                // birbiriyle hiç ilgisi olmayan iki olay.
                if (currentScenario != Scenario.VEHICLE_CRASH &&
                    currentScenario != Scenario.MOTORCYCLE_CRASH
                ) {
                    logIncident(
                        IncidentEntry.KIND_SERVICE,
                        note = "Surusa devam vetosu yok sayildi: canli olay " +
                            "${currentScenario.name}, kaza degil",
                    )
                    return
                }
                // Kaza algılandıktan sonra araç yeniden hızlandı: bilinçli
                // biri kullanıyor. En güçlü tek yanlış alarm elemesidir.
                logIncident(
                    IncidentEntry.KIND_CANCELLED,
                    note = "Surusa devam edildi (olaydan ${outcome.secondsAfterEvent} s sonra) " +
                        "- olay otomatik iptal edildi",
                )
                cancelIncident(userInitiated = false)
            }

            is CrashOutcome.Detected -> {
                val scenario = if (outcome.motorcycle) {
                    Scenario.MOTORCYCLE_CRASH
                } else Scenario.VEHICLE_CRASH
                val confidence = when (crashDetector?.confidenceFor(outcome.score)) {
                    DetectionConfidence.HIGH -> Confidence.HIGH
                    DetectionConfidence.MEDIUM -> Confidence.MEDIUM
                    else -> Confidence.LOW
                }
                val reason = outcome.evidence.describeTurkish()
                lastDetectionScore = outcome.score
                lastDetectionConfidence = confidence
                lastDetectionFeatures = mapOf(
                    "peakG" to outcome.evidence.peakAccelMs2 / 9.80665f,
                    "peakAccelMs2" to outcome.evidence.peakAccelMs2,
                    "gyroRadS" to outcome.evidence.peakGyroRadS,
                    "leanDeg" to outcome.evidence.leanDegrees,
                )
                lastDetectionEvidence = reason
                logIncident(
                    IncidentEntry.KIND_DETECTED,
                    scenario = scenario.name,
                    confidence = confidence.name,
                    score = outcome.score,
                    evidence = reason,
                )
                beginIncident(scenario, confidence, reason)
            }
        }
    }

    private fun handleOutcome(outcome: FallOutcome) {
        when (outcome) {
            is FallOutcome.Rejected -> {
                // Elenen adaylar da kaydedilir: eşik ayarı ancak gerçek saha
                // verisiyle yapılabilir ve kullanıcı neyin elendiğini görebilmeli.
                logIncident(
                    IncidentEntry.KIND_REJECTED,
                    scenario = Scenario.FALL.name,
                    score = outcome.score,
                    note = "${outcome.reason} (tepe ${"%.1f".format(outcome.peakG)}g)",
                )
            }
            is FallOutcome.Detected -> {
                val confidence = when {
                    outcome.score >= 6 -> Confidence.HIGH
                    outcome.score >= 4 -> Confidence.MEDIUM
                    else -> Confidence.LOW
                }
                logIncident(
                    IncidentEntry.KIND_DETECTED,
                    scenario = Scenario.FALL.name,
                    confidence = confidence.name,
                    score = outcome.score,
                    evidence = outcome.evidence.describeTurkish(),
                    note = "tepe ${"%.1f".format(outcome.peakG)}g",
                )
                lastDetectionScore = outcome.score
                lastDetectionConfidence = confidence
                lastDetectionFeatures = mapOf(
                    "peakG" to outcome.peakG,
                    "verticalVelocity" to outcome.evidence.measuredVerticalVelocityMs,
                    "gyroDegS" to outcome.evidence.measuredGyroDegS,
                    "postureDeg" to outcome.evidence.measuredPostureDeg,
                    "micromotionG" to outcome.evidence.measuredMicromotionG,
                )
                lastDetectionEvidence = outcome.evidence.describeTurkish()
                beginIncident(Scenario.FALL, confidence, outcome.evidence.describeTurkish())
            }
        }
    }

    private fun beginIncident(
        scenario: Scenario,
        confidence: Confidence,
        reason: List<String>,
        sensorDriven: Boolean = true,
    ) {
        val m = machine ?: return
        currentScenario = scenario
        incidentSensorDriven = sensorDriven
        drillActive = storage.state.value.config.drillMode

        lifecycleScope.launch { locationTracker.requestBestEffort() }

        val actions = m.onDetection(System.currentTimeMillis(), scenario, confidence)
        execute(actions)

        val plan = EscalationPlan.forScenario(scenario)
        EscalationBus.setLive(
            LiveIncident(
                scenarioTurkish = scenario.turkish,
                reason = reason,
                secondsRemaining = plan.countdownSeconds,
                drill = drillActive,
                learnable = sensorDriven,
            )
        )
        showAlertNotification(scenario)
        startTicking()
    }

    private fun startTicking() {
        tickJob?.cancel()
        tickJob = lifecycleScope.launch {
            while (isActive) {
                delay(TICK_MS)
                val m = machine ?: break
                execute(m.advanceTo(System.currentTimeMillis()))
            }
        }
    }

    /** Makinenin ürettiği emirleri uygular. */
    private fun execute(actions: List<Action>) {
        for (action in actions) {
            when (action) {
                is Action.StartAlarm -> {
                    siren.start(); torch.start(); haptics.startAlarmPattern()
                    voice.sayNow(AlertTexts.ttsToUser(EscalationPlan.forScenario(action.scenario).countdownSeconds))
                }
                Action.StopAlarm -> {
                    siren.stop(); torch.stop(); haptics.stop()
                    voice.stopSpeaking(); voice.stopCprMetronome()
                }
                is Action.AnnounceCountdown -> {
                    haptics.tick()
                    if (action.secondsRemaining <= 10 || action.secondsRemaining % 10 == 0) {
                        voice.sayNow(AlertTexts.ttsToUser(action.secondsRemaining))
                    }
                    updateLive { it.copy(secondsRemaining = action.secondsRemaining) }
                }
                Action.SpeakToBystander -> {
                    voice.enqueue(AlertTexts.ttsToBystander(alertContext()))
                    voice.enqueue(AlertTexts.ttsCprCoaching())
                    voice.startCprMetronome()
                    updateLive { it.copy(escalated = true) }
                }
                is Action.SendContactSms -> sendSms(action.to.map { it.phone }, action.text, "yakinlar")
                is Action.Send112Sms -> sendSms(listOf("112"), action.text, "112")
                is Action.CallContact -> EscalationBus.emit(action)      // aktivite yürütür
                Action.Surface112Dialer -> EscalationBus.emit(action)    // aktivite yürütür
                is Action.StartLocationBroadcast -> startLocationBroadcast(action.intervalSeconds)
                Action.StopLocationBroadcast -> Unit
                is Action.ShowMedicalCard -> EscalationBus.emit(action)
                is Action.Log -> logIncident(IncidentEntry.KIND_ACTION, note = action.note)
            }
        }
    }

    private fun startLocationBroadcast(intervalSeconds: Int) {
        lifecycleScope.launch {
            while (isActive) {
                locationTracker.requestBestEffort()?.let { fix ->
                    updateLive {
                        it.copy(
                            latitude = fix.latitude,
                            longitude = fix.longitude,
                            accuracyMeters = fix.accuracyMeters,
                        )
                    }
                }
                delay(intervalSeconds * 1000L)
            }
        }
    }

    private fun updateLive(transform: (LiveIncident) -> LiveIncident) {
        EscalationBus.live.value?.let { EscalationBus.setLive(transform(it)) }
    }

    /**
     * SMS gönderir.
     *
     * TATBİKAT MODUNDA hiçbir şey gönderilmez; metin yalnızca günlüğe yazılır.
     * Bu, uygulamanın tamamının gerçek 112 trafiği üretmeden test
     * edilebilmesini sağlar ve varsayılan davranıştır.
     */
    private fun sendSms(recipients: List<String>, text: String, label: String) {
        if (drillActive) {
            logIncident(
                IncidentEntry.KIND_DRILL, drill = true,
                note = "TATBIKAT - $label icin SMS GONDERILMEDI: $text",
            )
            return
        }
        val manager = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION") SmsManager.getDefault()
            }
        }.getOrNull()

        if (manager == null) {
            logIncident(IncidentEntry.KIND_ACTION, note = "SMS servisi yok; $label icin gonderilemedi")
            return
        }
        for (number in recipients) {
            runCatching {
                val parts = manager.divideMessage(text)
                if (parts.size > 1) {
                    manager.sendMultipartTextMessage(number, null, parts, null, null)
                } else {
                    manager.sendTextMessage(number, null, text, null, null)
                }
                logIncident(IncidentEntry.KIND_ACTION, note = "SMS gonderildi -> $number (${parts.size} parca)")
            }.onFailure {
                Log.e(TAG, "SMS gonderilemedi: $number", it)
                logIncident(IncidentEntry.KIND_ACTION, note = "SMS BASARISIZ -> $number: ${it.message}")
            }
        }
    }

    private fun showAlertNotification(scenario: Scenario) {
        val full = PendingIntent.getActivity(
            this, 2,
            Intent(this, AlertActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this, 3, Intent(this, GuardianService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, LifeSaverApp.CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${scenario.turkish} algılandı")
            .setContentText("Yardım çağrılıyor. İptal etmek için dokunun.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .setOngoing(true)
            .addAction(0, "İYİYİM - İPTAL", cancel)
            .build()

        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this)
                .notify(LifeSaverApp.NOTIFICATION_ALERT, notification)
        }.onFailure {
            // POST_NOTIFICATIONS reddedilmişse bildirim HİÇ gösterilmez ve
            // dolayısıyla tam ekran niyet de ateşlenmez. Bu sessiz bir
            // başarısızlıktır; günlüğe yazılması şart.
            logIncident(IncidentEntry.KIND_ACTION, note = "ALARM EKRANI ACILAMADI: ${it.message}")
        }
    }

    private fun runDrill() {
        drillActive = true
        lifecycleScope.launch {
            storage.appendIncident(
                IncidentEntry(System.currentTimeMillis(), IncidentEntry.KIND_DRILL, drill = true,
                    note = "Tatbikat baslatildi")
            )
        }
        if (machine == null) machine = EscalationMachine(contacts()) { alertContext() }
        beginIncident(Scenario.MANUAL, Confidence.HIGH, listOf("tatbikat"), sensorDriven = false)
    }

    private fun cancelIncident(userInitiated: Boolean = true) {
        val m = machine ?: return
        execute(m.onUserCancel(System.currentTimeMillis()))
        tickJob?.cancel()
        EscalationBus.clear()
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this)
                .cancel(LifeSaverApp.NOTIFICATION_ALERT)
        }
        logIncident(IncidentEntry.KIND_CANCELLED, note = "Kullanici iptal etti", drill = drillActive)
        if (userInitiated && incidentSensorDriven) recordCancellation()
        // Yeni bir olay algılanabilmesi için makineyi tazele.
        machine = EscalationMachine(contacts()) { alertContext() }
        drillActive = false
        // Olay sırasında ertelenmiş kip/eşik değişimi varsa şimdi uygulanır.
        if (reloadPending) {
            reloadPending = false
            reloadDetection()
        }
    }

    /**
     * İptali ham veriyle birlikte kaydeder.
     *
     * Sebep burada SORULMAZ: siren derhal susmalıdır. Kayıt UNSPECIFIED
     * olarak yazılır ve kullanıcı sebebi seçerse [applyCancelReason]
     * tamamlar. Seçmezse kayıt öğretmez — güvenli varsayılan budur.
     */
    private fun recordCancellation() {
        val mode = ActivityMode.fromName(storage.state.value.config.activityMode)
        val record = CancellationRecord(
            timestampMs = System.currentTimeMillis(),
            mode = mode.name,
            scenario = currentScenario.name,
            score = lastDetectionScore,
            confidence = lastDetectionConfidence.name,
            reason = CancelReason.UNSPECIFIED,
            features = lastDetectionFeatures,
            evidenceFired = lastDetectionEvidence,
            signalWindow = forensicWindow.toList(),
        )
        lifecycleScope.launch { storage.appendCancellation(record) }
        askCancelReasonInNotification()
    }

    /**
     * İptal sebebini bildirimden sorar.
     *
     * Neden ayrıca burada: alarm ekranındaki iptal sebebi sorar, ama
     * bildirimdeki "İYİYİM - İPTAL" düğmesi ekranı hiç açmaz. En kolay iptal
     * yolu öğrenmeye hiç veri vermeseydi, öğrenme özelliği pratikte ölü
     * olurdu. Cevap verilmezse kayıt "belirtilmedi" kalır ve hiçbir eşik
     * değişmez.
     */
    private fun askCancelReasonInNotification() {
        fun action(reason: CancelReason, requestCode: Int) = NotificationCompat.Action(
            0, reason.turkish,
            PendingIntent.getService(
                this, requestCode,
                Intent(this, GuardianService::class.java)
                    .setAction(ACTION_CANCEL_REASON)
                    .putExtra(EXTRA_REASON, reason.name),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        val notification = NotificationCompat.Builder(this, LifeSaverApp.CHANNEL_GUARD)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Ne olmuştu?")
            .setContentText("Cevabınız yanlış alarmları azaltmakta kullanılır.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Alarm durduruldu, yardım çağrılmadı. Yalnızca \"böyle bir şey " +
                        "olmadı\" cevabı eşikleri değiştirebilir; cevaplamazsanız " +
                        "hiçbir şey değişmez."
                )
            )
            .setSilent(true)
            .setAutoCancel(true)
            .setTimeoutAfter(15 * 60_000L)
            .addAction(action(CancelReason.FALSE_ALARM, 11))
            .addAction(action(CancelReason.REAL_BUT_FINE, 12))
            .addAction(action(CancelReason.ACCIDENTAL, 13))
            .build()
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this)
                .notify(LifeSaverApp.NOTIFICATION_FEEDBACK, notification)
        }
    }

    /**
     * Kullanıcı iptal sebebini seçtiğinde çalışır ve öğrenmeyi tetikler.
     *
     * Öğrenme YALNIZCA "böyle bir şey olmadı" cevabıyla olur. Diğer her
     * cevap kaydedilir ama eşiğe dokunmaz.
     */
    private fun applyCancelReason(reason: CancelReason) {
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this)
                .cancel(LifeSaverApp.NOTIFICATION_FEEDBACK)
        }
        lifecycleScope.launch {
            try {
                learnFrom(reason)
            } finally {
                // Koruma kapalıyken bildirimden gelen cevap servisi ayakta
                // tutmamalıdır: cevap kaydedilir, servis kapanır.
                if (detector == null && crashDetector == null) stopSelf()
            }
        }
    }

    private suspend fun learnFrom(reason: CancelReason) {
    storage.updateLastCancellationReason(reason) ?: return
        logIncident(IncidentEntry.KIND_CANCELLED, note = "Iptal sebebi: ${reason.turkish}")
        if (!reason.teaches) return
        if (!storage.state.value.config.learningEnabled) return

        val records = storage.readCancellations()
        val proposals = ThresholdTuner().propose(
            records = records,
            baselines = baselinesForCurrentMode(),
            nowMs = System.currentTimeMillis(),
        )
        if (proposals.isEmpty()) return

        storage.update { st ->
            val kept = st.config.tunings.filterNot { existing ->
                proposals.any { it.mode == existing.mode && it.parameter == existing.parameter }
            }
            st.copy(config = st.config.copy(tunings = kept + proposals))
        }
        proposals.forEach {
            logIncident(
                IncidentEntry.KIND_SERVICE,
                note = "Ogrenildi (${it.mode}/${it.parameter}): ${it.note}",
            )
        }
        // Yeni eşikler ancak algılayıcılar yeniden kurulunca geçerli olur.
        reloadDetection()
    }

    /**
     * Ayarlamanın tavanını belirleyen ÖZGÜN değerler.
     *
     * Kasten profilden okunur, ayarlanmış hâlden değil: aksi hâlde her tur
     * tavanı da yükseltir ve sınır anlamını yitirir.
     */
    private fun baselinesForCurrentMode(): Map<String, Float> {
        val mode = ActivityMode.fromName(storage.state.value.config.activityMode)
        val base = DetectionProfile.forMode(mode).fallConfig
        return mapOf(
            TunableParameter.FALL_IMPACT_G to base.impactG,
            TunableParameter.FALL_MIN_SCORE to base.minScore.toFloat(),
        )
    }

    private fun stopGuarding() {
        siren.stop(); torch.stop(); haptics.stop(); voice.stopSpeaking(); voice.stopCprMetronome()
        tickJob?.cancel()
        livenessJob?.cancel()
        liveness.reset()
        sensorManager.unregisterListener(this)
        significantMotionSensor?.let {
            runCatching { sensorManager.cancelTriggerSensor(significantMotionListener, it) }
        }
        stopSpeedStream()
        // Etkinlik tanıma isteği hiç kaldırılmıyordu: koruma durdurulduktan
        // sonra bile açık kalıyor ve pil yakmaya devam ediyordu.
        runCatching {
            val pending = PendingIntent.getBroadcast(
                this, 7,
                Intent(this, VehicleActivityReceiver::class.java)
                    .setAction(VehicleActivityReceiver.ACTION),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            ActivityRecognition.getClient(this).removeActivityUpdates(pending)
        }.onFailure { Log.w(TAG, "Etkinlik tanima kapatilamadi", it) }
        activityJob?.cancel()
        VehicleContext.clear()
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        detector = null
        crashDetector = null
        crashArmed = false
        machine = null
        EscalationBus.clear()
        logIncident(IncidentEntry.KIND_SERVICE, note = "Koruma durduruldu")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        voice.release()
        super.onDestroy()
    }

    // ---- Yardımcılar --------------------------------------------------------

    private fun contacts(): List<Contact> =
        storage.state.value.contacts.map { Contact(it.name, it.phone) }

    private fun alertContext(): AlertContext {
        val state = storage.state.value
        val fix: Fix? = locationTracker.lastFix.value
        return AlertContext(
            userName = state.profile.name.ifBlank { "LifeSaver kullanicisi" },
            scenario = currentScenario,
            fix = fix,
            timeText = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date()),
            callbackNumber = state.profile.phone.ifBlank { "-" },
            locationNote = state.profile.locationNote.ifBlank { null },
        )
    }

    private fun logIncident(
        kind: String,
        scenario: String = "",
        confidence: String = "",
        score: Int = 0,
        evidence: List<String> = emptyList(),
        note: String = "",
        drill: Boolean = drillActive,
    ) {
        val fix = locationTracker.lastFix.value
        lifecycleScope.launch {
            storage.appendIncident(
                IncidentEntry(
                    timestampMs = System.currentTimeMillis(),
                    kind = kind,
                    scenario = scenario,
                    confidence = confidence,
                    score = score,
                    evidence = evidence,
                    latitude = fix?.latitude,
                    longitude = fix?.longitude,
                    accuracyMeters = fix?.accuracyMeters,
                    note = note,
                    drill = drill,
                )
            )
        }
    }

    object Commands {
        fun start(context: Context) = context.startForegroundService(
            Intent(context, GuardianService::class.java).setAction(ACTION_START)
        )
        fun stop(context: Context) = context.startService(
            Intent(context, GuardianService::class.java).setAction(ACTION_STOP)
        )
        fun drill(context: Context) = context.startForegroundService(
            Intent(context, GuardianService::class.java).setAction(ACTION_DRILL)
        )
        fun cancel(context: Context) = context.startService(
            Intent(context, GuardianService::class.java).setAction(ACTION_CANCEL)
        )

        /** Kip DEĞİŞTİRİLDİKTEN ve diske YAZILDIKTAN sonra çağrılır. */
        fun modeChanged(context: Context) = context.startService(
            Intent(context, GuardianService::class.java).setAction(ACTION_SET_MODE)
        )

        fun cancelReason(context: Context, reason: CancelReason) = context.startService(
            Intent(context, GuardianService::class.java)
                .setAction(ACTION_CANCEL_REASON)
                .putExtra(EXTRA_REASON, reason.name)
        )
    }
}
