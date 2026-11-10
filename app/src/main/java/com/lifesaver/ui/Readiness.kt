package com.lifesaver.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.location.LocationManager
import android.speech.tts.TextToSpeech
import androidx.core.location.LocationManagerCompat
import com.lifesaver.actuator.VoiceStatus
import com.lifesaver.VehicleContext
import com.lifesaver.data.AppState

/** Bir hazırlık maddesinin ne kadar kritik olduğu. */
enum class Severity {
    /** Bu olmadan uygulama HİÇ çalışmaz. */
    BLOCKING,

    /** Bu olmadan uygulama çalışır ama önemli bir yeteneği kaybeder. */
    DEGRADED,

    /** İyileştirme. */
    ADVISORY,
}

data class ReadinessItem(
    val id: String,
    val title: String,
    val explanation: String,
    val severity: Severity,
    val satisfied: Boolean,
    /** Kullanıcıyı düzeltebileceği yere götüren niyet. */
    val fix: Intent? = null,
)

/**
 * Hazırlık denetimi.
 *
 * Bu ekranın varlık sebebi: bu tür uygulamaların kullanıcılarını
 * bırakmasının en yaygın nedeni, uygulamanın SESSİZCE çalışmaz durumda
 * olmasıdır — izin verilmemiştir, pil optimizasyonu servisi öldürmüştür,
 * ya da tam ekran niyet izni yoktur. Kullanıcı bunu ancak ihtiyaç anında
 * öğrenir ve o an çok geçtir.
 *
 * Bu yüzden her eksiklik açıkça, ciddiyet derecesiyle ve düzeltme yoluyla
 * gösterilir. "Yeşil" demek, gerçekten hazır demektir.
 */
object Readiness {

    fun evaluate(context: Context, state: AppState): List<ReadinessItem> = buildList {

        add(
            ReadinessItem(
                id = "profil",
                title = "Kimlik ve geri arama numarası",
                explanation = "112'ye gönderilen mesajda geri arama numarası bulunması " +
                    "zorunludur; adınız olmadan yakınlarınız kimden mesaj geldiğini anlayamaz.",
                severity = Severity.BLOCKING,
                satisfied = state.profile.isComplete,
            )
        )

        add(
            ReadinessItem(
                id = "kisiler",
                title = "Acil durum kişileri",
                explanation = "Otomatik olarak ulaşılabilen TEK kanal budur. " +
                    "112'yi uygulama sizin yerinize arayamaz; yakınlarınızı arayabilir.",
                severity = Severity.BLOCKING,
                satisfied = state.contacts.isNotEmpty(),
            )
        )

        add(
            permissionItem(
                context,
                id = "konum",
                permission = Manifest.permission.ACCESS_FINE_LOCATION,
                title = "Konum izni",
                explanation = "Konum olmadan gönderilen bir yardım çağrısı işe yaramaz. " +
                    "Türkiye'de 112 otomatik konum almaz; koordinatı mesajın içine biz koyarız.",
                severity = Severity.BLOCKING,
            )
        )

        // Cihaz genelinde konum servisi. İZİN vermek yetmez: sistem anahtarı
        // kapalıysa hiçbir konum API'si çalışmaz, ön plan servisi 'location'
        // tipini alamaz ve sürüş kipinde hız akışı hiç başlamaz.
        val locationOn = runCatching {
            val lm = context.getSystemService(LocationManager::class.java)
            lm != null && LocationManagerCompat.isLocationEnabled(lm)
        }.getOrDefault(false)
        add(
            ReadinessItem(
                id = "konumservisi",
                title = "Cihaz konum servisi",
                explanation = if (locationOn) {
                    "Konum servisi açık."
                } else {
                    "Cihazın konum servisi KAPALI. İzin vermeniz yetmez: bu anahtar " +
                        "kapalıyken konum hiç alınamaz, yardım çağrısı koordinatsız " +
                        "gider ve sürüş kipinde kaza algılama hızdan silahlanamaz."
                },
                severity = Severity.BLOCKING,
                satisfied = locationOn,
                fix = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
            )
        )

        add(
            permissionItem(
                context,
                id = "sms",
                permission = Manifest.permission.SEND_SMS,
                title = "SMS izni",
                explanation = "Yakınlarınıza ve 112'ye konumunuzu göndermenin yolu budur. " +
                    "İnternet olmasa da çalışır.",
                severity = Severity.BLOCKING,
            )
        )

        add(
            permissionItem(
                context,
                id = "arama",
                permission = Manifest.permission.CALL_PHONE,
                title = "Arama izni",
                explanation = "Yakınlarınızı otomatik aramak için gerekir.",
                severity = Severity.DEGRADED,
            )
        )

        add(
            permissionItem(
                context,
                id = "aktivite",
                permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Manifest.permission.ACTIVITY_RECOGNITION
                } else null,
                title = "Hareket algılama izni",
                explanation = "Telefonun taşınıp taşınmadığını anlamak için adım algılayıcı " +
                    "kullanılır. Bu izin olmadan 'masadaki telefon düştü' türü yanlış " +
                    "alarmları eleme yeteneği zayıflar.",
                severity = Severity.DEGRADED,
            )
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(
                permissionItem(
                    context,
                    id = "bildirim",
                    permission = Manifest.permission.POST_NOTIFICATIONS,
                    title = "Bildirim izni",
                    explanation = "Bu izin yoksa alarm bildirimi HİÇ gösterilmez ve " +
                        "dolayısıyla tam ekran alarm da açılmaz. Sessiz bir arıza olur.",
                    severity = Severity.BLOCKING,
                )
            )
        }

