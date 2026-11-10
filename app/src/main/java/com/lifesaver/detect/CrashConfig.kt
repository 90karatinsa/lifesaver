package com.lifesaver.detect

/** Sürüş kipi. Motosiklet eşikleri araçtan belirgin şekilde farklıdır. */
enum class VehicleMode { OFF, CAR, MOTORCYCLE }

/**
 * Kaza algılama eşikleri. Her değer kaynağıyla birlikte.
 *
 * Düşme algılamadan temel farkı: kaza algılayıcı SÜREKLİ çalışmaz.
 * Yalnızca araç bağlamı doğrulandığında silahlanır. Bunun iki sebebi var:
 * 200 Hz örnekleme pil yakar, ve bir araçta olmayan kullanıcıda 4 g'lik
 * darbeler (telefonu masaya bırakmak, koşmak) yanlış alarm üretir.
 */
data class CrashConfig(
    // ---- Silahlanma ------------------------------------------------------
    /** Bu hızın üstünde bu süre kalınırsa araçtayız sayılır. */
    val armSpeedMs: Float = 6.94f,          // 25 km/s
    val armSustainSeconds: Float = 10f,
    /** Bu hızın altında bu süre kalınırsa silahtan çıkılır. */
    val disarmSpeedMs: Float = 1.39f,       // 5 km/s
    val disarmSustainSeconds: Float = 300f, // 5 dakika

    // ---- Aşama 1: aday tetikleyiciler ------------------------------------
    /**
     * Araç için tepe ivme kapısı. Patent US11297177B2 30 m/s^2 kullanır;
     * Google Research 5 m/s^2 ile her sert frende tetiklenip ardından bir
     * sinir ağıyla eler. Ağımız olmadığı için kapıyı yüksek tutuyoruz.
     */
    val carPeakAccelMs2: Float = 40f,       // ~4.1 g
    /**
     * Motosiklette düşük taraf (low-side) düşüşü enerjiyi kayarak harcar;
     * tepe ivme cepteki telefonda 3-6 g'de kalır, daha da düşebilir.
     */
    val motorcyclePeakAccelMs2: Float = 25f, // ~2.5 g
    val peakWindowMs: Long = 100,

    /** Patent US11297177B2'nin jiroskop eşiği. Jiroskop kazada doymaz. */
    val peakGyroRadS: Float = 4f,           // ~229 derece/s

    /** Hız çöküşü: ~1 Hz GNSS için en dar anlamlı pencere 2 s'dir. */
    val speedCollapseFromMs: Float = 6.94f, // 25 km/s
    val speedCollapseToMs: Float = 2.22f,   // 8 km/s
    val speedCollapseWindowSeconds: Float = 2.0f,
    /** Bu doğruluktan kötü hız okumalarına güvenilmez. */
    val speedAccuracyMaxMs: Float = 2.0f,

    // ---- Aşama 2: doğrulama ----------------------------------------------
    /** Analiz penceresi: tetiklemeden 3 s önce, 3 s sonra. */
    val analysisWindowSeconds: Float = 3.0f,

    /** Doyma: bazı HAL'ler ölçek katsayısı uygular, 1.00 beklenmez. */
    val saturationFraction: Float = 0.95f,

    /**
     * Hava yastığı basınç sıçraması.
     * Patent 500 hPa/s der; kapı açma <5 hPa/s, kapı kapama <10 hPa/s.
     * 200 hPa/s'ye gevşetildi çünkü telefon çantada/cepte olabilir.
     */
    val airbagPressureRateHpaS: Float = 200f,
    val airbagWindowSeconds: Float = 2.0f,

    /** Çarpma sonrası durgunluk: her sistemin dayandığı ölçüt. */
    val stillnessStartSeconds: Float = 2.0f,
    val stillnessWindowSeconds: Float = 8.0f,
    val stillnessRmsMs2: Float = 0.5f,

    // ---- Vetolar ----------------------------------------------------------
    /**
     * Telefon düşmesi vetosu. Google Research bu hatayı açıkça adlandırır:
     * "araç içinde telefonu yere düşürmek büyük bir ivme sinyali üretir".
     */
    val dropVetoAccelMs2: Float = 2.0f,
    val dropVetoMinMs: Long = 150,
    val dropVetoLookbackMs: Long = 500,
    val dropVetoPenalty: Int = 40,

    /**
     * Sürüşe devam vetosu. 15 km/s ile ilerleyen bir aracı bilinçli biri
     * kullanıyordur. En güçlü tek yanlış alarm elemesidir.
     */
    val resumedSpeedMs: Float = 4.17f,      // 15 km/s
    val resumedSustainSeconds: Float = 10f,
    val resumedWatchSeconds: Float = 60f,

    // ---- Motosiklete özgü -------------------------------------------------
    /**
     * Yol sürücüleri ~45, spor sürücüler 50-60 derece yatar. Bu yüzden
     * ayırt edici olan açının kendisi DEĞİL, kalıcılığıdır: motosiklet
     * kalkmıyorsa düşmüştür.
     */
    val leanFallDegrees: Float = 60f,
    val leanReturnDegrees: Float = 20f,
    val leanPersistSeconds: Float = 3.0f,
    val leanSpeedBelowMs: Float = 2.22f,    // 8 km/s

    /**
     * Yüksek taraf (high-side) çift darbe: sürücü makineden fırlar,
     * inişi motosikletin geri savrulmasından AYRI bir olaydır.
     */
    val highSideSecondPeakMinSeconds: Float = 0.3f,
    val highSideSecondPeakMaxSeconds: Float = 2.5f,
    val highSideRollReversalRadS: Float = 8f,

    // ---- Karar --------------------------------------------------------------
    val highConfidenceScore: Int = 70,
    val mediumConfidenceScore: Int = 50,
) {
    fun peakAccelFor(mode: VehicleMode): Float = when (mode) {
        VehicleMode.MOTORCYCLE -> motorcyclePeakAccelMs2
        else -> carPeakAccelMs2
    }
}

/** Kaza kanıtlarının puan katkıları. Toplam 100 üzerinden ölçeklenir. */
object CrashWeights {
    const val SATURATION = 30       // sensör doydu: gerçek kaza şiddeti
    const val AIRBAG = 30           // hava yastığı basınç sıçraması
    const val SPEED_COLLAPSE = 25   // hız çöküşü
    const val GYRO = 15             // şiddetli dönme
    const val STILLNESS = 20        // çarpma sonrası hareketsizlik
    const val LEAN_FALL = 25        // motosiklet yatıp kalkmadı
    const val HIGH_SIDE = 15        // çift darbe imzası
    const val IN_VEHICLE = 10       // araç bağlamı doğrulandı
    const val IMPACT_MODERATE = 8   // eşiğin üstünde darbe
    const val IMPACT_SEVERE = 15    // eşiğin 1.5 katı üstünde darbe
}
