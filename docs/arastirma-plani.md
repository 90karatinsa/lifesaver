30 gün boyunca hiç sensör olayı yoksa: uygulama kendini "şüpheli sessiz" ilan eder, kullanıcıya tatbikat yapmasını söyler.

**Sensör canlılık testi (kod):**

```kotlin
// sensing/SensorLiveness.kt
class SensorLiveness(private val clock: Clock) {
    private var lastEventMs = 0L
    private val window = ArrayDeque<Float>(500)   // 5 s @ 100 Hz

    fun onSample(svm: Float, nowMs: Long) {
        lastEventMs = nowMs
        if (window.size == 500) window.removeFirst()
        window.addLast(svm)
    }

    fun evaluate(nowMs: Long): Liveness = when {
        nowMs - lastEventMs > 20_000L            -> Liveness.NO_EVENTS      // sensör kapalı
        window.size < 500                        -> Liveness.WARMING
        stdDev(window) < 0.005f                  -> Liveness.ZEROED         // GrapheneOS / HAL
        mean(window) !in 8.0f..11.5f             -> Liveness.IMPLAUSIBLE    // yerçekimi yok
        else                                     -> Liveness.OK
    }
}
```

`NO_EVENTS`, `ZEROED` ve `IMPLAUSIBLE` durumlarının hepsi **kırmızı rozet + bildirim** üretir. `getDefaultSensor()` bu durumların hiçbirinde `null` dönmez — bu yüzden nesne varlığına bakmak yetmez.

### 7.7 Pil bütçesi (dürüst rakam)

| Yapılandırma | Beklenen tüketim |
|---|---|
| Wake-up ivmeölçer mevcut + 2,0 s batching | **%0,5–1,5 / saat** |
| Non-wake-up ivmeölçer + sürekli `PARTIAL_WAKE_LOCK` (**gerçek durum**) | **%1,5–3,0 / saat** |
| Olay sırasında (1 Hz GNSS + siren + fener + ekran) | **%15–25 / saat** |

Literatürün "%0,9–6,0/saat" aralığı wake-up sensörlü ideal durumu içerir. Çoğu telefonda wake-up ivmeölçer **yoktur** → wake lock normal yoldur, ve bu gerçek bütçedir. Kullanıcıya onboarding'de **açıkça söylenir**: *"LifeSaver saatte yaklaşık %2 pil kullanır. Bu, koruma açık olduğu sürece kaçınılmazdır."*

Enerjiyi olay anında GNSS'ten kısmayın (GNSS konum güncellemesi başına yalnızca 232–318 mJ) — **ağ/uplink** ve **ekran+siren+fener** kısılacak yerdir.

---

## 8. Proje kurulumu

### 8.1 Doğrulanmış sürüm kümesi

Bu küme bu makinede **derlendi** (debug + unit test + R8 release APK). Değiştirmeyin.

| Bileşen | Sürüm | Neden bu ve daha yenisi değil |
|---|---|---|
| Gradle | **9.7.1** | AGP 9.4.0 minimum 9.6.0 ister; depo şu an 9.0.0'a pinlenmiş → **ilk iş bunu düzeltmek** |
| AGP | **9.4.0** | Son kararlı 9.x; JDK ≥ 17, API ≤ 37 |
| Kotlin (built-in) | **2.4.20** | `org.jetbrains.kotlin.android` **UYGULANMAZ** — AGP 9 sert hata verir |
| KSP | **2.3.11** | Kotlin'den bağımsız versiyonlama (2.3.0'dan itibaren); 2.4.x KSP **yok ve gerekmiyor** |
| compileSdk | **36.1** (yuvalı DSL) | Yerelde android-34/36/36.1 kurulu; **android-37 YOK** |
| buildToolsVersion | **"37.0.0"** (açıkça) | AGP 9.4 varsayılanı 36.0.0 ve kurulu değil; cmdline-tools yok → sdkmanager yok |
| targetSdk | **36** | `targetSdk` için minor-sürüm sözdizimi yok |
| minSdk | **26** | Türkiye'de API 26+ = %96,9 kapsama; ayrıca `connect-client 1.1.0` zaten minSdk 26 dayatıyor |
| JDK | **21** (Android Studio JBR) | `jvmToolchain(21)` |
| Compose BOM | **2026.06.01** | 2026.08.00 = Compose 1.12.0 → `minCompileSdk 37` |
| core-ktx | **1.18.0** | 1.19.0 → minCompileSdk 37 |
| activity-compose | **1.12.4** | minCompileSdk 36 ✔ |
| lifecycle-* | **2.10.0** (viewmodel-compose), **2.11.0** (service) | 2.11.0 viewmodel-compose → 37 |
| navigation-compose | **2.9.8** | 2.10.0 → 37 |
| work-runtime-ktx | **2.11.2** | |
| room-* | **2.8.4** | |
| datastore-preferences | **1.2.1** | |
| play-services-location | **21.4.0** | |
| kotlinx-coroutines | **1.11.0** | |
| kotlinx-serialization-json | **1.11.0** | |
| Robolectric | **4.16.1** — `@Config(sdk = [36])` | `sdk = [37]` → `UnknownSdk` |
| Hilt | **KULLANILMAZ** | AGP-9/built-in-Kotlin/KSP üçlüsü zaten kırılgan; el yazımı `AppContainer` yeter |
| material-icons-extended | **KULLANILMAZ** | 1.7.8'de donmuş, eski stdlib çekiyor, APK'yı şişiriyor |

### 8.2 Bootstrap komutları (bu macOS makinesi)

`cmdline-tools` yok, `gradle` PATH'te yok, `sdkmanager` yok. Yol:

```bash
# --- Ortam ---
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
java -version                    # "openjdk version 21.0.9" bekleniyor

# --- Wrapper'ı 9.7.1'e taşı ---
# services.gradle.org/distributions/gradle-9.7.1-wrapper.jar 404 verir; tek yol
# bir dağıtımın kendi bin/gradle'ını kullanmaktır.
DIST=$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-9.7.1-bin/*/gradle-9.7.1 2>/dev/null | head -1)
if [ -z "$DIST" ]; then
  mkdir -p "$HOME/gradle-dists" && cd "$HOME/gradle-dists"
  curl -fLO https://services.gradle.org/distributions/gradle-9.7.1-bin.zip
  # sha256: acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a
  shasum -a 256 gradle-9.7.1-bin.zip
  unzip -q gradle-9.7.1-bin.zip
  DIST="$HOME/gradle-dists/gradle-9.7.1"
fi

cd lifesaver  # deponun kök dizini
"$DIST/bin/gradle" wrapper --gradle-version 9.7.1 --distribution-type bin
./gradlew --version               # "Gradle 9.7.1" bekleniyor
```

> Alternatif: Android Studio → New Project sihirbazı da geçerli bir wrapper üretir.

### 8.3 `gradle/libs.versions.toml`

