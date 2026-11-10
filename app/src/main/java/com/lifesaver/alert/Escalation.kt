package com.lifesaver.alert

/** Algılamanın ne kadar güvenilir olduğu. 112 kanalı yalnızca [HIGH] ile açılır. */
enum class Confidence { LOW, MEDIUM, HIGH }

/** Bir acil durum kişisi. */
data class Contact(val name: String, val phone: String)

/**
 * Durum makinesinin ürettiği yan etkisiz emirler.
 * Android katmanı bunları çalıştırır; makine hiçbir şeyi kendisi yapmaz.
 * Bu ayrım, tüm tırmanma mantığının sahte saatle JVM'de test edilebilmesini sağlar.
 */
sealed interface Action {
    data class StartAlarm(val scenario: Scenario) : Action
    data object StopAlarm : Action
    data class AnnounceCountdown(val secondsRemaining: Int) : Action
    data class SendContactSms(val to: List<Contact>, val text: String) : Action
    data class CallContact(val contact: Contact) : Action
    data class Send112Sms(val text: String) : Action
    data object Surface112Dialer : Action
    data object SpeakToBystander : Action
    data class StartLocationBroadcast(val intervalSeconds: Int) : Action
    data object StopLocationBroadcast : Action
    data class ShowMedicalCard(val reason: String) : Action
    data class Log(val note: String) : Action
}

enum class EscalationState {
    IDLE,

    /** Alarm çalıyor, kullanıcı iptal edebilir. */
    COUNTDOWN,

    /** Yakınlara haber verildi, aramalar sürüyor. */
    ALERTING_CONTACTS,

    /** 112 kanalı kullanıldı. */
    EMERGENCY,

    /** Kullanıcı iptal etti — yanlış alarmdı. */
    CANCELLED,

    /** Bir yakın "yola çıktım" onayı verdi. */
    RESOLVED,
}

/**
 * Senaryoya göre zamanlama planı. Saniye cinsindendir ve algılamanın
 * karar anını (t=0) referans alır.
 */
data class EscalationPlan(
    /** İptal penceresi. Denizde kısadır: sürüklenme her saniye telafi edilemez hale gelir. */
    val countdownSeconds: Int,
    /** Yakınlara SMS'ten sonra ilk aramaya kadar geçen süre. */
    val firstCallDelaySeconds: Int = 5,
    /** Aramalar arası bekleme. Cevaplanıp cevaplanmadığı ANLAŞILAMAZ, o yüzden sabit. */
    val betweenCallsSeconds: Int = 25,
    /** 112 kanalının açıldığı an (t=0'dan itibaren). */
    val emergencyChannelSeconds: Int = 150,
    /** Yakınlara SMS tekrar aralığı. */
    val contactSmsRepeatSeconds: Int = 300,
    /** Toplam SMS turu sayısı — Android'in SMS hız sınırına karşı sınırlıdır. */
    val maxContactSmsRounds: Int = 4,
    /** Olay sırasında konum yayın aralığı. */
    val broadcastIntervalSeconds: Int = 30,
) {
    companion object {
        /**
         * Senaryo ve GÜVEN düzeyine göre plan.
         *
         * Kazada geri sayım güvene bağlıdır ve bu kasıtlıdır: ivmeölçeri
         * doyuran + hava yastığı açılan bir kazada kullanıcının cevap verme
         * ihtimali düşüktür, beklemek zarar verir (Apple 10 s kullanır).
         * Orta güvenli bir olayda ise yanlış alarm ihtimali yüksektir ve
         * kullanıcıya iptal için bol süre verilir (Google/Detecht 60 s).
         */
        fun forScenario(
            scenario: Scenario,
            confidence: Confidence = Confidence.HIGH,
        ): EscalationPlan = when (scenario) {
            Scenario.VEHICLE_CRASH, Scenario.MOTORCYCLE_CRASH -> EscalationPlan(
                countdownSeconds = if (confidence == Confidence.HIGH) 10 else 60,
                emergencyChannelSeconds = if (confidence == Confidence.HIGH) 90 else 180,
            )
            // Denizde sürüklenme birikir; pencere kısa.
            Scenario.MARINE_OVERBOARD -> EscalationPlan(
                countdownSeconds = 20,
                emergencyChannelSeconds = 90,
            )
            // Yanıtsızlık en zayıf sinyaldir; kullanıcıya en uzun süre verilir.
            Scenario.NO_RESPONSE -> EscalationPlan(
                countdownSeconds = 60,
                emergencyChannelSeconds = 240,
            )
            // Kullanıcı bilerek bastı; beklemenin anlamı yok.
            Scenario.MANUAL -> EscalationPlan(
                countdownSeconds = 0,
                emergencyChannelSeconds = 60,
            )
            else -> EscalationPlan(countdownSeconds = 30)
        }
    }
}

