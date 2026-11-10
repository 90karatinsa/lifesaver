package com.lifesaver.alert

import java.util.Locale
import kotlin.math.abs

/** Tek bir konum tespiti. */
data class Fix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val ageSeconds: Int = 0,
)

/**
 * Koordinat biçimleme.
 *
 * DİKKAT: Her biçimleme çağrısı [Locale.ROOT] kullanır. Türkçe yerelde
 * varsayılan biçimleyici ondalık ayırıcı olarak VİRGÜL üretir ("40,9735") ve
 * bu, "enlem,boylam" dizgisini ayrıştırılamaz hale getirip harita bağlantısını
 * sessizce bozar. Bu dosyadaki hiçbir yerde `"%f".format(x)` (yerel duyarlı
 * biçim) kullanılmaz.
 */
object Coordinates {

    /** Ondalık derece — makinelerin ve harita bağlantılarının biçimi. */
    fun decimal(fix: Fix): String =
        String.format(Locale.ROOT, "%.6f,%.6f", fix.latitude, fix.longitude)

    /**
     * Derece + ondalık dakika — DENİZCİLİK standardı.
     * Sahil Güvenlik ve MRCC bu biçimi bekler; ondalık dereceyi telsizde
     * okumak hataya açıktır.
     */
    fun degreesDecimalMinutes(fix: Fix): String {
        fun part(value: Double, positive: Char, negative: Char, degreeWidth: Int): String {
            val hemisphere = if (value >= 0) positive else negative
            val a = abs(value)
            var degrees = a.toInt()
            var minutes = (a - degrees) * 60.0
            // 59.9999' -> 60.000' yuvarlanmasını derece taşımasına çevir.
            if (minutes >= 59.9995) { minutes = 0.0; degrees += 1 }
            return String.format(
                Locale.ROOT, "%0${degreeWidth}d° %06.3f' %c", degrees, minutes, hemisphere,
            )
        }
        return part(fix.latitude, 'N', 'S', 2) + "  " + part(fix.longitude, 'E', 'W', 3)
    }

    /**
     * SMS'te tıklanabilir harita bağlantısı.
     * `geo:` URI çoğu SMS istemcisinde tıklanabilir DEĞİLDİR; https kullanılır.
     */
    fun mapsUrl(fix: Fix): String =
        String.format(Locale.ROOT, "https://maps.google.com/?q=%.6f,%.6f", fix.latitude, fix.longitude)

    /** Doğruluk yarıçapı, insan diliyle. */
    fun accuracyText(fix: Fix): String = when {
        fix.accuracyMeters <= 0f -> ""
        fix.accuracyMeters < 1000f -> "+-${fix.accuracyMeters.toInt()}m"
        else -> "+-${(fix.accuracyMeters / 1000f).toInt()}km"
    }
}

/**
 * GSM 03.38 7-bit varsayılan alfabesi.
 *
 * Neden önemli: tek bir Türkçe "ı", "ş" veya "ğ" karakteri MESAJIN TAMAMINI
 * UCS-2'ye düşürür ve sınır 160 karakterden 70'e iner — yani acil mesajın
 * koordinatı ikinci parçaya taşınıp kaybolabilir. Türkiye'nin ulusal
 * kaydırma tablosu cihaz kaynaklarından okunur ve uygulama kodundan
 * etkinleştirilemez, dolayısıyla ona güvenilemez.
 */
object Gsm7 {

    private const val BASIC =
        "@£$¥èéùìòÇ\nØø\rÅå" +
        "Δ_ΦΓΛΩΠΨΣΘΞ" +
        "ÆæßÉ" +
        " !\"#¤%&'()*+,-./0123456789:;<=>?" +
        "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§" +
        "¿abcdefghijklmnopqrstuvwxyzäöñüà"

    /** Genişletme tablosu: her biri 2 septet yer kaplar. */
    private const val EXTENDED = "^{}\\[~]|€"

    private val basicSet = BASIC.toHashSet()
    private val extendedSet = EXTENDED.toHashSet()

    fun isRepresentable(ch: Char): Boolean = ch in basicSet || ch in extendedSet

    fun isRepresentable(text: String): Boolean = text.all(::isRepresentable)

    /** Metnin septet cinsinden uzunluğu (genişletme karakterleri 2 sayılır). */
    fun septetLength(text: String): Int =
        text.sumOf { if (it in extendedSet) 2 else 1 }

    /** GSM-7'de temsil edilemeyen karakterler — hata ayıklama için. */
    fun offendingChars(text: String): Set<Char> =
        text.filterNot(::isRepresentable).toSet()
}

enum class SmsEncoding { GSM7, UCS2 }

/** Bir metnin SMS bütçesi. */
data class SmsBudget(
    val encoding: SmsEncoding,
    val units: Int,
    val parts: Int,
    val singlePartLimit: Int,
    val multiPartLimit: Int,
) {
    /** Bu metin tek parçaya sığıyor mu? */
    val isSinglePart: Boolean get() = parts <= 1

    /** İlk parçaya kaç birim sığar — kritik bilgi buraya konmalıdır. */
    val firstSegmentCapacity: Int get() = if (isSinglePart) singlePartLimit else multiPartLimit
}

object Sms {
    fun budget(text: String): SmsBudget {
        val gsm = Gsm7.isRepresentable(text)
        val encoding = if (gsm) SmsEncoding.GSM7 else SmsEncoding.UCS2
        val units = if (gsm) Gsm7.septetLength(text) else text.length
        val single = if (gsm) 160 else 70
        val multi = if (gsm) 153 else 67
        val parts = if (units <= single) 1 else ((units + multi - 1) / multi)
        return SmsBudget(encoding, units, parts, single, multi)
    }
}

/**
 * Türkçe metni ASCII'ye çevirir.
 *
 * Bu bir "güzelleştirme" değil, bir KAPASİTE kararıdır: ASCII'ye çevrilmiş
 * mesaj GSM-7'de kalır ve 160 karakter taşır; çevrilmemiş mesaj UCS-2'ye
 * düşüp 70 karaktere sıkışır. Acil bir mesajda bu fark, koordinatın
 * ulaşmasıyla ulaşmamasıdır.
 */
object TurkishAscii {
    private val map = mapOf(
        'ç' to "c", 'Ç' to "C", 'ğ' to "g", 'Ğ' to "G", 'ı' to "i", 'İ' to "I",
        'ö' to "o", 'Ö' to "O", 'ş' to "s", 'Ş' to "S", 'ü' to "u", 'Ü' to "U",
        'â' to "a", 'Â' to "A", 'î' to "i", 'Î' to "I", 'û' to "u", 'Û' to "U",
    )

    fun convert(text: String): String = buildString(text.length) {
        for (ch in text) append(map[ch] ?: ch)
    }
}
