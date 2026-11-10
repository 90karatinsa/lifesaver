package com.lifesaver.alert

/** Algılanan olay türü. */
enum class Scenario(val turkish: String, val smsPhrase: String) {
    FALL("Düşme", "dustu"),
    VEHICLE_CRASH("Araç kazası", "arac kazasi gecirdi"),
    MOTORCYCLE_CRASH("Motosiklet kazası", "motosiklet kazasi gecirdi"),
    MARINE_OVERBOARD("Denize düşme", "denize dustu"),
    NO_RESPONSE("Yanıt yok", "yanit vermiyor"),
    MANUAL("Manuel yardım çağrısı", "yardim istedi"),
}

/** Bir uyarı mesajını kurmak için gereken her şey. */
data class AlertContext(
    val userName: String,
    val scenario: Scenario,
    val fix: Fix?,
    /** "14:32" — çağıran taraf Locale.ROOT ile biçimler. */
    val timeText: String,
    /** Kullanıcının kendi numarası; 112 mesajında geri arama olarak zorunludur. */
    val callbackNumber: String,
    /** Kayıtlı adrese yakınsa eklenen tarif ("Blok B, 3. kat, daire 7"). */
    val locationNote: String? = null,
)

/**
 * Uyarı metinleri.
 *
 * İki ayrı dil rejimi vardır ve karıştırılmamalıdır:
 *  - SMS metinleri ASCII'dir (bkz. [TurkishAscii]) — kapasite kararıdır.
 *  - Sesli (TTS) metinler tam Türkçedir; seste kapasite sorunu yoktur ve
 *    "yanit" yerine "yanıt" demek anlaşılırlığı artırır.
 */
object AlertTexts {

    /**
     * Yakınlara giden SMS.
     *
     * Sıralama kasıtlıdır: kritik bilgi (ne oldu + koordinat + harita) ilk
     * parçaya sığacak şekilde başa konur. Çok parçalı bir SMS'in ikinci
     * parçası teslim edilmeyebilir.
     */
    fun contactSms(ctx: AlertContext): String {
        val sb = StringBuilder()
        sb.append("ACIL: ")
        sb.append(TurkishAscii.convert(ctx.userName))
        sb.append(' ').append(ctx.scenario.smsPhrase).append(". ")

        if (ctx.fix != null) {
            sb.append(Coordinates.decimal(ctx.fix))
            val acc = Coordinates.accuracyText(ctx.fix)
            if (acc.isNotEmpty()) sb.append(' ').append(acc)
            sb.append(' ').append(Coordinates.mapsUrl(ctx.fix)).append(' ')
        } else {
            sb.append("KONUM ALINAMADI. ")
        }

        sb.append(ctx.timeText).append(". LifeSaver otomatik uyarisi.")

        ctx.locationNote?.let { sb.append(' ').append(TurkishAscii.convert(it)) }
        return sb.toString()
    }

    /**
     * 112'ye giden SMS.
     *
     * Hukuki zorunluluklar (Kabahatler Kanunu m.42/A bağlamında):
     *  1. Mesaj MAKİNE kaynaklı olduğunu ilk cümlede söyler — operatörün
     *     "bu bir bot" deyip kapatması, "asılsız ihbar" tutanağından iyidir.
     *  2. Geri arama numarası verir.
     *  3. Bu kanal olay başına BİR KEZ kullanılır; ısrarlı çağrı ayrıca
     *     cezalandırılan bir fiildir. Tekrarı [EscalationPlan] engeller.
     *
     * Not: 112'ye gönderilen SMS'te otomatik konum YOKTUR; koordinat
     * gövdede olmak zorundadır.
     */
    fun emergencySms112(ctx: AlertContext): String {
        val sb = StringBuilder()
        sb.append("ACIL YARDIM. ")

        if (ctx.fix != null) {
            sb.append(Coordinates.decimal(ctx.fix))
            val acc = Coordinates.accuracyText(ctx.fix)
            if (acc.isNotEmpty()) sb.append(' ').append(acc)
            sb.append(' ').append(Coordinates.mapsUrl(ctx.fix)).append(' ')
        } else {
            sb.append("Konum alinamadi. ")
        }

        sb.append(TurkishAscii.convert(ctx.userName)).append(' ')
            .append(ctx.scenario.smsPhrase)
            .append(", bilinci kapali olabilir. ")

        ctx.locationNote?.let { sb.append(TurkishAscii.convert(it)).append(". ") }

        sb.append("Bu mesaj LifeSaver uygulamasi tarafindan OTOMATIK gonderildi. ")
            .append("Geri arama: ").append(ctx.callbackNumber).append(". ")
            .append("Saat ").append(ctx.timeText).append(".")

        return sb.toString()
    }