/**
 * Tırmanma durum makinesi.
 *
 * Saf Kotlin'dir ve saati dışarıdan alır: tüm zamanlama davranışı sahte saatle
 * milisaniye hassasiyetinde test edilebilir. Gerçek bir acil durumu
 * beklemeden 150. saniyede ne olduğunu doğrulayabilmek şarttır.
 *
 * Hukuki kısıtlar burada KOD olarak yaşar:
 *  - 112 kanalı olay başına BİR KEZ kullanılır ([emergencyChannelUsed]).
 *    Kabahatler Kanunu m.42/A "ısrarla çağrı bırakma" fiilini ayrıca
 *    cezalandırır; otomatik yeniden arama yasaktır.
 *  - 112 kanalı yalnızca güven [Confidence.HIGH] ise açılır.
 *  - Yakınlara SMS turu sayısı sınırlıdır; Android'in SMS hız sınırı
 *    (yalnızca varsayılan SMS uygulamasına muafiyet tanır) aşılırsa
 *    sistem kullanıcı onayı ister ve baygın kullanıcı bunu veremez.
 */
class EscalationMachine(
    private val contacts: List<Contact>,
    private val contextProvider: () -> AlertContext,
) {
    var state: EscalationState = EscalationState.IDLE
        private set

    private var scenario: Scenario = Scenario.FALL
    private var confidence: Confidence = Confidence.LOW
    private var plan: EscalationPlan = EscalationPlan(30)

    private var startedAtMs = 0L
    private var lastTickMs = 0L

    private var emergencyChannelUsed = false
    private var contactSmsRounds = 0
    private var nextContactSmsAtMs = Long.MAX_VALUE
    private var callsPlaced = 0
    private var nextCallAtMs = Long.MAX_VALUE
    private var lastAnnouncedSecond = -1

    /** t=0'dan bu yana geçen saniye. */
    private fun elapsed(nowMs: Long): Int = ((nowMs - startedAtMs) / 1000L).toInt()

    /** Algılama tetiklendi. Zincir başlar. */
    fun onDetection(nowMs: Long, scenario: Scenario, confidence: Confidence): List<Action> {
        if (state != EscalationState.IDLE) {
            return listOf(Action.Log("Zaten aktif olay var, yeni tetikleme yok sayildi"))
        }
        this.scenario = scenario
        this.confidence = confidence
        this.plan = EscalationPlan.forScenario(scenario, confidence)
        startedAtMs = nowMs
        lastTickMs = nowMs
        state = EscalationState.COUNTDOWN
        lastAnnouncedSecond = -1

        return buildList {
            add(Action.Log("Algilandi: ${scenario.turkish}, guven=$confidence"))
            add(Action.StartAlarm(scenario))
            add(Action.StartLocationBroadcast(plan.broadcastIntervalSeconds))
            if (plan.countdownSeconds > 0) add(Action.AnnounceCountdown(plan.countdownSeconds))
        }
    }

    /**
     * Zamanı ilerletir ve bu aralıkta yapılması gerekenleri döndürür.
     * Aynı [nowMs] ile iki kez çağrılması yan etki üretmez.
     */
    fun advanceTo(nowMs: Long): List<Action> {
        if (state != EscalationState.COUNTDOWN && state != EscalationState.ALERTING_CONTACTS &&
            state != EscalationState.EMERGENCY
        ) return emptyList()
        if (nowMs <= lastTickMs) return emptyList()
        lastTickMs = nowMs

        val actions = mutableListOf<Action>()
        val t = elapsed(nowMs)

        // --- Geri sayım ---
        if (state == EscalationState.COUNTDOWN) {
            val remaining = plan.countdownSeconds - t
            if (remaining > 0) {
                // Her saniyede bir değil; son 10 saniye ve 10'un katlarında duyur.
                if (remaining != lastAnnouncedSecond && (remaining <= 10 || remaining % 10 == 0)) {
                    lastAnnouncedSecond = remaining
                    actions += Action.AnnounceCountdown(remaining)
                }
                return actions
            }
            // Geri sayım bitti: yakınlara haber ver.
            state = EscalationState.ALERTING_CONTACTS
            actions += Action.Log("Geri sayim bitti, yakinlara haber veriliyor")
            actions += Action.SpeakToBystander
            actions += Action.ShowMedicalCard("Kullanici yanit vermedi")
            actions += sendContactSms(nowMs)
            nextCallAtMs = nowMs + plan.firstCallDelaySeconds * 1000L
        }

        // --- Yakınları arama ---
        if (nowMs >= nextCallAtMs && callsPlaced < contacts.size) {
            actions += Action.CallContact(contacts[callsPlaced])
            callsPlaced++
            nextCallAtMs = if (callsPlaced < contacts.size) {
                nowMs + plan.betweenCallsSeconds * 1000L
            } else {
                Long.MAX_VALUE
            }
        }

        // --- Yakınlara SMS tekrarı (sınırlı) ---
        if (nowMs >= nextContactSmsAtMs && contactSmsRounds < plan.maxContactSmsRounds) {
            actions += sendContactSms(nowMs)
        }

        // --- 112 kanalı: son basamak, tek sefer ---
        if (!emergencyChannelUsed && t >= plan.emergencyChannelSeconds) {
            emergencyChannelUsed = true
            state = EscalationState.EMERGENCY
            if (confidence == Confidence.HIGH) {
                actions += Action.Log("112 kanali aciliyor (tek sefer, guven=HIGH)")
                actions += Action.Send112Sms(AlertTexts.emergencySms112(contextProvider()))
            } else {
                actions += Action.Log("Guven $confidence: 112'ye otomatik SMS GONDERILMEDI")
            }
            // Çevirici her iki durumda da yüzeye çıkarılır: buna basacak olan
            // bir insandır ve karar onundur.
            actions += Action.Surface112Dialer
        }

        return actions
    }

    private fun sendContactSms(nowMs: Long): Action {
        contactSmsRounds++
        nextContactSmsAtMs = if (contactSmsRounds < plan.maxContactSmsRounds) {
            nowMs + plan.contactSmsRepeatSeconds * 1000L
        } else {
            Long.MAX_VALUE
        }
        return Action.SendContactSms(contacts, AlertTexts.contactSms(contextProvider()))
    }

    /**
     * Kullanıcı iptal etti.
     *
     * Android katmanı bunu yalnızca BASILI TUTMA hareketiyle çağırmalıdır:
     * cepteki tek bir dokunuş ya da sudaki hayalet dokunuş alarmı susturmamalıdır.
     */
    fun onUserCancel(nowMs: Long): List<Action> {
        if (state == EscalationState.IDLE) return emptyList()
        val wasEmergency = emergencyChannelUsed
        state = EscalationState.CANCELLED
        return buildList {
            add(Action.StopAlarm)
            add(Action.StopLocationBroadcast)
            add(Action.Log("Kullanici iptal etti (t+${elapsed(nowMs)}s)"))
            if (wasEmergency) {
                // Yakınlar zaten haber aldı; yanlış alarm olduğunu bilmeleri gerekir.
                add(Action.SendContactSms(contacts, "BILGI: LifeSaver yanlis alarm. Iyiyim."))
            }
        }
    }

    /** Bir yakın "yola çıktım" dedi: zincir durur, 112 kanalı açılmaz. */
    fun onContactConfirmed(nowMs: Long, contact: Contact): List<Action> {
        if (state == EscalationState.IDLE || state == EscalationState.CANCELLED) return emptyList()
        state = EscalationState.RESOLVED
        return listOf(
            Action.Log("${contact.name} yardima geliyor (t+${elapsed(nowMs)}s)"),
            Action.StopAlarm,
        )
        // Konum yayını RESOLVED durumunda bilerek DURDURULMAZ:
        // gelen kişinin kullanıcıyı bulabilmesi gerekir.
    }

    /** Denetim ve hata ayıklama için anlık durum. */
    fun snapshot(nowMs: Long) = mapOf(
        "durum" to state.name,
        "senaryo" to scenario.name,
        "guven" to confidence.name,
        "gecen_saniye" to elapsed(nowMs),
        "112_kullanildi" to emergencyChannelUsed,
        "sms_turu" to contactSmsRounds,
        "arama_sayisi" to callsPlaced,
    )
}