```toml
[versions]
agp                    = "9.4.0"
kotlin                 = "2.4.20"
ksp                    = "2.3.11"
composeBom             = "2026.06.01"
coreKtx                = "1.18.0"
activityCompose        = "1.12.4"
lifecycle              = "2.10.0"
lifecycleService       = "2.11.0"
navigationCompose      = "2.9.8"
work                   = "2.11.2"
room                   = "2.8.4"
datastore              = "1.2.1"
playLocation           = "21.4.0"
coroutines             = "1.11.0"
serializationJson      = "1.11.0"
junit                  = "4.13.2"
robolectric            = "4.16.1"
mockk                  = "1.14.5"
truth                  = "1.4.4"

[libraries]
androidx-core-ktx          = { module = "androidx.core:core-ktx",                          version.ref = "coreKtx" }
androidx-activity-compose  = { module = "androidx.activity:activity-compose",              version.ref = "activityCompose" }
androidx-lifecycle-vm      = { module = "androidx.lifecycle:lifecycle-viewmodel-compose",  version.ref = "lifecycle" }
androidx-lifecycle-runtime = { module = "androidx.lifecycle:lifecycle-runtime-compose",    version.ref = "lifecycle" }
androidx-lifecycle-service = { module = "androidx.lifecycle:lifecycle-service",            version.ref = "lifecycleService" }
androidx-navigation        = { module = "androidx.navigation:navigation-compose",          version.ref = "navigationCompose" }
androidx-work              = { module = "androidx.work:work-runtime-ktx",                  version.ref = "work" }
androidx-room-runtime      = { module = "androidx.room:room-runtime",                      version.ref = "room" }
androidx-room-ktx          = { module = "androidx.room:room-ktx",                          version.ref = "room" }
androidx-room-compiler     = { module = "androidx.room:room-compiler",                     version.ref = "room" }
androidx-datastore         = { module = "androidx.datastore:datastore-preferences",        version.ref = "datastore" }
compose-bom                = { module = "androidx.compose:compose-bom",                    version.ref = "composeBom" }
compose-ui                 = { module = "androidx.compose.ui:ui" }
compose-ui-tooling         = { module = "androidx.compose.ui:ui-tooling" }
compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
compose-material3          = { module = "androidx.compose.material3:material3" }
play-location              = { module = "com.google.android.gms:play-services-location",   version.ref = "playLocation" }
coroutines-android         = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android",version.ref = "coroutines" }
coroutines-play            = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-play-services", version.ref = "coroutines" }
serialization-json         = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json",version.ref = "serializationJson" }
junit                      = { module = "junit:junit",                                     version.ref = "junit" }
robolectric                = { module = "org.robolectric:robolectric",                     version.ref = "robolectric" }
mockk                      = { module = "io.mockk:mockk",                                  version.ref = "mockk" }
truth                      = { module = "com.google.truth:truth",                          version.ref = "truth" }
coroutines-test            = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test",   version.ref = "coroutines" }

[plugins]
android-application  = { id = "com.android.application",                  version.ref = "agp" }
compose-compiler     = { id = "org.jetbrains.kotlin.plugin.compose",      version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization",version.ref = "kotlin" }
ksp                  = { id = "com.google.devtools.ksp",                  version.ref = "ksp" }
# DİKKAT: org.jetbrains.kotlin.android BURADA YOK VE OLMAYACAK.
```

### 8.4 Kök `build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.android.application)  apply false
    alias(libs.plugins.compose.compiler)     apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp)                  apply false
}
```

Hiçbir yerde `alias(libs.plugins.kotlin.android)` **yoktur**. AGP 9 iki ayrı sert hata verir:
1. `"The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0."`
2. `android.builtInKotlin=false` eklerseniz: `"...not compatible with AGP's 9.0 new DSL (android.newDsl=true is enabled by default)... cannot be cast to BaseExtension"` — yani `android.newDsl=false` da gerekir, ve **her iki bayrak da AGP 10'da kaldırılıyor**. Kaçınma yolu: eklentiyi silmek.

### 8.5 `app/build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)     // bu, built-in Kotlin'i 2.4.20'ye yükseltir
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "tr.lifesaver"

    compileSdk { version = release(36) { minorApiLevel = 1 } }   // AGP 8.13+ sözdizimi
    buildToolsVersion = "37.0.0"                                  // ZORUNLU (§8.1)

    defaultConfig {
        applicationId = "tr.lifesaver"
        minSdk        = 26
        targetSdk     = 36
        versionCode   = 1
        versionName   = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("tr", "en")
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),
                          "proguard-rules.pro")
        }
        debug { applicationIdSuffix = ".debug"; isMinifyEnabled = false }
    }

    buildFeatures { compose = true; buildConfig = true }
    kotlin { jvmToolchain(21) }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    testOptions { unitTests { isIncludeAndroidResources = true; isReturnDefaultValues = true } }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.vm)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation)
    implementation(libs.androidx.work)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore)
    implementation(libs.play.location)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play)
    implementation(libs.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.truth)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
}
```

### 8.6 Çekirdek saflığını zorunlu kılan görev

```kotlin
// app/build.gradle.kts sonuna
val checkCorePurity by tasks.registering {
    group = "verification"
    doLast {
        val bad = fileTree("src/main/java/tr/lifesaver/core") { include("**/*.kt") }
            .filter { it.readText().contains(Regex("""^\s*import\s+android[.\s]""", RegexOption.MULTILINE)) }
            .map { it.relativeTo(projectDir).path }
        if (bad.isNotEmpty())
            throw GradleException("core/ altında android.* import var:\n" + bad.joinToString("\n"))
    }
}
tasks.named("check") { dependsOn(checkCorePurity) }
```

### 8.7 Derleme ve cihaza kurma

```bash
cd lifesaver  # deponun kök dizini

# Debug
./gradlew :app:assembleDebug
adb devices -l
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Test
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest      # cihaz/emülatör bağlıyken

# Release (imzasız üretilir; imzayı elle atıyoruz)
"$JAVA_HOME/bin/keytool" -genkeypair -v \
  -keystore "$HOME/keys/lifesaver.jks" -alias lifesaver \
  -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12

./gradlew :app:assembleRelease
BT="$ANDROID_HOME/build-tools/37.0.0"
"$BT/zipalign" -p -f 4 \
   app/build/outputs/apk/release/app-release-unsigned.apk /tmp/ls-aligned.apk
"$BT/apksigner" sign --ks "$HOME/keys/lifesaver.jks" \
   --out /tmp/lifesaver-release.apk /tmp/ls-aligned.apk
"$BT/apksigner" verify -v /tmp/lifesaver-release.apk    # v2/v3 true bekleniyor
adb install -r /tmp/lifesaver-release.apk
```

> `apksigner` JDK 21'de Conscrypt kaynaklı zararsız `restricted method in java.lang.System` uyarıları basar; yok sayın.

### 8.8 Faydalı hata ayıklama komutları

```bash
adb shell dumpsys sensorservice | head -60          # sensör envanteri, FIFO, wake-up
adb shell pm list features | grep hifi_sensors      # donanım sınıfı kapısı
adb shell dumpsys activity services tr.lifesaver    # FGS tipleri ve durumu
adb shell dumpsys power | grep -i wake              # wake lock canlı mı
adb shell dumpsys deviceidle get deep               # Doze durumu
adb shell dumpsys usagestats | grep tr.lifesaver    # App Standby bucket
adb shell cmd appops get tr.lifesaver               # tüm app-op durumları
adb shell settings get global sms_outgoing_check_max_count   # null = AOSP 30/60s
adb shell cmd role get-role-holders android.app.role.DIALER
adb shell dumpsys deviceidle whitelist | grep lifesaver

# Kill senaryolarını simüle et
adb shell cmd activity stop-app tr.lifesaver        # Görev Yöneticisi "Durdur" ile aynı
adb shell am set-standby-bucket tr.lifesaver restricted
adb shell dumpsys deviceidle force-idle             # Doze'a zorla
adb shell dumpsys battery unplug
```

---

## 9. Test planı

### 9.1 Emülatör sensör enjeksiyonu

`adb emu` yolu **telnet/auth-token dansı gerektirmez**:

```bash
adb -s emulator-5554 emu sensor status
adb -s emulator-5554 emu sensor get acceleration            # "0:9.77631:0.812349"
adb -s emulator-5554 emu sensor set acceleration 0:0:0      # serbest düşüş
adb -s emulator-5554 emu sensor set acceleration 0:294:0    # ~30 g (doyma testi)
adb -s emulator-5554 emu sensor set acceleration 0:9.81:0   # istirahat
adb -s emulator-5554 emu sensor set gyroscope 0:0:6.0       # 6 rad/s takla
adb -s emulator-5554 emu sensor set pressure 1013.25
```

**Ölçülen verim:** ardışık `adb emu` çağrıları **57 Hz** (süreç başlatma ~17,6 ms). 100–200 Hz iz oynatma için **kalıcı telnet soketi** kullanın: **~7300 komut/s**.

```python
#!/usr/bin/env python3
# tools/replay.py — 100 Hz iz oynatıcı
import socket, time, csv, pathlib, sys

