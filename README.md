# LifeSaver

> **English:** LifeSaver is an Android app that gets help to you when you can't ask for it yourself. It
> detects falls, car/motorcycle crashes, falling overboard and unresponsiveness from the phone's sensors,
> sounds an alarm, makes a Turkish announcement to people nearby, texts and calls your contacts with your
> location and, as a last step, opens the 112 emergency channel. The README starts with what the app
> *cannot* do, verified against AOSP and a real Pixel 9 Pro. **It is not a certified emergency device;
> do not rely on it as your only safety measure.**

Siz yanıt veremeyecek durumdayken yardımın size ulaşmasını sağlayan Android uygulaması.

Düşme, araç/motosiklet kazası, denize düşme ve yanıtsızlık durumlarını cihaz
sensörleriyle algılar; alarm çalar, çevredekilere Türkçe anons yapar,
yakınlarınıza konumlu SMS gönderip onları arar ve son basamakta 112 kanalını açar.

## Önce dürüstlük: bu uygulamanın YAPAMADIKLARI

**Sertifikalı bir acil durum cihazı değildir;** tek güvenlik önleminiz olarak ona güvenmeyin.

Bunlar tasarım tercihi değil, Android'in ve fiziğin sert sınırlarıdır. Hepsi
AOSP kaynağına ve bağlı bir Pixel 9 Pro'ya karşı doğrulanmıştır.

| Yapamaz | Neden |
|---|---|
| 112'yi sizin yerinize **sesli** aramak | AOSP Telecom (`NewOutgoingCallIntentBroadcaster`), varsayılan çevirici olmayan bir uygulamanın acil numaraya `ACTION_CALL`'ını iptal eder ve sistem çeviricisini açar. `CALL_PRIVILEGED` izni `signature\|privileged`, `ROLE_EMERGENCY` ise `systemOnly` — üçüncü taraf bir uygulama bunları asla alamaz. |
| 112 operatörüne konuşmak | Çağrı yukarı-bağına ses enjekte etmenin genel API'si yoktur. Ses karşı tarafa yalnızca hoparlörden mikrofona, akustik olarak ulaşır ve cihazın yankı bastırıcısı buna karşı çalışır. |
| Aramanın cevaplandığını anlamak | `TelephonyCallback` yalnızca IDLE/RINGING/OFFHOOK verir ve giden çağrı **çevirme anında** OFFHOOK olur. |
| Su altından haber vermek | GNSS deniz suyunda ~3 cm'de, BLE 15-20 cm'de ölür. Tek şans, telefon hâlâ havadayken mesajı çıkarmaktır. |
| Kalp krizini telefonla algılamak | Telefonda fizyolojik nabız sinyali yoktur. Bunun için BLE nabız bandı (0x180D) veya bir akıllı saat gerekir. |
| Gerçek kaza tepe ivmesini ölçmek | Ciddi kaza 100 g'yi aşar; telefon ivmeölçeri ±8-16 g'de doyar. Bu yüzden **doyma, hata değil kaza kanıtı** olarak kullanılır. |

**Yapabildikleri:** yakınlara otomatik konumlu SMS ve otomatik arama, 112'ye
otomatik konumlu SMS (Türkiye'de gerçek bir kanaldır ama otomatik konum
içermez — koordinatı mesaja biz koyarız), 112 çeviricisini numara girilmiş
halde önünüze koymak, siren, fener SOS, Türkçe anons ve kalp masajı metronomu.

## Mimari

