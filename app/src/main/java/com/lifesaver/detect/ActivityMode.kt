package com.lifesaver.detect

/**
 * Kullanıcının o an ne yaptığı.
 *
 * ÜÇ KİP, ALTI DEĞİL — ve sebebi konfor değil, ölçüm.
 *
 * Altı kipli hâlde kullanıcıya cevaplayamayacağı sorular soruluyordu.
 * Literatür ve platform açıkça şunu söylüyor:
 *
 *  - Android'in etkinlik tanıma API'sinde MOTOSİKLET ve TEKNE SINIFI YOKTUR;
 *    motosiklet IN_VEHICLE'a düşer. Yani araba/motosiklet ayrımını platform
 *    yapamaz.
 *  - Literatür de yapamıyor: 2024 tarihli hakemli bir derleme "arabayı
 *    motosikletten ayıran bir sınıflandırıcı kurmak daha zordur çünkü
 *    sınıflar arası varyans azalır" diyor. Yatma açısını telefonla ölçen tek
 *    çalışma, telefonun araca SABİTLENMİŞ ve hizalaması kalibre edilmiş
 *    olmasını gerektiriyor — cepteki telefon için geçerli değil.
 *  - Buna karşılık "insan mı araç mı" ayrımı güvenilir (%98.9). Literatürün
 *    kendi önerisi katmanlı yaklaşım: AİLEYİ ayır, aile içini ayırma.
 *
 * Bu yüzden kipler aile düzeyinde birleştirildi. Birleştirme kuralı tek
 * yönlüdür: BİRLEŞEN KİPİN EŞİĞİ DAHA DUYARLI OLAN TARAFTAN ALINIR. Sebebi
 * asimetri: yanlış kipte kalmanın iki kolu vardır ve yalnız biri öldürür.
 * Fazla duyarlı kalmanın bedeli iptal edilebilir bir yanlış alarmdır; az
 * duyarlı kalmanın bedeli hiç çalmayan bir alarmdır.
 */
enum class ActivityMode(
    val turkish: String,
    val shortLabel: String,
    val description: String,
) {
    DAILY(
        turkish = "Günlük",
        shortLabel = "Günlük",
        description = "Yürürken, koşarken, evde, işte. Düşme algılama en " +
            "duyarlı ayarındadır. Araca binerseniz kaza algılama kendiliğinden " +
            "devreye girer.",
    ),
    VEHICLE(
        turkish = "Araçta",
        shortLabel = "Araç",
        description = "Bisiklet, araba, motosiklet. Konum sürekli izlenir, " +
            "böylece kaza algılama tünelde ve kapalı otoparkta da hazırdır. " +
            "Yol tümseklerinden gelen yanlış alarmları azaltmak için düşme " +
            "eşiği bir miktar yükselir.",
    ),
    BOAT(
        turkish = "Teknede",
        shortLabel = "Tekne",
        description = "Sürekli dalga hareketi düşme algılamanın hareketsizlik " +
            "kapısını bozar; bu kip onu gevşetir. DİKKAT: devrilme ve denize " +
            "düşme algılama HENÜZ YOK.",
    );

    companion object {
        /**
         * Kip adını çözer ve ESKİ ALTI KİPLİ adları da kabul eder.
         *
         * Göç sessiz olmamalıdır: eski adı tanımayıp varsayılana düşmek,
         * araçtaki bir kullanıcıyı güncelleme sonrası farkında olmadan
         * başka bir profile taşırdı.
         */
        fun fromName(name: String?): ActivityMode = when (name) {
            null, "" -> DAILY
            "PEDESTRIAN", "RUNNING" -> DAILY
            "CYCLING", "CAR", "MOTORCYCLE" -> VEHICLE
            else -> entries.firstOrNull { it.name == name } ?: DAILY
        }
    }
}

/**
 * Bir kipin tam algılama yapılandırması.
 *
 * Her kip için hangi dedektörün çalıştığı ve hangi eşiklerle çalıştığı
 * TEK BİR YERDE toplanır. Eşiklerin kod içine dağılması, hangi kipte neyin
 * geçerli olduğunu izlenemez hale getirir.
 */