    /** Geri sayım sırasında kullanıcıya: iptal etme şansı. */
    fun ttsToUser(secondsRemaining: Int): String =
        "İyi misiniz? $secondsRemaining saniye içinde yanıt vermezseniz " +
            "yardım çağıracağım. İptal etmek için ekrana basılı tutun."

    /** Çevredeki insanlara: ne olduğunu ve ne yapmaları gerektiğini söyler. */
    fun ttsToBystander(ctx: AlertContext): String = buildString {
        append("Dikkat. Bu kişinin bilinci kapalı olabilir. ")
        append("Adı ${ctx.userName}. ")
        append("Lütfen yardım edin. ")
        append("Yüz on iki, acil çağrı merkezini arayın. ")
        if (ctx.fix != null) {
            append("Bulunduğunuz konum ekranda yazıyor. ")
        }
        append("Telefonu hoparlöre alın ve ekrandaki yönergeleri izleyin.")
    }

    /**
     * Temel yaşam desteği yönergesi (ERC 2021).
     *
     * Sayılar kılavuzdan birebir alınmıştır ve değiştirilmemelidir.
     * Metronom 110 vuruş/dakika ile çalışır (100-120 aralığının ortası).
     */
    fun ttsCprCoaching(): String =
        "Kişi yanıt vermiyor ve normal nefes almıyorsa kalp masajına başlayın. " +
            "Ellerinizi göğsün ortasına koyun. " +
            "Dakikada yüz ile yüz yirmi bası hızında, " +
            "en az beş santimetre, en fazla altı santimetre derinliğinde bastırın. " +
            "Her basıdan sonra göğsün tamamen geri kalkmasına izin verin. " +
            "Metronomu takip edin."

    /**
     * Bir arama bağlandıysa hoparlörden okunan metin.
     *
     * DÜRÜSTLÜK NOTU: Bu ses karşı tarafa yalnızca AKUSTİK olarak,
     * hoparlörden mikrofona ulaşır. Uygulamanın çağrı yukarı-bağına ses
     * enjekte etmesi mümkün değildir ve cihazın yankı bastırıcısı bu sesle
     * aktif olarak mücadele eder. Bu yüzden metin kısa, yavaş ve
     * TEKRARLANABİLİR tutulur; en kritik bilgi (konum) başa konur.
     */
    fun ttsToOperator(ctx: AlertContext): String = buildString {
        append("Bu bir otomatik acil durum bildirimidir. ")
        if (ctx.fix != null) {
            append("Konum: ")
            append(spellCoordinates(ctx.fix))
            append(". ")
        }
        append("${ctx.userName}, ${ctx.scenario.turkish.lowercase()} nedeniyle yanıt vermiyor. ")
        append("Geri arama numarası: ${spellDigits(ctx.callbackNumber)}. ")
        append("Tekrar ediyorum. ")
    }

    /**
     * Koordinatı rakam rakam, duraklamalarla okutur.
     * "40.9735" gibi bir sayıyı TTS'in tek seferde doğru okuması güvenilmezdir;
     * telefonda bozuk hatta dinleyen bir operatör için hane hane okumak şarttır.
     */
    internal fun spellCoordinates(fix: Fix): String {
        val text = Coordinates.decimal(fix)
        val (lat, lon) = text.split(",")
        return "enlem ${spellDigits(lat)}, boylam ${spellDigits(lon)}"
    }

    internal fun spellDigits(value: String): String =
        value.map { ch ->
            when (ch) {
                '.' -> "virgül"
                '+' -> "artı"
                '-' -> "eksi"
                ' ' -> ""
                else -> ch.toString()
            }
        }.filter { it.isNotEmpty() }.joinToString(" ")
}