```
detect/          Saf Kotlin, Android bagimliligi YOK -> JVM'de test edilebilir
  Signals.kt         halka tampon, pencere istatistikleri, durus acisi, doyma
  DetectionConfig.kt literaturden gelen esikler, her biri kaynagiyla
  FallDetector.kt    4 sert kapi + 7 agirlikli kanit + 2 veto
  CrashDetector.kt   arac/motosiklet kazasi: doyma, hava yastigi, hiz cokusu
  ActivityMode.kt    3 etkinlik kipi + tek yonlu otomasyon kurali
  SensorLiveness.kt  sifirlanmis/olu sensor akisini yakalar

learn/           Saf Kotlin -> tamamen test edilebilir
  Feedback.kt        iptal sebebi, iptal kaydi, uygulanmis ayarlama
  ThresholdTuner.kt  sinirlandirilmis esik uyarlamasi (kara kutu DEGIL)

alert/           Saf Kotlin -> sahte saatle test edilebilir
  EmergencyMessage.kt koordinat bicimleme, GSM-7 butcesi, ASCII cevrimi
  AlertTexts.kt       Turkce SMS ve TTS metinleri
  Escalation.kt       tirmanma durum makinesi; hukuki kisitlar kod olarak

actuator/        Her biri BAGIMSIZ cokebilir
  Siren.kt, TorchStrobe.kt, Haptics.kt, Voice.kt

GuardianService.kt  on plan servisi: sensor -> algilama -> tirmanma
EscalationBus.kt    servis <-> alarm ekrani koprusu
ui/                 ana ekran, alarm ekrani, kip secici, hizli ayar dosemesi
```

Algılama ve tırmanma mantığının tamamı saf Kotlin'dir. Doğrulaması bir telefonu
yere atmaya bağlı değildir: 140 birim testi sentetik sinyallerle ve sahte saatle
çalışır.

## Etkinlik kipleri — üç kip ve tek yönlü otomasyon

Başlangıçta altı kip vardı (yaya, koşu, bisiklet, araba, motosiklet, tekne).
Bir araştırma turundan sonra üçe indirildi, çünkü kullanıcıya cevabı ölçülemeyen
sorular soruluyordu:

- Android'in etkinlik tanıma API'sinde **MOTOSİKLET ve TEKNE sınıfı yoktur**;
  motosiklet `IN_VEHICLE`'a düşer. Araba/motosiklet ayrımını platform yapamaz.
- Literatür de yapamıyor: hakemli bir derleme *"arabayı motosikletten ayıran bir
  sınıflandırıcı kurmak daha zordur çünkü sınıflar arası varyans azalır"* diyor.
  Yatma açısını telefonla ölçen tek çalışma telefonun araca **sabitlenmiş ve
  hizalaması kalibre edilmiş** olmasını gerektiriyor — cepteki telefon için
  geçerli değil.
- Buna karşılık **"insan mı araç mı"** ayrımı güvenilir (%98.9). Literatürün
  kendi önerisi katmanlı yaklaşım: aileyi ayır, aile içini ayırma.

Birleştirme kuralı tek yönlüdür: **birleşen kipin eşiği daha DUYARLI olan
taraftan alınır.** Sebebi asimetri — fazla duyarlı kalmanın bedeli kullanıcının
iptal edebildiği bir yanlış alarmdır; az duyarlı kalmanın bedeli hiç çalmayan
bir alarmdır ve bunun arka duvarı yoktur.

| Kip | Düşme eşiği | Kaza algılama | Konum | Neyi birleştirdi |
|---|---|---|---|---|
| Günlük | 2.60 g, 3 puan | kurulu, kanıtla silahlanır | kapalı (fırsatçı) | yaya + koşu |
| Araçta | 3.00 g, 3 puan | kurulu, kanıtla silahlanır | sürekli 1 Hz | bisiklet + araba + motosiklet |
| Teknede | 3.20 g, gevşek hareketsizlik | kurulu, kanıtla silahlanır | kapalı (fırsatçı) | — |

### Neyin otomatik olduğu, neyin olmadığı

Tek kural: **otomatik hiçbir karar duyarlılığı düşüremez.** Yalnızca kapsam
ekleyebilir ya da duyarlılığı artırabilir.

