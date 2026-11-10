package com.lifesaver.detect

/**
 * Algılama eşikleri. Her değer literatürden gelir ve kaynağı yanında yazılıdır.
 *
 * Bu sınıf bilinçli olarak veri sınıfıdır: testler ve alan ayarı için eşikler
 * değiştirilebilir olmalı, ama üretim varsayılanları tek ve denetlenebilir bir
 * yerde durmalı. Sihirli sayı kodun içine gömülmez.
 */
data class FallConfig(
    /** Örnekleme hızı. Literatürün modu 50 Hz (Abbate 2012, Harari 2021). */
    val sampleRateHz: Int = 50,
    /**
     * Bu dedektöre ulaşabilecek EN YÜKSEK örnekleme hızı. Tampon boyutlaması
     * buna göre yapılır; gerçek hız çalışma zamanında değişebilir.
     */
    val maxSampleRateHz: Int = 200,

    /** Halka tamponun kapsadığı süre. -5 s taban çizgisi .. +13 s mikro-hareket. */
    val bufferSeconds: Float = 20f,

    // ---- Kapı 1: Çarpma tepesi -------------------------------------------
    /** Üst eşik (UFT). Li ve ark. uyluk 2.5 g, Bourke3 bel 2.8 g. */
    val impactG: Float = 2.60f,

    /** Alt eşik (LFT) — serbest düşüş çukuru. Kangas ve ark. tam olarak 0.6 g. */
    val freefallTroughG: Float = 0.60f,

    /** Çukurdan tepeye izin verilen en uzun süre. Kangas: çarpma 1 s içinde. */
    val troughToPeakMaxMs: Long = 850,

    // ---- Veto 1: Nesne düşmesi -------------------------------------------
    /**
     * Gerçek dünyadaki yanlış alarmların %62'si telefonun düşmesidir
     * (Harari ve ark., 2070 günde 45 yanlış alarm). Cepteki bir telefon insan
     * düşerken bu kadar derin ve uzun bir serbest düşüş görmez.
     */
    val dropVetoG: Float = 0.35f,
    val dropVetoMinMs: Long = 250,
    val dropVetoLookbackMs: Long = 1000,

    // ---- Veto 2: Sensör doyması ------------------------------------------
    /**
     * Sert zemine düşen telefon 87 g'ye kadar çıkar; telefonun kendi tavanı
     * ±8..16 g olduğu için sinyal düzleşir. Cepteki telefon insan düşerken
     * doyuma ulaşmaz — doyma + serbest düşüş birlikte görülürse bu nesne düşmesidir.
     */
    val saturationFraction: Float = 0.90f,
    val saturationFlatSamples: Int = 3,

    // ---- Kapı 2: Çarpma sonrası durgunluk --------------------------------
    val stillnessFromSec: Float = 2.0f,
    val stillnessToSec: Float = 12.0f,
    /** Li ve ark. statik duruş ölçütü: 1 s'lik pencerede |a| aralığı < 0.40 g. */
    val stillnessAccelRangeG: Float = 0.40f,
    /** Aynı ölçütün jiroskop yarısı: < 60 derece/s. */
    val stillnessGyroRangeDegS: Float = 60f,

    // ---- Puanlanan kanıtlar ----------------------------------------------
    /** Bourke3'ün yayımlanmış dikey hız eşiği: -0.7 m/s. */
    val verticalVelocityMs: Float = -0.7f,
    val verticalVelocityWindowMs: Long = 800,

    /** Li uyluk 340 derece/s, Bourke gövde 177 derece/s; arası seçildi. */
    val impactGyroDegS: Float = 250f,

    /** Chen 20 derece, Bourke3 bel 60 derece; cep yerleşimi için arası. */
    val postureChangeDeg: Float = 40f,

    /** Cepte olma kanıtı. */
    val proximityNearCm: Float = 5.0f,
    val darkLux: Float = 10f,

    /** Barometre: metrede 12 Pa; cepteki telefon düşerken 0.7-0.9 m iner. */
    val baroRisePa: Float = 8f,
    val baroBaselineMaxStdPa: Float = 2f,

    /**
     * Solunum bandı. Yerde yatan bir insanın cebindeki telefon 0.15-0.60 Hz'de
     * (12-36 soluk/dk) modüle olur; sert zemine düşmüş boş telefon sadece
     * sensör gürültüsü gösterir. "Telefon mu düştü, insan mı" ayrımının özü budur.
     */
    val micromotionLowHz: Float = 0.15f,
    val micromotionHighHz: Float = 0.60f,
    val micromotionFromSec: Float = 3.0f,
    val micromotionToSec: Float = 13.0f,
    /** Cihaz başına ölçülen gürültü tabanının kaç katı "insan var" sayılsın. */
    val micromotionNoiseMultiple: Float = 5.0f,

    // ---- Silahlanma kapısı ------------------------------------------------
    /**
     * Telefon taşınmıyorsa (masada, çantada, şarjda) alarm KURULMAZ.
     * Baskın saha hata modunu doğrudan hedefler.
     */
    val onBodyStepWindowSec: Float = 300f,
    val onBodyMinSteps: Int = 10,
    val onBodySignificantMotionSec: Float = 120f,

    // ---- Karar -------------------------------------------------------------
    /**
     * Saf VE-zinciri gerçek düşmelerde %40-55 duyarlılık verir (Bagala ve ark.),
     * çünkü gerçek düşmeler aşamaları atlar. Bu yüzden: sert kapılar + ağırlıklı puan.
     */
    val minScore: Int = 3,
) {
    val sampleIntervalNanos: Long get() = 1_000_000_000L / sampleRateHz
    /**
     * Halka tamponu TESLİM EDİLEN EN YÜKSEK hızdan boyutlar, nominal hızdan
     * değil.
     *
     * CİHAZDA BULUNAN HATA: kaza algılama silahlanınca aynı ivmeölçer akışı
     * 200 Hz'e çıkıyor (GuardianService.syncCrashSampleRate) ve o akış bu
     * dedektöre de geliyor. 50 Hz'e göre boyutlanmış 1002 örneklik tampon
     * 200 Hz'de 20 saniye değil 5 saniye tutuyordu; çarpma sonrası
     * durgunluk kapısı [tepe+2 s, tepe+12 s] penceresini tamponda
     * BULAMADIĞI için HER düşme adayı MOVED_AFTER_IMPACT ile eleniyordu.
     * Yani araç kiplerinde düşme algılama sessizce tamamen ölüydü.
     */
    val bufferCapacity: Int get() = (bufferSeconds * maxSampleRateHz).toInt() + 2
}

/** Puan katkıları — kararın neden verildiğini açıklanabilir tutar. */
object Weights {
    const val VERTICAL_VELOCITY = 2
    const val GYRO_IMPACT = 2
    const val PROXIMITY_NEAR = 2
    const val ON_BODY = 2
    const val POSTURE_CHANGE = 1
    const val DARKNESS = 1
    const val BAROMETER = 1
    const val MICROMOTION = 2
}