        // Tam ekran niyet: API 34+ varsayılan olarak yalnızca arama/alarm
        // uygulamalarına verilir. Bu olmadan kilitli ekranda alarm açılamaz.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val granted = nm?.canUseFullScreenIntent() == true
            add(
                ReadinessItem(
                    id = "tamekran",
                    title = "Kilitli ekranda alarm izni",
                    explanation = "Bu izin olmadan telefon kilitliyken alarm ekranı " +
                        "açılamaz — yalnızca bildirim görünür. Yakınlarınızı arama ve " +
                        "112 çeviricisini açma adımları da bu ekrandan yürütülür.",
                    severity = Severity.BLOCKING,
                    satisfied = granted,
                    fix = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                        .setData(Uri.fromParts("package", context.packageName, null)),
                )
            )
        }

        // Pil optimizasyonu muafiyeti.
        val pm = context.getSystemService(PowerManager::class.java)
        val ignoringBattery = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        add(
            ReadinessItem(
                id = "pil",
                title = "Pil optimizasyonu muafiyeti",
                explanation = "Bu muafiyet olmadan sistem, koruma servisini saatler " +
                    "içinde öldürebilir ve siz bunu fark etmezsiniz.",
                severity = Severity.BLOCKING,
                satisfied = ignoringBattery,
                fix = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}")),
            )
        )

        // Rahatsız Etmeyin erişimi.
        val notificationPolicy = runCatching {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }.getOrDefault(false)
        add(
            ReadinessItem(
                id = "dnd",
                title = "Bildirimler etkin",
                explanation = "Rahatsız Etmeyin 'tam sessizlik' modundaysa siren " +
                    "erişilebilirlik kanalından çalmaya devam eder, ama bildirimler " +
                    "kapalıysa alarm ekranı açılamaz.",
                severity = Severity.DEGRADED,
                satisfied = notificationPolicy,
                fix = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        )

        // Sesli anons katmanı.
        if (VoiceStatus.checked) {
            add(
                ReadinessItem(
                    id = "ses",
                    title = if (!VoiceStatus.engineReady) "Sesli anons motoru" else "Türkçe ses paketi",
                    explanation = when {
                        !VoiceStatus.engineReady ->
                            "Cihazda çalışan bir metin okuma motoru bulunamadı. Siren ve fener " +
                                "çalışmaya devam eder, ama çevredeki insanlara Türkçe anons " +
                                "yapılamaz ve kalp masajı yönergesi okunamaz."
                        !VoiceStatus.turkishVoice ->
                            "Metin okuma motoru çalışıyor ama TÜRKÇE ses verisi kurulu değil. " +
                                "Anonslar yanlış telaffuzla okunabilir. Ayarlar > Erişilebilirlik > " +
                                "Metin okuma bölümünden Türkçe ses verisini indirin."
                        else -> "Türkçe sesli anons hazır."
                    },
                    severity = Severity.DEGRADED,
                    satisfied = VoiceStatus.engineReady && VoiceStatus.turkishVoice,
                    fix = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),
                )
            )
        }

        // TEŞHİS: etkinlik tanıma gerçekten sonuç üretiyor mu?
        // Günlük kipte kaza algılamanın otomatik silahlanması buna bağlı
        // olduğu için, sessizce ölmüş bir sınıflandırıcı görünmez bir
        // koruma boşluğu demektir.
        val arSeen = VehicleContext.resultCount > 0
        val arSeenBefore = state.config.activityRecognitionSeenAtMs > 0
        val arAgeMin = if (VehicleContext.lastResultAtMs > 0) {
            (System.currentTimeMillis() - VehicleContext.lastResultAtMs) / 60_000
        } else -1L
        add(
            ReadinessItem(
                id = "etkinliktanima",
                title = "Etkinlik tanıma (araç algılama)",
                explanation = when {
                    arSeen -> "Çalışıyor. Son sonuç ${arAgeMin} dk önce: " +
                        "\"${VehicleContext.lastTopActivity}\" (güven " +
                        "%${VehicleContext.lastTopConfidence}). Bu oturumda " +
                        "${VehicleContext.resultCount} sonuç alındı."
                    arSeenBefore -> "Daha önce çalıştı ama bu oturumda henüz " +
                        "sonuç gelmedi. İlk sonuç koruma başladıktan sonra " +
                        "birkaç dakika sürebilir."
                    else -> "HENÜZ HİÇ SONUÇ ALINMADI. Bu normal olabilir " +
                        "(koruma yeni başladıysa) ama kalıcıysa sınıflandırıcı " +
                        "çalışmıyor demektir: Günlük kipte araca bindiğinizin " +
                        "otomatik anlaşılması buna bağlıdır. Sandbox'lı Play " +
                        "Services kurulumlarında fiziksel etkinlik izni ve pil " +
                        "kısıtlaması muafiyeti elle verilmezse sessizce ölür."
                },
                severity = Severity.DEGRADED,
                satisfied = arSeen || arSeenBefore,
                fix = null,
            )
        )

        // Kaza algılama artık HER kipte kuruludur ve kanıtla silahlanır;
        // dolayısıyla hız akışının çalışıp çalışmadığı her kipte önemlidir.
        val activityMode = com.lifesaver.detect.ActivityMode.fromName(state.config.activityMode)
        val profile = com.lifesaver.detect.DetectionProfile.forMode(activityMode)
        add(
            ReadinessItem(
                id = "kazakipi",
                title = "Kaza algılama (${activityMode.turkish} kipi)",
                explanation = when {
                    !locationOn ->
                        "Konum servisi KAPALI olduğu için hız akışı yok. Kaza " +
                            "algılama yalnızca etkinlik tanımayla silahlanabilir " +
                            "ve hız çöküşü kanıtı hiç toplanamaz."
                    profile.continuousLocation ->
                        "Araç kipi açık: konum sürekli izlenir, böylece tünelde " +
                            "ve kapalı otoparkta da kaza algılama hazırdır. " +
                            "Silahlanınca ivmeölçer 200 Hz'e çıkar."
                    else ->
                        "Kaza algılama bu kipte de kuruludur ama konum kapalıdır. " +
                            "Etkinlik tanıma 'araçta' derse konum kendiliğinden " +
                            "açılır; bu 30-60 saniye gecikebilir. Araca binerken " +
                            "kipi Araç yapmak bu gecikmeyi kaldırır."
                },
                severity = Severity.DEGRADED,
                satisfied = locationOn,
                fix = if (locationOn) null else Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
            )
        )

        add(
            ReadinessItem(
                id = "tatbikat",
                title = "Tatbikat modu",
                explanation = if (state.config.drillMode) {
                    "AÇIK. Zincirin tamamı çalışır ama hiçbir gerçek SMS gönderilmez ve " +
                        "hiç kimse aranmaz. En az bir tatbikat yapıp bunu kapatmadan " +
                        "uygulama sizi gerçekten koruyamaz."
                } else {
                    "KAPALI. Bir olay algılanırsa gerçek mesajlar gönderilecek."
                },
                severity = Severity.ADVISORY,
                satisfied = !state.config.drillMode,
            )
        )
    }

    private fun permissionItem(
        context: Context,
        id: String,
        permission: String?,
        title: String,
        explanation: String,
        severity: Severity,
    ): ReadinessItem {
        val satisfied = permission == null ||
            ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
        return ReadinessItem(id, title, explanation, severity, satisfied)
    }

    /** Engelleyici eksik yoksa koruma anlamlı biçimde çalışabilir. */
    fun canGuard(items: List<ReadinessItem>): Boolean =
        items.none { it.severity == Severity.BLOCKING && !it.satisfied && it.id != "tatbikat" }

    fun score(items: List<ReadinessItem>): Int {
        val relevant = items.filter { it.severity != Severity.ADVISORY }
        if (relevant.isEmpty()) return 0
        return (relevant.count { it.satisfied } * 100) / relevant.size
    }
}