TOK = (pathlib.Path.home()/".emulator_console_auth_token").read_text().strip()
s = socket.create_connection(("127.0.0.1", 5554)); s.recv(4096)
s.sendall(f"auth {TOK}\n".encode()); s.recv(4096)      # <-- raw telnet auth GEREKİR
                                                        #     ('adb emu' için gerekmez)
period = 1.0/100.0
t0 = time.perf_counter()
with open(sys.argv[1]) as f:
    for i, r in enumerate(csv.DictReader(f)):           # ax,ay,az,wx,wy,wz (m/s², rad/s)
        s.sendall(f"sensor set acceleration {r['ax']}:{r['ay']}:{r['az']}\n".encode())
        s.sendall(f"sensor set gyroscope {r['wx']}:{r['wy']}:{r['wz']}\n".encode())
        due = t0 + i*period
        while time.perf_counter() < due: pass
s.close()
```

**AVD'de etkin sensörler (doğrulandı):** acceleration, gyroscope, magnetic-field, orientation, temperature, proximity, light, pressure, humidity + uncalibrated varyantlar. **Devre dışı:** hinge-angle, heart-rate, rgbc-light, wrist-tilt, heading.

⚠️ **Fiziksel cihazda enjeksiyon imkânsızdır.** Bağlı Pixel 9 Pro `ro.build.type=user`; `cmd sensorservice help` yalnızca `get/set/reset-uid-state` sunar. `SensorManager` data-injection modu `userdebug`/`eng` build ister.

### 9.2 Sentetik sinyal birim testleri (JVM, saf çekirdek)

`core/` saf olduğu için **tüm** algılama ve tırmanma mantığı milisaniye mertebesinde test edilir. Sahte saat kullanılır, gerçek zaman beklenmez.

```kotlin
// core/src/test/.../FallDetectorTest.kt
class FallDetectorTest {
    private val clock = FakeClock()
    private val engine = DetectionEngine(EngineConfig.DEFAULT, clock)

    @Test fun `bourke3 kanonik dusme onaylanir`() {
        val trace = Synth.fall(
            restMs      = 2000,
            troughG     = 0.55f, troughMs = 220,     // < 0.65 g
            riseMs      = 180,                        // < 350 ms
            impactG     = 3.1f,  impactMs = 60,       // > 2.5 g
            omegaPeak   = 4.2f,                       // rad/s
            postureDeg  = 72f,
            stillnessMs = 12_000
        )
        val v = engine.feedAll(trace)
        assertThat(v).isNotNull()
        assertThat(v!!.scenario).isEqualTo(Scenario.FALL)
        assertThat(v.confidence).isAtLeast(70)
        assertThat(v.level).isEqualTo(Level.HIGH)
    }

    @Test fun `telefon 1 metreden dusurulunce veto edilir`() {
        val trace = Synth.objectDrop(heightM = 1.0f, saturateAtMs2 = 78.5f)  // 8 g cihaz
        engine.setOnBodyScore(0)
        assertThat(engine.feedAll(trace)).isNull()
    }

    @Test fun `merdivenden dusme veto EDILMEZ - serbest dusus var ama vucut kaniti da var`() {
        val trace = Synth.stairFall(freeFallMs = 420, impactG = 3.4f)
        engine.setOnBodyScore(8)                       // adımlar + proximity NEAR
        val v = engine.feedAll(trace)
        assertThat(v).isNotNull()                      // KOŞULLU veto çalışıyor
    }

    @Test fun `hizli oturma alarm uretmez`()      { … }   // SisFall F11 / D11
    @Test fun `kosu ve ziplama alarm uretmez`()   { … }   // SisFall D04, D18/D19
    @Test fun `bilinci acik debelenen kurban SESSIZCE ELENMEZ`() {
        val trace = Synth.fall(stillnessMs = 0, postFallMotion = true)
        val v = engine.feedAll(trace)
        assertThat(v).isNotNull()                      // hareketsizlik KAPI DEĞİL
        assertThat(v!!.level).isEqualTo(Level.MEDIUM)  // sadece güven düşer
    }
    @Test fun `sensorler kapali - motor SENSORS_DEAD bildirir`() { … }
}
```

**Tırmanma testleri (sahte saat, gerçek bekleme yok):**

```kotlin
@Test fun `arac kazasi HIGH - tam zaman cizelgesi`() {
    var st: EState = EState.Idle
    val fx = mutableListOf<EEffect>()
    fun tick(ms: Long) { clock.advance(ms); step(st, EInput.Tick(clock.now), ctx).let { st = it.first; fx += it.second } }

    st = step(st, EInput.Verdict(carCrashHigh), ctx).also { fx += it.second }.first
    assertThat(fx).contains(EEffect.StartHaptic); assertThat(fx).contains(EEffect.ShowAlarmUi)
    tick(10_000); assertThat(fx).contains(EEffect.StartSirenMax)
    tick(10_000); assertThat(fx.filterIsInstance<EEffect.SendSms>()).hasSize(6)   // 3 kişi × 2
    tick(10_000); assertThat(fx).contains(EEffect.PlaceCall(contact1))
    tick(120_000)
    assertThat(fx.filterIsInstance<EEffect.SendSms>().filter { it.to == "112" }).hasSize(1)
    tick(600_000)
    assertThat(fx.filterIsInstance<EEffect.SendSms>().filter { it.to == "112" }).hasSize(1) // TEKRAR YOK
}

@Test fun `surise devam CANLI IPTAL eder, on kosul degildir`() {
    st = step(st, EInput.Verdict(carCrashHigh), ctx).first
    clock.advance(15_000)
    st = step(st, EInput.SpeedSample(4.5f, sustainedMs = 10_000), ctx).first
    assertThat(st).isInstanceOf(EState.Cancelled::class.java)
}
```

**SMS metin testleri (kritik):**

```kotlin
@Test fun `SMS-1 en kotu durumda tek PDU ve GSM-7`() {
    val body = SmsTemplates.critical(
        name = "Ayşegül Karaküçük",       // Türkçe girdi
        event = Event.MOTO_CRASH,          // en uzun olay: 17 karakter
        graceSec = 20, lat = 41.015137, lon = 28.979530, accM = 999,
        time = LocalTime.of(14, 32)
    )
    assertThat(body).matches("[\\x20-\\x7E]+")          // saf ASCII
    assertThat(body.length).isAtMost(160)
    assertThat(Gsm7.septets(body)).isAtMost(160)        // uzantı tablosu karakteri yok
    assertThat(body).doesNotContain("ş"); assertThat(body).doesNotContain("ı")
}

@Test fun `turkce locale koordinati bozmaz`() {
    Locale.setDefault(Locale("tr","TR"))
    val body = SmsTemplates.critical(...)
    assertThat(body).contains("41.015137,28.979530")     // nokta, virgül değil
    assertThat(body).doesNotContain("41,015137")
}