**Otomatik:**
- **Kaza dedektörü her kipte kuruludur** ve GNSS hızı (15 km/s, 10 s) ya da
  etkinlik tanıma ile silahlanır. Bu, eski sistemin en öldürücü koluydu: kipini
  değiştirmeyi unutan motosikletli için 145 puanlık kaza kanıt havuzunun tamamı
  devre dışı kalıyordu. Dedektörü kurmak yalnızca kapsam ekler.
- **Fırsatçı konum:** Günlük kipte konum kapalıdır; etkinlik tanıma "araçta"
  derse (güven ≥ 50, en fazla 90 s eski) yalnızca kapsam eklemek için açılır.
- **Araç kipinden Günlük kipine dönüş:** dedektör silahsız + son yüksek hızdan
  bu yana ≥ 200 adım + ≥ 10 dakika → dön. Ayrıca 3 saatlik zaman aşımı, her
  gerçek silahlanmada yenilenir. Bu geçiş duyarlılığı **artırır**, bu yüzden
  otomatik olabilir.

**Elle (ve bilerek öyle):**
- **Günlük'ten Araç'a geçiş.** Bu yön duyarlılığı düşürür (2.60 → 3.00 g) ve
  pil yakar. Yanlış bir çıkarımın kullanıcıyı sessizce sağır bırakmasına izin
  verilmez.
- **Tekne kipi.** Otomatik girilmez ve otomatik çıkılmaz. Tek yayımlanmış ölçüm
  (İstanbul vapurları, 100 Hz, 60 s pencere) ham geri çağırmayı %64.8 veriyor ve
  vapurun en çok karıştığı sınıf **araba**. Ayrıca dalga bandı (0.1-0.5 Hz)
  bizim solunum mikro-hareket kanıtımızın bandına (0.15-0.60 Hz) doğrudan
  çarpıyor — yani tekneyi otomatikleştirmek kendi en iyi "insan mı telefon mu"
  ayracımıza saldırmak olurdu.

Neden otomasyon gerekiyordu: elle kipin arızası **sessiz ve kalıcıdır** — dün
akşam arabada bırakılan kip bugün yürürken de yürürlüktedir ve kimse söylemez.
Otomatiğin arızası ise geçici ve sınırlıdır.

Kip üç yerden değiştirilir: ana ekran, kalıcı bildirimdeki "Kip değiştir"
düğmesi ve hızlı ayarlar döşemesi.

Tekne kipi eksikliğini açıkça söyler: **devrilme ve denize düşme algılama
henüz yoktur.**

### Araç ipucu — etkinlik tanıma ölürse diye ikinci kapı

Günlük kipte kaza algılamanın tek otomatik giriş kapısı Google'ın etkinlik
tanıma sınıflandırıcısıydı. O sınıflandırıcı Play Services sürecinde çalışır ve
izin/pil ayarları eksikse **sessizce ölür** — hata dönmez, sadece hiç sonuç
gelmez. GrapheneOS gibi kum havuzlu kurulumlarda bu gerçek bir olasılıktır. Tek
kapıya bağlı kalmak görünmez bir koruma boşluğu demekti.

Bunun için iki şey eklendi:

1. **Teşhis.** Hazırlık ekranındaki "Etkinlik tanıma" satırı sınıflandırıcının
   gerçekten sonuç üretip üretmediğini söyler: *çalışıyor / daha önce çalıştı /
   henüz hiç sonuç alınmadı*. Sessiz arıza artık görünür.
2. **İkinci kapı — `VehicleHint`.** GMS'siz, izinsiz, sıfır ek pille çalışan bir
   ipucu. Üç koşul birden aranır: (a) ≥ 90 s hiç adım yok, (b) doğrusal ivme
   RMS'i "taşınıyor ama yürümüyor" bandında, (c) yatay düzlemde **alçak
   geçirilmiş** özgül kuvvet ≥ 1.0 m/s² ve ≥ 3 s sürüyor.

   Ayıran şey genlik değil **süreklilik**: yürüyenin yatay kuvveti her adımda
   işaret değiştirir ve alçak geçirilmiş bileşeni sıfıra yakındır; hızlanan bir
   aracınki saniyelerce aynı yönde kalır.

   İpucu **alarm üretmez**, kip değiştirmez. Yalnızca 60 saniyelik bir GNSS
   penceresi açar; kararı hızı ölçen GNSS verir. Yanlış tetiklemenin bedeli bir
   yanlış alarm değil, bir konum penceresidir — bu yüzden eşikler cömerttir.
   Doğruysa dedektör silahlanır ve pencere kendiliğinden uzar.