data class DetectionProfile(
    val mode: ActivityMode,
    val fallEnabled: Boolean,
    val fallConfig: FallConfig,
    /**
     * Kaza dedektörü KURULUR mu?
     *
     * Artık her kipte kurulur ve yalnızca KANITLA silahlanır. Eskiden kipe
     * bağlıydı ve bu, kipini değiştirmeyi unutan motosikletlinin 145 puanlık
     * kaza kanıt havuzunun tamamını devre dışı bırakıyordu. Dedektörü kurmak
     * yalnızca KAPSAMA EKLER, hiçbir eşiği gevşetmez; bu yüzden otomatik
     * olması güvenlidir.
     */
    val crashEnabled: Boolean,
    val crashConfig: CrashConfig,
    val crashVehicleMode: VehicleMode,
    /**
     * Konum akışı SÜREKLİ açık mı?
     *
     * Kipin gerçek pil bedeli budur, kaza dedektörünün varlığı değil. Günlük
     * kipte konum kapalıdır ve yalnızca etkinlik tanıma "araçta" dediğinde
     * fırsatçı olarak açılır.
     */
    val continuousLocation: Boolean,
    /** Kullanıcıya gösterilecek dürüst pil notu. */
    val batteryNote: String,
    /** Bu kipte kapsanmayan riskler. Boşsa yok. */
    val gaps: List<String> = emptyList(),
) {
    companion object {

        /**
         * Kaza yapılandırması artık KİPTEN BAĞIMSIZDIR ve tek bir birleşimdir.
         *
         * İki karar burada veriliyor ve ikisi de aynı asimetriden çıkıyor:
         *
         *  - Silahlanma hızı, birleşimin EN DÜŞÜĞÜ (15 km/s, bisikletten
         *    gelir). Silahlanma yalnızca dedektör ekler; yüksek eşik seçmek
         *    bisikletliyi ve yavaş motosikletliyi kapsam dışı bırakırdı.
         *  - Tepe ivme kapısı, birleşimin EN DÜŞÜĞÜ (25 m/s², motosikletten
         *    gelir). Arabaya motosiklet kapısını uygulamak fazladan aday
         *    üretir — bu iptal edilebilir. Motosiklete araba kapısını (40)
         *    uygulamak ise gerçek bir düşük-taraf düşüşünü görünmez yapar ve
         *    bunun arka duvarı yoktur.
         */
        private fun unifiedCrashConfig() = CrashConfig(
            armSpeedMs = 4.17f,        // 15 km/s — birleşimin en düşüğü
            disarmSpeedMs = 1.39f,     // 5 km/s
            leanFallDegrees = 50f,     // bisiklet/motosiklet ortak değeri
        )

        fun forMode(mode: ActivityMode): DetectionProfile = when (mode) {

            ActivityMode.DAILY -> DetectionProfile(
                mode = mode,
                fallEnabled = true,
                // Yaya ve koşu birleşti; DAHA DUYARLI taraf alındı.
                // Koşunun 4.00 g / 4 puan ayarı silindi çünkü tek etkisi
                // duyarlılığı düşürmekti ve kullanıcı koşudan sonra geri
                // almayı unutursa akşama kadar sağır kalıyordu.
                fallConfig = FallConfig(),
                crashEnabled = true,
                crashConfig = unifiedCrashConfig(),
                crashVehicleMode = VehicleMode.MOTORCYCLE,
                continuousLocation = false,
                batteryNote = "Ek pil maliyeti yok. Konum kapalı; yalnızca " +
                    "etkinlik tanıma 'araçta' derse açılır.",
                gaps = listOf(
                    "Koşarken tempolu adımlar aday üretebilir; çoğu adım " +
                        "vetosuyla elenir ama koşu sonrası durmada alarm çalabilir.",
                ),
            )

            ActivityMode.VEHICLE -> DetectionProfile(
                mode = mode,
                fallEnabled = true,
                // Bisiklet + araba + motosiklet birleşti; DAHA DUYARLI taraf
                // olan bisikletin 3.00 g'si alındı. Arabanın 4.00 g'si bir
                // güvenlik değil KONFOR ayarıydı: yol tümsekleri 2-3 g üretir.
                fallConfig = FallConfig(impactG = 3.0f),
                crashEnabled = true,
                crashConfig = unifiedCrashConfig(),
                crashVehicleMode = VehicleMode.MOTORCYCLE,
                continuousLocation = true,
                batteryNote = "Saniyede bir konum alınır. Silahlanınca " +
                    "ivmeölçer 200 Hz'e çıkar. Pil tüketimi belirgin artar.",
                gaps = listOf(
                    "Araba ve motosiklet ayrımı YAPILMAZ; iki tekerlek " +
                        "kanıtları (yatma, çift darbe) her araçta aranır.",
                    "Hava yastığı kanıtı bisiklette ve motosiklette yoktur; " +
                        "karar diğer kanıtlara dayanır.",
                ),
            )

            ActivityMode.BOAT -> DetectionProfile(
                mode = mode,
                fallEnabled = true,
                fallConfig = FallConfig(
                    impactG = 3.2f,
                    stillnessAccelRangeG = 0.8f,   // dalga hareketi payı
                    micromotionNoiseMultiple = 8f, // tekne titreşimi gürültü tabanını yükseltir
                ),
                crashEnabled = true,
                crashConfig = unifiedCrashConfig(),
                crashVehicleMode = VehicleMode.MOTORCYCLE,
                continuousLocation = false,
                batteryNote = "Ek pil maliyeti yok.",
                gaps = listOf(
                    "Devrilme ve denize düşme algılama YOK.",
                    "Su altına giren telefon hiçbir şey gönderemez.",
                    "Gevşetilmiş hareketsizlik kapısı karada yanlış alarmı " +
                        "artırır; karaya çıkınca kipi değiştirin.",
                ),
            )
        }
    }
}