@Test fun `DDM formati Locale ROOT ve 59_99995 tasmasi`() {
    Locale.setDefault(Locale("tr","TR"))
    assertThat(Ddm.format(40.9742, 28.8189)).isEqualTo("40° 58.4520' K  028° 49.1340' D")
    assertThat(Ddm.format(41.0 - 1e-9, 28.0)).startsWith("41° 00.0000' K")  // taşma korumalı
}
```

### 9.3 Veri seti oynatma (dataset replay)

| Veri seti | Yerleşim | Hız | Kullanım |
|---|---|---|---|
| **SisFall** (Sensors 2017, 17(1), 198) | Bel kemer tokası | 200 Hz | 19 ADL (D01–D19) + 15 düşme (F01–F15); regresyon çekirdeği |
| **MobiFall** | **Pantolon cebi** | ~87 Hz accel / ~200 Hz gyro | **En yakın yerleşim** — birincil kalibrasyon |
| **UMAFall** | **Sol pantolon cebi** (+ 4 SensorTag) | ~200 Hz | Cep + çok noktalı |
| **UP-Fall** | **Sağ pantolon cebi** (+ 4 nokta) | 18,4 Hz | Düşük hızda dayanıklılık |
| **FallAllD** | Boyun/bel/bilek + **barometre** | 238 Hz | Barometre terimlerini test etmek için tek veri seti |
| **KFall** | Bel altı | 100 Hz | Lider zamanı (lead time) ölçümü |

**Yöntem:**
1. Tüm izleri **100 Hz'e yeniden örnekle** (polyphase, doğrusal fazlı FIR ön-filtre ile).
2. `core/detect` motorunu doğrudan besle — Android yok, emülatör yok. 5000+ iz saniyeler içinde koşar.
3. Metrikler: duyarlılık, özgüllük, **24 saatte yanlış alarm**, ortalama lider süresi.
4. CI kapısı: SisFall'da duyarlılık ≥ %90 **ve** özgüllük ≥ %90; herhangi bir düşüş build'i kırar.

⚠️ **Metodolojik uyarı, koda yorum olarak yazılmalı:** SisFall/KFall/FallAllD **bel/göğüs/boyun** takılıdır. Pantolon cebindeki telefonun kinematiği farklıdır — uyluk hem yürürken salınır hem de **oturma ile yatmayı eğimle ayırt edemez** (Li et al.: uyluk `θ > 35°` her ikisinde de). Bu veri setleri **regresyon** içindir, saha duyarlılığı kalibrasyonu için **değil**. Cep kalibrasyonu MobiFall/UMAFall + kendi tatbikat telemetrinizden gelir.

⚠️ Ayrıca: **hiçbir kamuya açık veri seti serbest taşınan bir telefonla GERÇEK (taklit olmayan) düşme içermez.** Laboratuvar rakamları sahaya transfer olmaz. Ürün kararlarını Harari'nin gerçek-dünya rakamlarına (%73 duyarlılık, %37,5 kesinlik) göre verin, SisFall'un %98'ine göre değil.

### 9.4 Cihaz üstü (instrumented) testler

```kotlin
@Test fun `FGS her izin kombinasyonunda ya baslar ya da GORUNUR sekilde basarisiz olur`()
@Test fun `SMS sentIntent sonucu HER ZAMAN yakalanir`()      // gerçek numaraya, ikinci SIM
@Test fun `POST_NOTIFICATIONS reddedildiginde hazirlik skoru KIRMIZI`()
@Test fun `canUseFullScreenIntent false iken overlay yedegi acilir`()
@Test fun `alarm aktivitesi kilit ekraninin USTUNDE gorunur`()
@Test fun `proximity NEAR iken dokunuslar YOK SAYILIR`()
@Test fun `Vol- 2 sn basili tutma iptal eder`()
@Test fun `112 isEmergencyNumber true doner`()               // TelephonyManager, try/catch
@Test fun `tr-TR TTS mevcut degilse kanal OLU isaretlenir`()
```

**Kill/dayanıklılık matrisi (elle, her cihaz modelinde):**

| Senaryo | Komut / eylem | Beklenen |
|---|---|---|
| Doze derin uyku | `adb shell dumpsys deviceidle force-idle` | Sensör olayları akmaya devam eder |
| Görev Yöneticisi "Durdur" | `adb shell cmd activity stop-app tr.lifesaver` | ≤ 15 dk içinde watchdog dirilttir |
| Yeniden başlatma | `adb reboot` | ≤ 30 s içinde servis ayakta |
| Kilit açılmadan yeniden başlatma | reboot, PIN girme | `LOCKED_BOOT_COMPLETED` yolu çalışır |
| İzin sıfırlama simülasyonu | `adb shell pm revoke tr.lifesaver android.permission.SEND_SMS` | Hazırlık skoru kırmızı + bildirim |
| Sensörler kapalı | Hızlı ayarlardan "Sensörler kapalı" | `SENSORS_DEAD` tespiti < 20 s |
| Uçak modu | | SMS hatası doğru sınıflandırılır, kanal ölü işaretlenir |
| SIM yok | | `RESULT_RIL_SIM_ABSENT`, kullanıcıya bildirilir |
| Kısıtlanmış bucket | `adb shell am set-standby-bucket tr.lifesaver restricted` | Engelleyici uyarı |
| 30 gün simülasyonu | Cihaz tarihini ileri al | Hibernation uyarısı |

### 9.5 Zorunlu güvenli uçtan uca tatbikat

**Kural: tatbikat yapılmadan uygulama "korumaya hazır" ilan edilmez. Hazırlık maddesi #16 buna bağlıdır.**

Uygulama içinde **"Tatbikat Sihirbazı"** (`ui/drill`) 5 adımda yönetir:

**Adım 1 — Hazırlık (uygulama otomatik yapar)**
- Tüm 16 hazırlık maddesi kontrol edilir; kırmızı varsa tatbikat başlamaz.
- **Tatbikat modu** açılır: `EscalationPlan.drill()` kullanılır → 112 numarası **`112` değil**, kullanıcının kendi ikinci numarası veya `+90 555 000 00 00` gibi bir test hedefidir. Bu, kod düzeyinde ayrı bir `PhoneBook` nesnesiyle sağlanır, bir `if` ile değil.
- Tüm SMS'lerin başına `[TATBIKAT] ` ön eki eklenir (11 karakter → SMS bütçesi tatbikatta 149'a düşer, şablon otomatik kısalır).

**Adım 2 — Kişilere haber ver (uygulama otomatik yapar)**
```
LifeSaver TATBIKAT: {AD} birazdan test mesaji gonderecek.
Gercek acil durum DEGILDIR. Hicbir sey yapmaniza gerek yok.
```
Kişilerden "Tamam" yanıtı beklenir (opsiyonel, 5 dk).

**Adım 3 — Tetikleme (kullanıcı yapar)**
Kullanıcıya seçenek sunulur:
- **A. Yumuşak sim:** uygulama sentetik bir düşme izini motora besler (gerçek sensör kullanmadan). Her şey gerçek gibi çalışır, gerçek fizik yoktur. **Herkes için güvenli.**
- **B. Yatak testi:** telefon cepte, kullanıcı **bir yatağın veya kalın minderin üzerine** kontrollü şekilde yan düşer. Yalnızca sağlıklı yetişkinler; ekranda uyarı: *"Kendinizi yaralamayın. Bu test zorunlu değildir. Yumuşak simülasyon aynı şeyi doğrular."*
- **C. Araç testi:** **YAPILMAZ.** Uygulama bunu sunmaz. Araç kazası yalnızca yumuşak simülasyonla test edilir.

**Adım 4 — Gözlem (kullanıcı doğrular, uygulama zaman damgalar)**
Kontrol listesi, her biri kullanıcı tarafından işaretlenir:
- [ ] Ekran **kilitliyken** açıldı ve geri sayım göründü
- [ ] Titreşim hissedildi (telefon cepteyken)
- [ ] Siren duyuldu (telefon cepteyken, kapı kapalı bitişik odadan)
- [ ] Siren **DND açıkken** de duyuldu → DND'yi aç, tekrar et
- [ ] Fener çaktı
- [ ] Türkçe sesli anlatım anlaşıldı
- [ ] 1,5 s basılı tutarak **iptal edilebildi**
- [ ] Ses-azalt ile de iptal edilebildi
- [ ] Cepteyken ekrana dokunmak iptal **etmedi** (proximity kapısı)

**Adım 5 — Tam zincir (iptal edilmeden)**
Kullanıcı iptal etmez, zincirin sonuna kadar gitmesine izin verir.
- [ ] Kişilere `[TATBIKAT]` SMS'i **ulaştı** (kişi telefonundan doğrulanır)
- [ ] SMS'teki koordinat **doğru** (kişi haritada açıp kontrol eder, hata < 50 m)
- [ ] SMS'te Türkçe karakter **yok**, tek parça geldi (kişi "1 mesaj" gördü)
- [ ] Kişi **otomatik arandı** ve hoparlörden Türkçe anlatım duyuldu
- [ ] Test hedefine `[TATBIKAT]` "112 mesajı" ulaştı
- [ ] `ACTION_DIAL` ekranı açıldı ve **112 dolu** göründü (kullanıcı **aramaz**, geri tuşuna basar)

**Adım 6 — Rapor**
Uygulama tatbikat raporunu kaydeder (`self_tests` tablosu): her adımın zaman damgası, SMS `sentIntent`/`deliveryIntent` sonuçları, konum doğruluğu, TTS motoru adı, cihaz modeli, Android sürümü. Hazırlık maddesi #16 yeşile döner, 90 gün geçerlidir.

**ROLE_DIALER tatbikatı ayrıdır ve TEK KEZ yapılır:** kullanıcı rolü verdikten sonra uygulama **`112`'yi değil**, `TelecomManager.placeCall` ile kendi ikinci numarasını çevirir ve "otomatik arama yeteneği doğrulandı" der. **112 hiçbir tatbikatta gerçekten aranmaz.**

---

## 10. Hukuki ve etik

### 10.1 Türkiye'de asılsız çağrı sorumluluğu

**Mevzuat:** Kabahatler Kanunu (5326) **m.42/A**, başlığıyla birlikte **8/5/2025 tarih ve 7547 sayılı Kanun'un 13. maddesiyle değiştirilmiştir**. Madde başlığı artık *"Asılsız çağrı ve asılsız ihbar"*.

| Fiil | Kanuni tutar | 2026 yeniden değerleme (%25,49) | Tekrar (1 yıl içinde) |
|---|---|---|---|
| 112'yi meşgul etmek amacıyla arayıp görevliyle konuşmak **veya ısrarla çağrı bırakmak** | 1.500 TL | **≈ 1.882 TL** | ≈ 3.764 TL |
| Yapılan ihbarın asılsız olduğunun **olay yerine giden ekiplerce tutanakla tespiti** | 15.000 TL | **≈ 18.823 TL** | ≈ 37.646 TL |

Cezayı **il valileri** verir. Uygulama yönergesi: *"112 Acil Çağrı Merkezlerince Asılsız Çağrı ve Asılsız İhbarlar İçin İdari Para Cezası Uygulanmasına Dair Usul ve Esaslar Hakkında Yönerge"*. **İyi niyet istisnası yoktur.** Kanıt zinciri: personel tutanağı → amir → kalite bürosu (çağrı kayıtları/sistem kayıtları incelenir) → müdür onayı → valilik kararı → BTK/vergi dairesi üzerinden tebliğ.

**Uygulama tasarımına dört doğrudan sonucu:**

1. **112 kanalı merdivenin EN SON basamağıdır** (t+150 s ve sonrası), ve yalnızca (a) güven HIGH, (b) kullanıcı iptal etmedi, (c) hiçbir kişi "geldim" onayı vermedi koşulları birlikte sağlanınca açılır.
2. **Otomatik yeniden arama kesinlikle yasaktır.** m.42/A(1) "ısrarla çağrı bırakan" fiilini ayrıca cezalandırır. Olay başına 112 kanalı **bir kez** kullanılır.
3. **112'ye giden her mesaj makine kaynaklı olduğunu söyler ve geri arama numarası verir.** Operatörün "bu bir bot" diye kapatması, "bu asılsız ihbar" diye tutanak tutmasından iyidir.
4. **Adli günlük zorunludur.** Her tetiklemede saklanan: zaman damgası, senaryo, güven skoru ve **tüm skor bileşenleri**, ham IMU penceresi (±6 s), konum + doğruluk, gönderilen tam metinler, `sentIntent` sonuç kodları, iptal denemeleri, cihaz/OS sürümü. Valilik dosya açarsa **kullanıcının tek savunması budur**. Uygulama içinde "Olay raporu dışa aktar (PDF)" düğmesi bulunur.

⚠️ Uygulama, çağrının karşı tarafta ne duyulduğunu **kaydedemez** (telefon görüşmesi kaydı `CAPTURE_AUDIO_OUTPUT` = signature\|privileged ister). Günlük "ne söylemeye çalıştığını" kanıtlar, "ne duyulduğunu" değil. Feragatnamede bu açıkça yazılır.

**Ölçek bağlamı:** 2024'te ~103 milyon 112 çağrısının **%28,46'sı asılsız**; 2025 ilk çeyrek ~%30. Operatörler makine sesli, sessiz çağrılara karşı zaten hassas. LifeSaver'ın meşruiyeti, TTS'in **ilk cümlede** kendini tanıtmasına ve **eyleme geçirilebilir konum** vermesine bağlıdır.

### 10.2 KVKK — açık rıza ve aydınlatma metni taslağı

**Hukuki dayanak haritası:**

| Veri | İşleme anı | Hukuki sebep |
|---|---|---|
| Konum, kişi bilgileri, sensör verisi | **Onboarding'de toplama/saklama** | m.5(1) **açık rıza** (amaç bazlı, ayrı ayrı) |
| Konum, kişi bilgileri | **Olay anında iletim** | m.5(2)(b) — *"Fiili imkânsızlık nedeniyle rızasını açıklayamayacak durumda bulunan… kişinin kendisinin ya da bir başkasının hayatı veya beden bütünlüğünün korunması için zorunlu olması"* |
| Kan grubu, alerji, kronik hastalık, ilaç (**özel nitelikli**) | **Onboarding'de toplama** | m.6(3)(a) **açık rıza** — ayrı ekran, ayrı onay |
| Aynı veriler | **Olay anında 112'ye/yakına iletim, kilit ekranında gösterim** | m.6(3)(c) — m.5(2)(b) ile aynı lafız |
| — | — | ⚠️ **m.6(3)(e)** (sır saklama yükümlülüğü altındaki kişiler / yetkili kurumlar) **KULLANILAMAZ** — özel bir yazılım geliştiricisi bu kapsamda değildir. |

**Açık rıza geçerliliği (m.3(1)(a)):** *"Belirli bir konuya ilişkin, bilgilendirmeye dayanan ve özgür iradeyle açıklanan rıza."* Üçü birden olmalı. Dolayısıyla:

- **Battaniye rıza yasak.** Her amaç ayrı anahtar:
  1. `Konum verisi` — 2. `Hareket/sensör verisi` — 3. `Acil durum kişileri` — 4. `Sağlık kartı (özel nitelikli)` — 5. `Kilit açılmadan önce erişilebilir depolama (DE)` — 6. `Olay kayıtlarının cihazda saklanması` — 7. (varsa) `Sunucuya iletim`
- Rıza vermemek **uygulamanın diğer işlevlerine erişimi engellemez** (özgür irade).
- **Her an geri alınabilir**; geri alındığında işleme **durur** ve ilgili veri silinir.
- **İspat yükü veri sorumlusundadır** → her rıza olayı `consents` tablosuna yazılır: `purposeId, granted, timestampMs, textVersion, textSha256, appVersion`.

**Aydınlatma metni yapısı (m.10 zorunlu içerik):**

```
1) Veri sorumlusunun kimliği
   Ad, adres, e-posta, (varsa) VERBİS kaydı.
   "LifeSaver, {geliştirici adı} tarafından geliştirilmiştir."