**Neden Günlük kipte konumu sürekli açmıyoruz:** sürekli GNSS saatte ~%1.5 pil
yakar, yani günde %30-40. Üç kademeli zincir (bedava sensör tetiği → 60 s GNSS
penceresi → silahlanma) aynı kapsamı bunun çok altında bir bedelle verir.

### Dalga imzası — tekne kipini açmayı unutmaya karşı

Tekne kipinin en sinsi arızası kipi **açmayı unutmaktır** ve bu arıza sessizdir.
`WaveMonitor` bunu telafi eder ama yetkisi bilinçli olarak dardır.

Dalgayı ayıran şey genlik değil **frekans ve süreklilik**: 0.08-0.35 Hz (3-12
saniyelik periyot), en az 120 saniye kesintisiz, adım yokken. Yürüme 1.4-2.3
Hz'dir ve alçak geçirici süzgeç onu tamamen söndürür.

Doğrulandığında yapılan **tek** şey, düşme algılamanın hareketsizlik kapısını
0.40 g'den 0.80 g'ye gevşetmektir. Bu tek yönlü kurala uyar: yalnızca daha çok
adayın geçmesine izin verir, hiçbir şeyi görünmez yapmaz. Tam tekne kipi
**sorulur, uygulanmaz** — o kip darbe eşiğini de 3.20 g'ye çıkarır ve bu bir
duyarlılık düşüşüdür. Dalga 10 dakika boyunca kesilirse gevşetme geri alınır.
`FallDetector.relaxStillness()` sıkılaştırma isteğini **açıkça reddeder**;
kural bir yorum değil, koddadır.

**Sınır ölçüldü, varsayılmadı.** 0.2 Hz dalgada ivme kapısı ancak 0.35 g
genlikten (~3.4 m/s² düşey salınım) sonra tıkanıyor:

| düşey salınım genliği | sonuç |
|---|---|
| 0.05 g, 0.20 g | düşme algılanıyor |
| 0.35 g, 0.50 g | `MOVED_AFTER_IMPACT` ile eleniyor |

ISO 2631-1 ölçeğinde 3.4 m/s² "aşırı rahatsız edici" banttadır: bu boşluk hafif
dalgada değil, **kaba denizde** açılır — insanların gerçekten düştüğü koşulda.
Yalpalama ayrı bir kapıdır ve ölçüldüğünde tıkanmadı (0.2 Hz'de 30 dereceye
kadar jiroskop aralığı 60 °/s ölçütünün altında kaldı), bu yüzden jiroskop
ölçütüne dokunulmadı. İki sınıf birbirine test ile bağlıdır: kapıyı tıkayan her
dalga `WaveMonitor` tarafından görülmek zorundadır.

**Hâlâ eksik:** devrilme ve denize düşme algılama. Dalga imzası bunu çözmez.

## İptallerden öğrenme — ve neden sınırlıdır

Kullanıcı bir alarmı iptal ettiğinde sistem geriye dönük sinyal penceresini
(30 sn, 10 Hz) ve tetikleyen kanıtları kaydeder. Ama öğrenme kasten
sınırlandırılmıştır, çünkü bu veri **tek yönlüdür**:

> İptaller yalnızca OLUMSUZ örnek üretir. Gerçek bir kaza neredeyse hiç
> gözlemlenmez. Denetimsiz bırakılan bir öğrenici bu yüzden yalnızca "bunda
> çalma"yı öğrenebilir, "bunda çal"ı asla — ve kendini sessizce hiç çalmaz hale
> getirir. Bir hayat kurtarma uygulamasında bu, mümkün olan en kötü arıza
> biçimidir.