2) Kişisel verilerin hangi amaçla işleneceği
   a) Düşme, araç/motosiklet kazası, denize düşme ve yanıtsızlık durumlarını
      cihaz sensörleriyle algılamak
   b) Bir olay algılandığında sizin belirlediğiniz yakınlarınıza konumunuzu
      ve durumunuzu iletmek
   c) Bilinciniz kapalıysa, olay yerine gelen kişilere ve 112 görevlilerine
      sağlık bilginizi göstermek/okumak
   d) Yanlış alarmları azaltmak için eşikleri cihazınızda iyileştirmek
   e) Asılsız ihbar iddiasına karşı olay kaydı tutmak

3) İşlenen verilerin kimlere ve hangi amaçla aktarılabileceği
   - Sizin belirlediğiniz acil durum kişilerine (SMS/arama yoluyla)
   - 112 Acil Çağrı Merkezi'ne (yalnızca merdivenin son basamağında,
     yalnızca SMS yoluyla ve/veya sizin ya da bir başkasının başlattığı arama
     sırasında sesli olarak)
   - BAŞKA HİÇ KİMSEYE. Reklam, analitik, üçüncü taraf SDK yoktur.
   - [Sunucu kullanılmıyorsa] Verileriniz cihazınızdan çıkmaz;
     tek istisna yukarıdaki iki alıcıdır.