Bu yüzden kara kutu bir model değil, **sınırlandırılmış eşik uyarlaması**
vardır. Beş sert kural:

1. Yalnızca "böyle bir şey olmadı" cevabı öğretir. "Oldu ama iyiyim" cevabı
   eşiğin DOĞRU olduğunu söyler; ondan öğrenmek dedektöre gerçek düşmeleri
   görmezden gelmeyi öğretir.
2. Tek bir iptal hiçbir şeyi değiştirmez: en az 3 benzer örnek gerekir,
   yüksek güvenli bir alarm yanlış çıktıysa 5.
3. Her parametrenin aşılamaz tavanı vardır (özgün değerin %30 üstü).
   Tavan hep **literatür değerinden** hesaplanır, ayarlanmış değerden değil.
4. Tavana dayanınca duyarlılık düşürülmez; bunun yerine daha fazla
   destekleyici kanıt istenir (puan eşiği +1, en fazla +2).
5. Her değişiklik "Öğrenilenler" bölümünde gerekçesiyle yazılıdır ve tek
   dokunuşla geri alınır.

İptal sebebi iki yerden sorulur: alarm ekranından iptal edildiyse ekranda,
bildirimdeki "İYİYİM - İPTAL" düğmesinden iptal edildiyse takip bildiriminde.
Cevaplanmazsa kayıt "belirtilmedi" kalır ve **hiçbir eşik değişmez**.

## Yanlış alarm savunması

Sahadaki yanlış alarmların **%62'si telefonun düşmesidir** (Harari ve ark.).
Üç katman bunu hedefler:

1. **Taşınma kapısı** — telefon masada/çantada/şarjdayken alarm hiç kurulmaz.
2. **Nesne düşmesi vetosu** — derin ve uzun serbest düşüş (>250 ms, <0.35 g)
   insan düşmesi değildir; cepteki telefon böyle bir çukur görmez.
3. **Solunum mikro-hareketi** — 0.15-0.60 Hz bandında RMS. Yerde yatan insanın
   cebindeki telefon nefes almayla modüle olur; boş telefon yalnızca sensör
   gürültüsü gösterir. "Telefon mu düştü, insan mı" ayrımını yapan sinyal budur.

## Hukuki kısıtlar koda gömülüdür

Kabahatler Kanunu m.42/A (8/5/2025 - 7547 s.K. ile değişik): 112'yi meşgul etmek
~1.882 TL, ihbarın ekiplerce asılsız bulunması ~18.823 TL idari para cezasıdır ve
**iyi niyet istisnası yoktur**. Bu yüzden:

- 112 kanalı olay başına **bir kez** kullanılır; otomatik yeniden arama yoktur
  (m.42/A "ısrarla çağrı bırakma" fiilini ayrıca cezalandırır).
- 112 kanalı **en son** basamaktır (t+150 s) ve yalnızca güven HIGH ise açılır.
- 112'ye giden mesaj ilk cümlede **makine kaynaklı** olduğunu söyler ve
  geri arama numarası verir.
- Her tetikleme, her puan bileşeni ve gönderilen her metin adli günlüğe yazılır.

## Tatbikat modu varsayılan olarak AÇIKTIR

Zincirin tamamı çalışır, alarm çalar, mesajlar oluşturulur — ama hiçbir SMS
gönderilmez ve kimse aranmaz. Kullanıcı en az bir tatbikat yapıp bunu bilinçli
olarak kapatana kadar dışarı gerçek bir mesaj çıkmaz.

## Derleme

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew assembleDebug testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Sürüm kümesi (her biri Maven'dan doğrulandı): Gradle 9.0.0, AGP 8.13.2,
Kotlin 2.2.20, Compose BOM 2025.09.00, compileSdk 36, minSdk 26, targetSdk 36.

> AGP 9.x ve Gradle 9.6+ bilinçli olarak KULLANILMIYOR: AGP 9 ayrı Kotlin
> eklentisini reddediyor, Gradle 9.6 ise AGP 8'in kullandığı bir iç API'yi
> kaldırmış. 2026 tarihli androidx sürümleri de `minCompileSdk 37` istiyor.
> Bu küme bu makinede derlenip test edildi.

## Kaza algılama

Düşme algılamadan mimari olarak farklıdır ve bu fark kasıtlıdır:

| | Düşme | Kaza |
|---|---|---|
| Ne zaman çalışır | Sürekli | Yalnızca araç bağlamı doğrulandığında |
| Örnekleme | 50 Hz + toplu bildirim | Silahlıyken 200 Hz, batching kapalı |
| Doyma | Nesne düşmesi **vetosu** | Kaza **kanıtı** (en ağır puan) |
| Geri sayım | 30 sn sabit | Güvene bağlı: 10 sn (şiddetli) / 60 sn (orta) |

**Silahlanma:** 25 km/s hızda 10 saniye, veya etkinlik tanımadan IN_VEHICLE.
Silahtan çıkma: 5 km/s altında 5 dakika. Araç bağlamı olmadan 4 g'lik bir darbe
yok sayılır — kullanıcı sadece telefonunu masaya bırakmıştır.

**Kanıtlar ve puanları:** ivmeölçer doyması 30, hava yastığı basınç sıçraması
(≥200 hPa/s) 30, hız çöküşü 25, şiddetli dönme 15, çarpma sonrası hareketsizlik 20,
motosiklet yatıp kalkmaması 25, yüksek taraf çift darbesi 15, darbe şiddeti 8-15,
araç bağlamı 10. Eşik: ≥70 yüksek güven, ≥50 orta güven.

**Vetolar:** araç içinde telefon düşmesi (Google Research'ün açıkça adlandırdığı
hata), çarpma sonrası hareketin sürmesi, ve **sürüşe devam** — olaydan sonraki
60 saniye içinde araç 15 km/s ile ilerlemeye devam ederse olay otomatik iptal
edilir. Bu, en güçlü tek yanlış alarm elemesidir.

**Motosiklet farkları:** darbe eşiği 40 → 25 m/s² (kayarak düşüşte tepe ivme
küçüktür), yatma açısının KALICILIĞI ölçülür (spor sürücüler 60 dereceye yatar
ama motosiklet geri kalkar), ve yüksek taraf için 0.3-2.5 sn arayla ikinci
darbe aranır.

## Yapılacaklar

- [ ] Tekne kipi için devrilme ve denize düşme algılama (dead-man switch)
- [ ] Bluetooth ile öğretilmiş araç kimliği (cihaz sınıfı değil, kullanıcının
      işaretlediği cihaz) — yalnızca ek silahlanma yolu olarak
- [ ] Araç ipucunun ve dalga imzasının sahada ölçülmesi (yanlış tetikleme sıklığı)
- [ ] Gömülü Türkçe ses dosyaları (TTS motoru yoksa yedek)
- [ ] Olay günlüğü ekranı ve PDF dışa aktarma
- [ ] SisFall/UMAFall veri setleriyle iz oynatma testleri
- [ ] BLE nabız bandı (0x180D) ile bilinç kaybı algılama

## Cihazda bulunan hatalar (düzeltildi)

Bu dördü birim testlerinin yakalayamadığı, cihazda ve düşmanca inceleme ile
bulunan **sessiz** arızalardı. Hepsi için gerileme testi yazıldı.

1. **Araç kipinde düşme algılama tamamen ölüydü.** `FallDetector` halka
   tamponunu kurulumda 50 Hz'e göre boyutluyordu (1002 örnek = 20 s). Kaza
   algılama silahlanınca aynı ivme akışı 200 Hz'e çıkıyor ve tampon 20 saniye
   yerine **5 saniye** tutuyordu. Çarpma sonrası hareketsizlik kapısı
   [tepe+2 s, tepe+12 s] penceresini tamponda bulamadığı için **her** düşme
   adayı `MOVED_AFTER_IMPACT` ile eleniyordu — ve günlüğe "elendi" yazdığı için
   dedektör çalışıyormuş gibi görünüyordu. Tampon artık teslim edilen en yüksek
   hızdan boyutlanıyor; solunum bandı filtresinin `dt`'si de gerçek örnekleme
   aralığından ölçülüyor.
2. **Silah hiç düşmüyordu.** Silahtan çıkma yalnızca `onSpeed` içinde
   değerlendiriliyordu ve koşulu "her örnek yavaş" biçimindeydi. Hiç örnek
   gelmezse (tünel, kapalı otopark, konum kapalı, GMS'siz cihaz) koşul asla
   sağlanmaz: dedektör sonsuza kadar silahlı, ivmeölçer sonsuza kadar 200 Hz.
   Artık ivme akışından işleyen bir zaman aşımı var ve koşul negatif kurulu.
3. **Kip değişimi canlı olayı öldürüyordu.** `startGuarding()` koşulsuz yeni bir
   `EscalationMachine` atıyordu; geri sayım sürerken kip değişirse olay sessizce
   ölüyor ve `StopAlarm`'ın tek kaynağı kayboluyordu. Artık canlı olay sırasında
   yeniden kurulum ertelenir.
4. **"Sürüşe devam" vetosu düşme olaylarını da iptal ediyordu.** Senaryo kapısı
   eklendi: bu veto yalnızca kaza olaylarını iptal edebilir.

Ayrıca etkinlik tanıma isteği hiç kaldırılmıyordu (koruma durduktan sonra da
sürüyordu) ve etkinlik tanıma sonucunun tazeliği hiç kontrol edilmiyordu —
saatler önceki bir "araçta" okuması dedektörü silahlandırabiliyordu.

## Bilinen sınırlar

- Algılama eşikleri literatürden alınmıştır ve **gerçek düşmelerle henüz
  doğrulanmamıştır**. Birim testleri durum makinesinin şartnameye uygunluğunu
  kanıtlar, saha başarımını değil.
- Kullanıcı uygulamayı "Kısıtlanmış" pil kovasına koyarsa Android 13+
  `BOOT_COMPLETED` göndermez ve uygulama yeniden başlatmadan sonra kendini
  toparlayamaz. Bunu düzeltmenin API'si yoktur.
- Android 13+ Görev Yöneticisi'nden tek dokunuşla servis durdurulabilir;
  geri çağrı yoktur ve güvenlik uygulamaları için muafiyet yoktur.
- Öğrenme yalnızca **düşme** eşiklerini uyarlar. Kaza eşikleri için gereken
  saha verisi yoktur ve az sayıda iptalden kaza duyarlılığını düşürmek
  tehlikelidir.
- Kip değişikliği algılayıcıları yeniden kurar; bu sırada yaklaşık bir
  saniyelik bir boşluk oluşur. Canlı bir olay sürerken değişiklik ertelenir.
- Araba ve motosiklet ayrımı **yapılmaz**. Platform da literatür de bunu
  güvenilir yapamıyor; iki tekerlek kanıtları (yatma, çift darbe) her araçta
  aranır ve tepe ivme kapısı 25 m/s²'de birleşiktir. Bedeli arabada biraz daha
  fazla aday, kazancı motosiklette kaçırılmayan düşük-taraf düşüşü.
- Etkinlik tanımayla silahlanmanın gecikmesi 30-60 saniye mertebesindedir.
  Araca binerken kipi Araç yapmak bu gecikmeyi kaldırır; yapılmazsa yolculuğun
  ilk dakikası yalnızca düşme algılamasıyla korunur.

## Lisans

MIT, bkz. [LICENSE](LICENSE).