4) Kişisel veri toplamanın yöntemi ve hukuki sebebi
   Yöntem: uygulama içi form (kişiler, sağlık kartı) ve cihaz sensörleri
           (ivmeölçer, jiroskop, barometre, GNSS, adım algılayıcı,
            yakınlık, ışık, (varsa) Bluetooth nabız bandı).
   Hukuki sebep: KVKK m.5(1) ve m.6(3)(a) uyarınca AÇIK RIZANIZ;
                 acil durum anındaki iletim bakımından ayrıca
                 KVKK m.5(2)(b) ve m.6(3)(c).

5) KVKK m.11'de sayılan haklarınız
   - Kişisel verilerinizin işlenip işlenmediğini öğrenme
   - İşlenmişse buna ilişkin bilgi talep etme
   - İşlenme amacını ve amacına uygun kullanılıp kullanılmadığını öğrenme
   - Yurt içinde/yurt dışında aktarıldığı üçüncü kişileri bilme
   - Eksik veya yanlış işlenmişse düzeltilmesini isteme
   - m.7'deki şartlar çerçevesinde silinmesini veya yok edilmesini isteme
   - Düzeltme/silme işlemlerinin aktarıldığı üçüncü kişilere bildirilmesini isteme
   - Münhasıran otomatik sistemlerle analiz edilmesi suretiyle aleyhinize
     bir sonucun ortaya çıkmasına itiraz etme
   - Kanuna aykırı işleme sebebiyle zarara uğramanız hâlinde zararın
     giderilmesini talep etme
   Başvuru: {e-posta} / uygulama içi "Verilerim" ekranı.

6) Saklama süresi
   Olay kayıtları: en fazla 50 olay veya 12 ay (hangisi önce dolarsa).
   Rıza kayıtları: rıza geri alındıktan sonra 3 yıl (ispat yükümlülüğü).
   Sağlık kartı: siz silene kadar.
   Ham sensör verisi: yalnızca olay anındaki ±6 saniye; sürekli kayıt YAPILMAZ.

7) Güvenlik tedbirleri
   Kurul'un 31/01/2018 tarihli 2018/10 sayılı kararı gereğince özel nitelikli
   veriler için: ayrı yazılı politika, erişim yetkilerinin kapsamlı ve süreli
   tanımlanması, periyodik gözden geçirme, cihaz içi şifreli depolama,
   şifreli aktarım. Uygulama içinde ayrı "Özel Nitelikli Kişisel Veri
   Politikası" belgesi bulunur.
```

**Ekstra: DE depolama rıza metni (Direct Boot için, ayrı ekran, varsayılan KAPALI):**

```
Telefonunuz yeniden başlatıldıktan sonra, siz PIN/desen girmeden ÖNCE de
yardım çağırabilmemiz için yakınlarınızın adı ve telefon numarası cihazınızda
"cihaz korumalı" alanda saklanır. Bu alan, telefon ekranı kilitliyken de
okunabilir; telefonunuz çalınırsa bu bilgilere erişilebilir.

Bu özelliği kapalı bırakırsanız: yeniden başlatma sonrası, siz telefonu
açana kadar SMS gönderilemez. Alarm, siren, fener ve "112'yi ara" ekranı
yine çalışır.

[ ] Anladım, açıyorum          [ ] Kapalı kalsın (önerilen)
```

### 10.3 Tıbbi feragat (zorunlu, hem mağaza açıklamasında hem uygulama içinde)

```
TIBBİ FERAGATNAME

LifeSaver bir TIBBİ CİHAZ DEĞİLDİR. Hiçbir hastalığı teşhis etmez,
tedavi etmez, iyileştirmez veya önlemez. Herhangi bir düzenleyici
otorite tarafından onaylanmamış, belgelendirilmemiş veya sertifikalandırılmamıştır.

LifeSaver, tıbbi bakımın, acil sağlık hizmetinin, bir sağlık
profesyonelinin ya da onaylı bir kişisel acil durum sisteminin
YERİNE GEÇMEZ.

Bu uygulama sizin adınıza 112'yi ARAMAZ. 112'yi tek dokunuşla
arayabileceğiniz bir ekran açar ve yakınlarınıza mesaj gönderir.
(İsteğe bağlı "varsayılan telefon uygulaması" iznini verdiyseniz
uygulama 112'yi kendisi çevirebilir; bu durumda bile aramanın
kurulup kurulmadığını doğrulayamaz.)

Uygulama şu durumlarda çalışmayabilir veya geç çalışabilir:
  • Telefonunuz kapalı, pili bitmiş, hasarlı veya sizden uzaktaysa
  • Şebeke, SIM, GPS veya internet erişimi yoksa
  • Telefonunuzun üreticisi uygulamayı arka planda kapattıysa
  • İzinler geri alındıysa veya "Sensörler kapalı" açıksa
  • Olay, algılama eşiklerinin altında kaldıysa

Uygulama YANLIŞ ALARM da verebilir. Türkiye'de 112'ye asılsız ihbar
idari para cezasına tabidir (Kabahatler Kanunu m.42/A).

LifeSaver BOĞULMA TESPİT ETMEZ. Su altında hiçbir işlevi çalışmaz.
Denizde AIS MOB vericisinin veya 406 MHz PLB'nin yerini TUTMAZ.

Sağlık durumunuzla ilgili her konuda bir sağlık profesyoneline danışın.
Acil durumda önce 112'yi arayın.
```

Ayrıca Play dağıtımı yapılacaksa: Play Console → App content → **Health apps declaration** formu (Medical → "Emergency and First Aid") ve **aktif, herkese açık, coğrafi kısıtsız, PDF olmayan** bir gizlilik politikası URL'si zorunludur.

### 10.4 Uygulama içi uyarılar (nerede, ne zaman)

| Yer | Uyarı |
|---|---|
| **Onboarding, ilk ekran** | Ürün cümlesi (§1.4) — "112'yi sizin yerinize aramaz" |
| Onboarding, konum adımı | Play prominent disclosure: *"Bu uygulama, kapalıyken veya kullanılmadığında da düşme/kaza algılamak ve konumunuzu yakınlarınıza iletmek için konum verisi toplar."* |
| Onboarding, SMS adımı | *"Yakınlarınıza mesaj gönderilecektir. Operatörünüz standart SMS ücreti uygulayabilir."* |
| Onboarding, sağlık kartı | Özel nitelikli veri uyarısı + m.6(3)(a) rızası |
| Onboarding, son ekran | **"Tatbikat yapmadan bu uygulamaya güvenmeyin."** + tatbikat düğmesi |
| Ana ekran, sürekli | Hazırlık rozeti: KORUMA AÇIK / KISITLI / KAPALI + kırmızı maddeler |
| Ana ekran, pil | *"LifeSaver saatte yaklaşık %2 pil kullanır."* |
| Alarm ekranı, üstte | *"Bu bir TATBİKAT değildir"* / tatbikatta *"TATBİKAT"* dev harflerle |
| 112 basamağına geçerken | *"112'ye mesaj gönderiliyor. Asılsız ihbar idari para cezasına tabidir. İptal etmek için basılı tutun."* |
| Deniz modu açılırken | AIS MOB / PLB feragati + 1-10-1 soğuk su bilgisi |
| Motosiklet modu açılırken | *"Telefon vücudunuzda veya sert bir montajda olmalıdır. Çantada/gözde serbest duran telefon yanlış alarm üretir."* |
| ROLE_DIALER isterken | *"Bu izni verirseniz LifeSaver telefonunuzun varsayılan arama uygulaması olur ve 112'yi kendisi çevirebilir. Ancak acil çağrılarda telefonun kendi arama ekranı devreye girer ve LifeSaver çağrıyı takip edemez. Bu ayarı istediğiniz zaman geri alabilirsiniz."* |
| 90 gün tatbikatsız | Bildirim: *"90 gündür tatbikat yapmadınız. LifeSaver'ın çalıştığını doğrulayın."* |
| Herhangi bir kırmızı madde | Bildirim: *"KORUMA KISITLI: {sebep}"* |
| Yanlış alarm sonrası | *"Yanlış alarm mıydı? Bize söyleyin, eşikleri iyileştirelim."* → geri bildirim + cihazda eşik ayarı |

### 10.5 Etik kırmızı çizgiler

1. **Asla "112 arandı" deme.** `placeCall` `void` döner, `ACTION_DIAL` yalnızca ekran açar. Doğru metin: *"112 arama ekranı açıldı"* / *"112'ye mesaj gönderildi (teslimat garanti değildir)"*.
2. **Asla "hayatınızı kurtarır" deme.** *"Yardımın size daha çabuk ulaşmasına yardımcı olabilir."*
3. **Sunucuya veri gönderme varsayılan olarak KAPALI.** Cihaz-içi tam işlevsellik esastır.
4. **Reklam, analitik, üçüncü taraf takip SDK'sı yok.** Bir hayat-güvenliği uygulaması pazarlama piksel'i taşımaz.
5. **Nöbet algılama 112'ye bağlanmaz** (yılda ~620 yanlış ihbar).
6. **Boğulma sınıflandırıcısı gönderilmez** (5 kişilik taklit veriyle eğitilmiş literatür).
7. **HAYAT 112 / KADES / e-Nabız'ı taklit etmez ve onlarla karıştırılmaz.** Devlet uygulamalarının bilinçli kullanıcı + e-Devlet girişi gerektirdiği, LifeSaver'ın ise **bilinçsiz kullanıcı** için olduğu açıkça anlatılır. Kullanıcı ayrıca e-Nabız "Acil sağlık verilerim" alanını doldurmaya **teşvik edilir** (Türk paramediğin gerçekten sorgulayabileceği kayıt odur).
8. **Sağlık kartı alan adları AFAD'ın "Afet ve Acil Durum Bilgi Kartı" şemasını birebir kullanır:** ADI SOYADI, DOĞUM TARİHİ, T.C. KİMLİK NO, KAN GRUBU, ADRES, ACİL DURUMDA ULAŞILACAK KİŞİ, SÜREKLİ RAHATSIZLIĞI, ALERJİSİ, KULLANDIĞI İLAÇLAR, GEÇİRDİĞİ AMELİYAT, ORGAN BAĞIŞI. (Not: ambulans EK-12 formunda **kan grubu yoktur** — hastanede yeniden bakılır. Operasyonel değeri yüksek alanlar: **alerji, kullanılan ilaç, kronik hastalık** ve *"bilinç kapalı"* ifadesi.)

---

## 11. Yapım sırası

Öncelik ilkesi: **Faz 1'in sonunda uygulama gerçekten faydalı olmalı.** Faz 1, bir insanın hayatını kurtarabilecek en küçük tam zincirdir.

### Faz 0 — İskele (2–3 gün)

- Gradle wrapper 9.0.0 → **9.7.1** (ilk iş).
- `libs.versions.toml` + kök/app build dosyaları (§8.3–8.5); `kotlin-android` eklentisini **sil**.
- `buildToolsVersion = "37.0.0"` ekle.
- `core/` paket iskeleti + `checkCorePurity` görevi.
- Boş `SentinelService` (`specialUse|health`) + boot receiver + hazırlık ekranı iskeleti.
- ✅ **Çıkış kriteri:** `./gradlew :app:assembleDebug :app:testDebugUnitTest` yeşil; APK cihaza kuruluyor; servis reboot'tan sonra ayakta.

### Faz 1 — MVP: Düşme → alarm → kişilere SMS → 112 çevirme yüzeyi (2–3 hafta)

**Bu fazın sonunda uygulama yayına alınabilir ve faydalıdır.**

| Sıra | İş | Neden bu sırada |
|---|---|---|
| 1.1 | `core/dsp` + `core/detect/FallDetector` + `DropDiscriminator` + `OnBodyContext` | Ürünün kalbi, saf Kotlin, test edilebilir |
| 1.2 | Sentetik sinyal birim testleri + SisFall/MobiFall oynatma harness'ı | Eşikler bundan sonra asla "tahmin" olmaz |
| 1.3 | `ImuSource` + `SensorLiveness` + wake lock + FGS | Motoru gerçek sensörle besle |
| 1.4 | `core/escalate` durum makinesi (saf) + zaman çizelgesi testleri | Zamanlama sahte saatle doğrulanır |
| 1.5 | `AlarmActivity`: showWhenLocked, turnScreenOn, 1,5 s basılı tut iptal, proximity kapısı, Vol− yedeği | **İptal jesti alarmdan önce yazılır** |
| 1.6 | `AlarmChannel`: siren ×2 (USAGE_ALARM + USAGE_ASSISTANCE_ACCESSIBILITY), titreşim, fener | Dikkat katmanı |
| 1.7 | `LocationSource`: 3 kaynaklı **paralel** yarış, `PRIORITY_HIGH_ACCURACY` **açıkça**, `setWaitForAccurateLocation(false)` | Konum olmadan SMS anlamsız |
| 1.8 | `core/text`: Gsm7 transliterasyon, SmsTemplates, Ddm (hepsi `Locale.ROOT`) + testler | SMS'in doğruluğu koordinatın doğruluğudur |
| 1.9 | `SmsChannel`: sentIntent/deliveryIntent **zorunlu**, sonuç sınıflandırma, sürüm-dallı `SmsManager`, hız limiti muhasebesi | En kritik sessiz-hata kaynağı |
| 1.10 | Onboarding: 16 adımlı izin sihirbazı (§6.3), Türkçe gerekçeler | İzinsiz hiçbir şey çalışmaz |
| 1.11 | Hazırlık skoru ekranı (16 madde) + rozet | Kullanıcı "koruma açık mı" bilmeli |
| 1.12 | Kişiler CRUD + `ACTION_DIAL(tel:112)` dev düğmesi | Zincirin sonu |
| 1.13 | Tatbikat sihirbazı (§9.5), tatbikat modu `PhoneBook` ayrımı | **Test edilmemiş güvenlik sistemi güvenlik sistemi değildir** |
| 1.14 | KVKK aydınlatma + amaç bazlı rıza anahtarları + tıbbi feragat | Yasal zorunluluk |
| 1.15 | Pil optimizasyonu muafiyeti + `getUnusedAppRestrictionsStatus` + OEM rehberleri | Hibernation en olası sessiz ölüm |
| 1.16 | Watchdog (AlarmManager + WorkManager) + `ApplicationExitInfo` | Diriltme |

✅ **Faz 1 çıkış kriteri:** 3 farklı OEM cihazında (mutlaka bir Xiaomi veya Samsung dahil) tam tatbikat başarılı; 7 gün kesintisiz çalışma; SisFall regresyonunda duyarlılık ≥ %90 / özgüllük ≥ %90.

### Faz 2 — Kişi araması + tam yayın + araç kazası (2 hafta)

| 2.1 | `CallChannel`: `placeCall` ile kişilerin **normal** numarasını arama, `try/catch(SecurityException)`, sabit zaman aşımı |
| 2.2 | `TtsChannel`: tr-TR kontrolü, `playSilentUtterance` duraklamalar, TTS-A/TTS-B betikleri |
| 2.3 | Sürekli yayın modu: SOS fener deseni, %25 görev döngülü siren, konum güncelleme SMS'leri, %5 pil davranışı |
| 2.4 | `CarCrashDetector` + `ActivitySource` (ActivityTransition) + GPS hız kanalı |
| 2.5 | Barometre airbag terimi (yalnız ≥ 20 Hz cihazlarda), doyma tespiti, `hifi_sensors` kapısı |
| 2.6 | Canlı "sürüşe devam etti" iptali |
| 2.7 | Gönderim sonrası 120 s iptal penceresi + "YANLIŞ ALARM" SMS'i |
| 2.8 | Olay raporu dışa aktarma (PDF) — hukuki savunma |

### Faz 3 — Direct Boot + motosiklet + dayanıklılık sertleştirme (2 hafta)

| 3.1 | Direct Boot: DE depo, `directBootAware` zinciri, opt-in rıza ekranı, `LOCKED_BOOT_COMPLETED` yolu |
| 3.2 | `MotoCrashDetector`: jiroskop entegrasyonlu yatış, tümleyici filtre, dönmeyen-yatış tetiği |
| 3.3 | Kill/dayanıklılık matrisinin tamamı (§9.4), her hedef OEM'de |
| 3.4 | Günlük öz-test + haftalık kalp atışı SMS'i |
| 3.5 | Kişiselleştirilmiş eşik ayarı: yanlış alarm geri bildirimi → cihaz-içi eşik kaydırma |
| 3.6 | Yaşlı profili (tüm ivme eşikleri ×0,90) |

### Faz 4 — Deniz modu + dead-man switch (2 hafta)

| 4.1 | `MarineDetector` telefon-only: serbest düşüş/takla tetiği, 8 s geri sayım |
| 4.2 | Daldırma teyidi (barometre) — **cihaza özel kova testi kalibrasyonu zorunlu** |
| 4.3 | Sürekli iz kaydı (5 s, 6 saat halka tampon) |
| 4.4 | DDM birincil görüntü + MAYDAY şablonu + AAKKM/158 numaraları |
| 4.5 | `UnresponsiveDetector` dead-man switch: FGS içi zamanlayıcı, taşınıyor-mu sınıflandırıcı, kişiselleştirilmiş taban çizgisi |
| 4.6 | Islak ekran senaryosu testleri |

### Faz 5 — Opsiyonel donanım ve yetenekler (bekleyebilir)

| 5.1 | BLE MOB fob tether: 500 ms heartbeat, 4 kayıp = MOB (CDM **değil**) |
| 5.2 | BLE Heart Rate Service (0x180D) nabız kaybı: temas bitleri, 15 s pencere, sıkıştırılmış merdiven |
| 5.3 | `ROLE_DIALER` yolu: `ACTION_DIAL` aktivitesi, `tel:` aktivitesi, `InCallService`, açık kullanıcı onayı, ayrı tatbikat |
| 5.4 | HTTPS relay (opsiyonel sunucu) — varsayılan kapalı |
| 5.5 | Sesli iptal ("İyiyim") — cihaz-içi tr-TR model kontrolü, yalnız aktivite görünürken |
| 5.6 | Kamera-PPG bilinçli-kullanıcı kendi kendini kontrol akışı |
| 5.7 | Plus Code ikincil konum gösterimi (Türk 112'sinin kabul ettiğine dair kanıt yok — **her zaman ek, asla yerine**) |
| 5.8 | YAMNet ses sınıflandırma (yalnız aktivite görünürken; doğru indeksler §3.3) |
| 5.9 | Wear OS yardımcı modülü — ⚠️ `READ_HEART_RATE` / `READ_HEALTH_DATA_IN_BACKGROUND` gerektirir, Play'in Health Permissions politikası (ölüm/yaralanma maddesi + "headless app" yasağı) doğrudan devreye girer. **Play dağıtımı planlanıyorsa bu maddeyi hukukçuyla değerlendirin.** |

### Asla yapılmayacaklar

- Boğulma sınıflandırıcısı.
- Nöbet algılamanın 112'ye bağlanması.
- 112'nin otomatik yeniden aranması.
- Health Connect'in tetik olarak kullanılması.
- `QUERY_ALL_PACKAGES`.
- Uplink ses enjeksiyonu için root/OEM hack'i.
- Uygulamanın "112'yi arar" diye tanıtılması.

---

## Ek A — Doğrulama sonrası düzeltilen, kolayca tekrar yapılabilecek 12 hata

| # | Yanlış | Doğru |
|---|---|---|
| 1 | "Hiçbir uygulama 112'yi otomatik arayamaz" | `ROLE_DIALER` (kullanıcının verebileceği rol) 112'yi çevirir; kaybedilen şey **çağrı gözlemlenebilirliğidir**, çağrının kendisi değil |
| 2 | "CALL_PHONE burada işe yaramaz" | Yakının **normal** numarasını FGS'ten otomatik aramak en güçlü otonom kanaldır |
| 3 | `ForegroundServiceTypeNotAllowedException` | Böyle bir public sınıf yok: `ForegroundServiceTypeException`, `Invalid…`, `Missing…`, `ForegroundServiceStartNotAllowedException` |
| 4 | `EXTRA_START_CALL_WITH_SPEAKERPHONE`'u `ACTION_CALL` intent'inin köküne koymak | `EXTRA_OUTGOING_CALL_EXTRAS` bundle'ının **içine** (placeCall'da doğrudan extras bundle'ına) |
| 5 | Ayarlar deep link'lerini `data`sız açmak | `Uri.fromParts("package", pkg, null)` zorunlu, yoksa `No activities found` |
| 6 | YAMNet 439 = "Glass" | 439 = **"Splash, splatter"**; Glass = **435**; 300 = Motor vehicle; 302 = Vehicle horn |
| 7 | `TYPE_STATIONARY_DETECT` / `MOTION_DETECT` API 26 | **API 24** — ve Pixel'de standart tip olarak **yok** (vendor 65554/65555) |
| 8 | Hup et al. HR = 0,475 | **0,50** (%95 GA 0,39–0,65); n = 26 kayıtlı / 25 analiz |
| 9 | Batching sample kaybetmez | Non-wake-up + AP suspend = **eski olaylar düşer**. `maxReportLatencyUs = 0` |
| 10 | `systemExempted` FGS erişilemez | Platform, Doze izin listesinde olan **veya** `SCHEDULE_EXACT_ALARM` tutan uygulamaya izin verir; engelleyen **Play politikasıdır**, sideload'da meşru bir seçenektir |
| 11 | Hareketsizlik bir VE-kapısı | Bilinci açık, debelenen kurbanı **sessizce eler**; yalnızca güven/kısaltma terimidir |
| 12 | `1000–3000 Hz` sweep "ISO 7731'in 500–2500 Hz bandı içinde" | Çelişkili. **1000 → 2500 Hz**, 200 ms yukarı + 200 ms aşağı = **2,5 Hz** çevrim |

---

## Ek B — Türkiye numaraları ve resmî kanallar (uygulamada sabit)

| Kanal | Numara / adres | Not |
|---|---|---|
| Tüm acil durumlar | **112** | Tek numara; 110/155/156/122/177/158 buraya taşındı (81 il, Haziran 2021) |
| 112 SMS ihbarı | **112**'ye SMS | Ücretsiz, SIM zorunlu, gönderildiği ilin merkezine yönlenir, **otomatik konum YOK** → koordinat gövdede olmalı |
| Sahil Güvenlik | **158** / denizden-yurtdışından **+90 312 158 00 00** | 112'ye entegre |
| AAKKM (Türk MRCC) | **+90 312 231 91 05**, **+90 312 232 47 83** (24 sa), faks +90 312 232 08 23, trmrcc@uab.gov.tr | Denizde arama-kurtarma koordinasyonu |
| Kıyı Emniyeti / Türk Radyo | 0212 598 95 20 | Sahil telsiz istasyonları |
| VHF | Ch16 (156,800 MHz) sesli MAYDAY, Ch70 (156,525 MHz) DSC | Uygulama **veremez**, kullanıcıya talimat verir |
| Devlet uygulamaları | HAYAT 112 (`com.bakanlik112.mobil.ng112mobil`), Engelsiz 112, KADES, e-Nabız | Üçüncü taraf API'si **yok**; programatik devir imkânsız. Kullanıcı bunları da kurmaya teşvik edilir. |

**AML/ELS durumu:** İçişleri Bakanlığı Google ELS'in **Haziran 2023'ten beri 79 ilde** devrede olduğunu ve ~30 m hassasiyet sağladığını yazıyor; Google'ın kendi ülke listesinde Türkiye **yok**. Ayrıca ELS **yalnızca varsayılan telefon uygulamasıyla** yapılan aramalarda çalışır. **Sonuç: LifeSaver 112'nin konumu otomatik aldığını ASLA varsaymaz.** Kendi koordinatını SMS'e yazar, ekranda gösterir ve (mümkünse) sesli okur.