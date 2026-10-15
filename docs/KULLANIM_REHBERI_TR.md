# Android FileLogger: Sıfırdan Kurulum ve Kullanım Rehberi

Bu rehber, Android projesine daha önce harici bir kütüphane eklememiş birinin
bile FileLogger'ı kurup kullanabilmesi için hazırlanmıştır.

Bu işlemlerin sonunda uygulamanız:

- logları Logcat yanında uygulamanın özel klasörüne yazabilecek,
- hata anındaki son kayıtları koruyabilecek,
- logları ZIP dosyası hâline getirebilecek,
- kullanıcıya Gmail veya Outlook üzerinden destek adresine göndertebilecek,
- isteğe bağlı olarak logları şirket sunucusu, Crashlytics veya OpenTelemetry'ye aktarabilecek,
- hassas verileri production sürümünde maskeleyebilecektir.

## 1. FileLogger Ne İşe Yarar?

Normal Android `Log.d()` kayıtları çoğunlukla yalnızca cihaz bilgisayara bağlıyken
görülebilir. Kullanıcı iki gün önce hata yaşadıysa eski Logcat kayıtlarına ulaşmak
genellikle mümkün değildir.

FileLogger kayıtları uygulamanın kendi özel depolama alanında tutar. Kullanıcı
"Destek loglarını gönder" butonuna bastığında bu kayıtlar ZIP yapılabilir ve
e-posta uygulamasıyla geliştiriciye gönderilebilir.

FileLogger bir crash reporting servisi, e-posta sunucusu veya kullanıcı takip
aracı değildir. Logların ne zaman toplanacağına ve paylaşılacağına uygulama karar
verir.

### Tek APK mı, birden fazla APK mı?

FileLogger modülleri ayrı uygulamalar değildir. Ana uygulamanız sonunda yine tek
APK veya AAB üretir. Gradle'a yalnızca kullanacağınız parçaları eklersiniz:

- Sadece cihazda dosya ve e-posta paylaşımı: `filelogger`
- Ağ isteklerini de kaydetmek: `filelogger` + `filelogger-okhttp`
- Şirket sunucusuna göndermek: `filelogger` + `filelogger-remote`
- Firebase hata ekranında görmek: `filelogger` + `filelogger-crashlytics`
- Kurumsal gözlemlenebilirlik sistemi: `filelogger` + `filelogger-otel`
- Uygulama içinde kayıt ekranı: `filelogger` + `filelogger-ui`

Kullanmadığınız modülü eklemezsiniz. Böylece gereksiz SDK ve kod uygulamanıza girmez.

## 2. Gereksinimler

- Android Studio
- Android `minSdk 24` veya üzeri
- Modern Android projeleri için JDK 17 önerilir
- İnternet bağlantısı (kütüphaneyi ilk kez indirirken)
- Gradle Kotlin DSL (`.gradle.kts`) veya Groovy (`.gradle`) kullanılabilir

Kütüphanenin mevcut sürümü:

```text
1.0.0-rc6
```

`rc`, release candidate anlamına gelir. Kütüphane kapsamlı testlerden geçmiştir
fakat final `1.0.0` etiketi henüz verilmemiştir.

> **Yayın durumu:** `filelogger`, `filelogger-core`, `filelogger-okhttp`,
> `filelogger-remote`, `filelogger-crashlytics`, `filelogger-otel` ve
> `filelogger-ui` modülleri `1.0.0-rc6` sürümünün parçalarıdır.

## 3. Maven Central Deposunu Kontrol Edin

Android Studio'da proje kökündeki `settings.gradle.kts` dosyasını açın.

`dependencyResolutionManagement` bölümündeki `repositories` listesinde
`google()` ve `mavenCentral()` bulunduğunu kontrol edin:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        google()
        mavenCentral()
    }
}
```

Groovy kullanan projelerde aynı ayar şöyledir:

```groovy
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        google()
        mavenCentral()
    }
}
```

Bu işlem Gradle'a FileLogger paketini Maven Central üzerinden aramasını söyler.
Ek bir özel repository adresi gerekmez.

## 4. Kütüphaneyi Uygulama Modülüne Ekleyin

Genellikle düzenlemeniz gereken dosya şudur:

```text
app/build.gradle.kts
```

`dependencies` bölümüne aşağıdaki satırı ekleyin:

```kotlin
dependencies {
    implementation(
        "io.github.sevcanucan:filelogger:1.0.0-rc6"
    )
}
```

Groovy karşılığı:

```groovy
dependencies {
    implementation 'io.github.sevcanucan:filelogger:1.0.0-rc6'
}
```

İlk kurulumda yalnızca `filelogger` paketini ekleyin. Aşağıdaki modüller
zorunlu değildir:

```kotlin
// OkHttp istek ve cevaplarını loglamak için:
implementation(
    "io.github.sevcanucan:filelogger-okhttp:1.0.0-rc6"
)

// Logları kendi HTTPS sunucunuza göndermek için:
implementation(
    "io.github.sevcanucan:filelogger-remote:1.0.0-rc6"
)

// Firebase Crashlytics'e WARN/ERROR göndermek için:
implementation(
    "io.github.sevcanucan:filelogger-crashlytics:1.0.0-rc6"
)

// OpenTelemetry pipeline'ına structured log göndermek için:
implementation(
    "io.github.sevcanucan:filelogger-otel:1.0.0-rc6"
)

// Compose içinde log görüntüleme ekranı için:
implementation(
    "io.github.sevcanucan:filelogger-ui:1.0.0-rc6"
)

// Android/JVM/iOS/Wasm ortak kodundan loglama için:
implementation(
    "io.github.sevcanucan:filelogger-core:1.0.0-rc6"
)
```

Dosyayı düzenledikten sonra Android Studio'nun üst kısmındaki **Sync Now**
bağlantısına basın. Senkronizasyon bitmeden diğer adımlara geçmeyin.

### JitPack alternatifi

Eski kurulumunu hemen değiştiremeyen projeler için JitPack desteği korunur:

```kotlin
repositories { maven("https://jitpack.io") }

dependencies {
    implementation(
        "com.github.SevcanUcan.android-file-logger:filelogger:1.0.0-rc4"
    )
}
```

Yeni entegrasyonlarda üstteki `io.github.sevcanucan` Maven Central koordinatını
kullanın. Aynı modülün Maven Central ve JitPack bağımlılıklarını birlikte eklemeyin.

## 5. Application Sınıfını Oluşturun

FileLogger uygulama açılırken yalnızca bir kez kurulmalıdır.

`App.kt` adında bir dosya oluşturun. Paket adını kendi projenize göre değiştirin:

```kotlin
package com.example.myapp

import android.app.Application
import com.filelogger.FileLogger
import com.filelogger.LoggerConfig

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        FileLogger.install(
            context = this,
            config = if (BuildConfig.DEBUG) {
                LoggerConfig.dev()
            } else {
                LoggerConfig.prod()
            }
        )
    }
}
```

Buradaki davranış:

- Debug APK'da `LoggerConfig.dev()` kullanılır.
- Release APK'da `LoggerConfig.prod()` kullanılır.
- Debug yapılandırması `DEBUG` ve üzerindeki kayıtları düz metin yazar.
- Production yapılandırması `WARN` ve `ERROR` kayıtlarını JSON yazar.
- Production yapılandırması e-posta, token, parola ve benzeri değerleri maskeler.

## 6. Application Sınıfını Manifest'e Tanıtın

`app/src/main/AndroidManifest.xml` dosyasını açın.

`application` etiketine `android:name` ekleyin:

```xml
<application
    android:name=".App"
    android:icon="@mipmap/ic_launcher"
    android:label="@string/app_name"
    android:theme="@style/Theme.MyApp">

    <!-- Activity tanımları burada kalır. -->

</application>
```

Projede zaten özel bir `Application` sınıfı varsa ikinci bir sınıf oluşturmayın.
Mevcut sınıfın `onCreate()` metoduna `FileLogger.install(...)` çağrısını ekleyin.

## 7. İlk Logunuzu Yazın

Artık Activity, Fragment, ViewModel, Worker veya Repository içinden log
yazabilirsiniz:

```kotlin
import com.filelogger.FileLogger

FileLogger.d("MainActivity", "Ana ekran açıldı")
FileLogger.i("Sync", "Senkronizasyon başladı")
FileLogger.w("Network", "İstek tekrar denenecek")
FileLogger.e("Payment", "Ödeme sırasında hata oluştu", exception)
```

Seviyelerin anlamı:

| Seviye | Ne zaman kullanılır? |
| --- | --- |
| `DEBUG` | Geliştirme sırasında ayrıntılı takip |
| `INFO` | Normal ve önemli uygulama olayları |
| `WARN` | Uygulama çalışıyor ama olağan dışı durum var |
| `ERROR` | İşlem başarısız veya exception oluştu |

Production yapılandırmasının varsayılan seviyesi `WARN` olduğu için release
APK'da `DEBUG` ve `INFO` kayıtlarının dosyada görünmemesi normaldir.

Production'da `INFO` kayıtlarını da tutmak isterseniz:

```kotlin
val productionConfig = LoggerConfig.prod().copy(
    minimumLogLevel = LogLevel.INFO
)

FileLogger.install(this, productionConfig)
```

## 8. Loglara Ek Bilgi Ekleyin

Uygulama sürümü gibi sürekli bilgileri bir kez ekleyebilirsiniz:

```kotlin
FileLogger.putContext(
    mapOf(
        "appVersion" to BuildConfig.VERSION_NAME,
        "buildType" to BuildConfig.BUILD_TYPE
    )
)
```

Tek bir olaya özel bilgi eklemek için:

```kotlin
FileLogger.i(
    tag = "Checkout",
    message = "Sipariş gönderildi",
    attributes = mapOf(
        "provider" to "card",
        "attempt" to "2"
    )
)
```

Parola, erişim token'ı, kredi kartı, kimlik numarası veya açık kullanıcı verisini
loglamayın. Otomatik maskeleme ikinci güvenlik katmanıdır; yanlış veriyi loglamamak
birinci güvenlik katmanıdır.

## 9. Kullanıcı Hareketlerini Breadcrumb Olarak Kaydedin

Breadcrumb, tam log kaydı yerine kullanıcının hangi adımları izlediğini gösteren
kısa bir izdir:

```kotlin
FileLogger.addBreadcrumb(
    message = "Ayarlar ekranı açıldı",
    category = "navigation"
)

FileLogger.addBreadcrumb(
    message = "Tekrar dene butonuna basıldı",
    category = "user-action",
    attributes = mapOf("screen" to "sync")
)
```

Bu kayıtlar destek raporundaki `breadcrumbs.json` dosyasına eklenir.

## 10. Log ZIP'ini Paylaşmak İçin FileProvider Ekleyin

Android, uygulamanın özel dosyasını doğrudan başka uygulamalara vermez.
`FileProvider`, ZIP dosyasını geçici ve güvenli bir URI ile paylaşır.

Manifest'teki `application` etiketinin içine şunu ekleyin:

```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.filelogger.files"
    android:exported="false"
    android:grantUriPermissions="true">

    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/filelogger_paths" />
</provider>
```

Şimdi şu dosyayı oluşturun:

```text
app/src/main/res/xml/filelogger_paths.xml
```

İçeriği:

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths xmlns:android="http://schemas.android.com/apk/res/android">
    <files-path
        name="log_exports"
        path="log_exports/" />
</paths>
```

Manifest ve Kotlin kodundaki authority aynı olmalıdır:

```text
${applicationId}.filelogger.files
```

FileLogger için depolama izni istemeniz gerekmez. Dosyalar uygulamanın özel
alanında tutulur.

## 11. Destek Raporunu E-posta ile Gönderin

Activity içine aşağıdaki fonksiyonu ekleyebilirsiniz:

```kotlin
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.filelogger.FileLogger
import com.filelogger.SupportReportOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun sendLogsToSupport() {
    lifecycleScope.launch(Dispatchers.IO) {
        runCatching {
            val archive = FileLogger.createSupportReport(
                SupportReportOptions(
                    userNote = "Kullanıcının destek açıklaması",
                    sections = mapOf(
                        "screen" to "settings",
                        "sync-state" to "pending"
                    )
                )
            )

            val uri = FileProvider.getUriForFile(
                this@MainActivity,
                "$packageName.filelogger.files",
                archive
            )

            FileLogger.createShareIntent(uri).apply {
                putExtra(
                    Intent.EXTRA_EMAIL,
                    arrayOf("support@uygulamaadi.com")
                )
                putExtra(
                    Intent.EXTRA_SUBJECT,
                    "Uygulama destek logları"
                )
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Sorun incelemesi için destek raporu ektedir."
                )
            }
        }.onSuccess { shareIntent ->
            withContext(Dispatchers.Main) {
                startActivity(
                    Intent.createChooser(
                        shareIntent,
                        "Logları gönder"
                    )
                )
            }
        }.onFailure { error ->
            FileLogger.e(
                "Support",
                "Destek raporu oluşturulamadı",
                error
            )
        }
    }
}
```

Sonra butonun `onClick` bölümünde çağırın:

```kotlin
Button(onClick = ::sendLogsToSupport) {
    Text("Destek loglarını gönder")
}
```

Bu butona basılınca:

1. Kuyrukta bekleyen loglar dosyaya yazılır.
2. Loglar ve tanılama bilgileri ZIP dosyasına eklenir.
3. Android paylaşım ekranı açılır.
4. Kullanıcı Gmail veya Outlook'u seçer.
5. ZIP dosyası e-postaya eklenmiş olarak görünür.
6. Son gönderme işlemini kullanıcı yapar.

Bazı e-posta uygulamaları güvenlik veya uygulama politikası nedeniyle hazır alıcı
adresini göstermeyebilir. ZIP eki yine paylaşılır; kullanıcı adresi elle seçebilir.

## 12. Destek ZIP'inin İçinde Ne Var?

`createSupportReport()` ile oluşturulan ZIP genel olarak şunları içerir:

```text
app_log.txt
app_log.txt.1
diagnostics.json
support-report.json
breadcrumbs.json
user-note.txt
sections/screen.txt
sections/sync-state.txt
```

Kalıcı cihaz kimliği, reklam kimliği, hesap bilgisi, konum, IP veya MAC adresi
otomatik olarak toplanmaz.

Yalnızca log dosyalarını ZIP yapmak isterseniz:

```kotlin
val archive = FileLogger.exportLogs(
    redactor = LogRedactor.DEFAULT_SENSITIVE
)
```

## 13. Kullanıcı İzni ve Logları Silme

Uygulamanızda log toplama izni varsa bunu FileLogger'a bağlayabilirsiniz:

```kotlin
FileLogger.setCollectionEnabled(consentGranted)
```

Kullanıcı iznini geri çektiğinde tüm logları da silmek için:

```kotlin
FileLogger.setCollectionEnabled(
    enabled = false,
    deleteExistingData = true
)
```

Hesap silme akışında:

```kotlin
FileLogger.deleteAllLogs(includeExports = true)
```

Bu davranışı gizlilik politikanızda açıkça anlatın.

## 14. Saklanan Logları Uygulama İçinden Okuma

Sadece hata kayıtlarını okumak için:

```kotlin
val errors = FileLogger.readLogs(
    LogQuery(
        levels = setOf(LogLevel.ERROR),
        limit = 200
    )
)
```

Etiket ve kelimeyle filtreleme:

```kotlin
val results = FileLogger.readLogs(
    LogQuery(
        tags = setOf("Sync"),
        keyword = "failed",
        limit = 100
    )
)
```

## 15. OkHttp Loglama

Önce `filelogger-okhttp` bağımlılığını ekleyin. Sonra interceptor'ı OkHttp
istemcisine bağlayın:

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(
        FileLoggerInterceptor(
            config = NetworkLogConfig()
        )
    )
    .build()
```

Body loglama varsayılan olarak kapalıdır. Authorization, cookie ve API key
başlıkları maskelenir. Production ortamında hassas request/response body'lerini
loglamayın.

## 16. Compose Log Görüntüleyici

Önce `filelogger-ui` bağımlılığını ekleyin. Sonra bir Compose ekranında:

```kotlin
@Composable
fun LogsScreen() {
    FileLoggerViewer()
}
```

Bu ekran seviye filtresi, arama, yenileme ve exception ayrıntılarını gösterir.
Production uygulamasında bu ekranı yalnızca geliştirici modu veya güvenli destek
akışı arkasında açmanız önerilir.

## 17. Otomatik Uzak Gönderim

APK içine Gmail, SMTP veya e-posta parolası koymayın. APK içindeki bir parola
çıkarılabilir ve kötüye kullanılabilir.

Otomatik log gönderimi gerekiyorsa önerilen yapı:

```text
FileLogger
    -> filelogger-remote
    -> HTTPS backend
    -> güvenli log deposu
    -> geliştiriciye bildirim/e-posta
```

`filelogger-remote` batch, gzip, disk spool ve retry sağlar. Gerçek HTTPS isteğini,
kimlik doğrulamasını, sertifika politikasını ve sunucu tarafını uygulama sahibi
oluşturur.

### 17.1 Firebase Crashlytics'e Gönderim

Bu seçenek özellikle mobil crash ve non-fatal hata takibi isteyen ekipler içindir.
Önce ana uygulamanızda Firebase ve Crashlytics kurulumunu tamamlayın. Uygulamada
`google-services.json` bulunmalı ve resmi Crashlytics kurulumu çalışıyor olmalıdır.
Güncel kurulum adımları için
[Firebase Crashlytics Android dokümanını](https://firebase.google.com/docs/crashlytics/android/get-started)
kullanın.

Modülü ekleyin:

```kotlin
implementation(
    "io.github.sevcanucan:filelogger-crashlytics:1.0.0-rc6"
)
```

Sonra destination oluşturup FileLogger yapılandırmasına ekleyin:

```kotlin
import com.filelogger.LogLevel
import com.filelogger.crashlytics.CrashlyticsLogConfig
import com.filelogger.crashlytics.CrashlyticsLogDestination

val crashlyticsDestination = CrashlyticsLogDestination(
    config = CrashlyticsLogConfig(
        minimumLogLevel = LogLevel.WARN,
        recordNonFatalExceptions = true
    )
)

FileLogger.install(
    context = this,
    config = LoggerConfig.prod().copy(
        customDestinations = listOf(crashlyticsDestination)
    )
)
```

Varsayılan olarak düşük önemdeki kayıtlar Crashlytics'e gönderilmez. Mesajlar ve
metadata boyut olarak sınırlandırılır. FileLogger'ın production redactor'ı
destination çalışmadan önce hassas değerleri maskeler.

Crashlytics, cihazdaki tam log arşivinin yerine geçmez. Tam ZIP dosyası gerektiğinde
`createSupportReport()` kullanılmaya devam edilir.

### 17.2 OpenTelemetry'ye Gönderim

Bu seçenek büyük şirketler, kendi Collector sistemini kullanan ekipler ve logları
trace/metric verileriyle ilişkilendirmek isteyen uygulamalar içindir.

Modülü ekleyin:

```kotlin
implementation(
    "io.github.sevcanucan:filelogger-otel:1.0.0-rc6"
)
```

Ana uygulama OpenTelemetry SDK, OTLP exporter, endpoint ve authentication ayarlarını
kendi güvenlik politikasına göre oluşturur. FileLogger hazır `OpenTelemetry`
nesnesini kullanır:

Exporter seçenekleri ve endpoint ayarları için
[OpenTelemetry OTLP yapılandırma dokümanına](https://opentelemetry.io/docs/languages/sdk-configuration/otlp-exporter/)
bakın.

OpenTelemetry Java kullanırken ana Android uygulamasında core library desugaring
açılmalıdır. `app/build.gradle.kts` içinde:

```kotlin
android {
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")
}
```

Güncel sürüm ve yapılandırma için
[Android desugaring dokümanını](https://developer.android.com/studio/write/java8-support)
kontrol edin.

```kotlin
import com.filelogger.LogLevel
import com.filelogger.otel.OpenTelemetryLogConfig
import com.filelogger.otel.OpenTelemetryLogDestination

val otelDestination = OpenTelemetryLogDestination(
    openTelemetry = openTelemetrySdk,
    config = OpenTelemetryLogConfig(
        minimumLogLevel = LogLevel.INFO,
        instrumentationVersion = BuildConfig.VERSION_NAME
    )
)

FileLogger.install(
    context = this,
    config = LoggerConfig.prod().copy(
        customDestinations = listOf(otelDestination)
    )
)
```

`openTelemetrySdk` burada uygulamanızın önceden hazırladığı OpenTelemetry
instance'ıdır. FileLogger sunucu adresini veya gizli anahtarı kendi içinde tutmaz.

### 17.3 Birden Fazla Hedefi Birlikte Kullanma

Aynı uygulamada birden fazla destination kullanılabilir:

```kotlin
FileLogger.install(
    context = this,
    config = LoggerConfig.prod().copy(
        customDestinations = listOf(
            remoteDestination,
            crashlyticsDestination,
            otelDestination
        )
    )
)
```

Bunlar ayrı APK değildir. Hepsi ana uygulamanın tek APK/AAB dosyasına eklenir.
Kullanmadığınız modülü dependencies listesine koymayın.

### 17.4 Hangi Seçeneği Kullanmalıyım?

| Uygulama tipi | Önerilen yapı |
| --- | --- |
| Küçük uygulama | `filelogger` + kullanıcı kontrollü ZIP/e-posta |
| Orta ölçekli uygulama | `filelogger` + `filelogger-crashlytics` |
| Kendi backend'i olan uygulama | `filelogger` + `filelogger-remote` |
| Büyük/kurumsal uygulama | `filelogger` + `filelogger-otel` + gerekirse Crashlytics |
| İnternet erişimi sınırlı uygulama | Yerel dosya + kullanıcı kontrollü export |

SMTP parolası, OTLP gizli anahtarı veya yönetici seviyesinde backend anahtarı APK
içine gömülmemelidir. Mobil uygulamaya yalnızca sınırlandırılmış ve gerektiğinde
iptal edilebilir kimlik bilgileri verilmelidir.

## 18. Projeyi Derleme

Android Studio'da:

1. **File > Sync Project with Gradle Files** seçin.
2. **Build > Make Project** seçin.
3. Uygulamayı bir emülatör veya gerçek cihazda çalıştırın.

Windows terminalinde debug APK üretmek için:

```powershell
.\gradlew.bat assembleDebug
```

Unit testleri çalıştırmak için:

```powershell
.\gradlew.bat test
```

Lint kontrolü için:

```powershell
.\gradlew.bat lint
```

Release üretmek için uygulamanızın kendi signing yapılandırması gerekir:

```powershell
.\gradlew.bat bundleRelease
```

FileLogger sizin uygulamanızı imzalamaz. Play Store signing ayarları her uygulama
projesinde ayrı yapılmalıdır.

## 19. Log Dosyaları Nerede?

Loglar uygulamanın internal files klasöründe tutulur. Genel yapı şöyledir:

```text
files/
    logs/
        session_20261012_103000_123/
            app_log.txt
            app_log.txt.1
    log_exports/
        SupportReport_20261012_103500_456.zip
```

Bu klasör diğer uygulamalara açık değildir. Root olmayan production cihazda
kullanıcı dosya yöneticisiyle doğrudan göremeyebilir. Bu nedenle export/share API'si
kullanılır.

## 20. Sık Görülen Hatalar

### `Could not resolve ... filelogger`

Kontrol edin:

- `settings.gradle.kts` içinde `google()` ve `mavenCentral()` var mı?
- İnternet bağlantısı çalışıyor mu?
- Sürüm tam olarak `1.0.0-rc6` mü?
- Gradle Offline Mode kapalı mı?
- **Sync Project with Gradle Files** çalıştırıldı mı?

### `Unresolved reference: FileLogger`

Bağımlılığın `app/build.gradle.kts` içine eklendiğini ve import'un bulunduğunu
kontrol edin:

```kotlin
import com.filelogger.FileLogger
```

### `FileLogger.init(context) must be called before logging`

`FileLogger.install(...)` çağrısı çalışmamıştır. `Application` sınıfını manifest'e
eklediğinizi kontrol edin.

### Release APK'da DEBUG log görünmüyor

Bu normaldir. `LoggerConfig.prod()` varsayılan olarak `WARN` ve üzerini kaydeder.

### `Failed to find configured root` veya FileProvider hatası

Kontrol edin:

- `filelogger_paths.xml` gerçekten `res/xml` altında mı?
- XML içinde `log_exports/` yolu var mı?
- Manifest authority değeri `${applicationId}.filelogger.files` mı?
- Kotlin tarafında `"$packageName.filelogger.files"` kullanılıyor mu?

### E-posta açılıyor ama ZIP eklenmiyor

`Intent.FLAG_GRANT_READ_URI_PERMISSION` gereklidir. Bu flag
`FileLogger.createShareIntent(uri)` tarafından eklenir. Intent'i sıfırdan oluşturmak
yerine bu yardımcı metodu kullanın.

### Uygulama kapandığında son log kayboluyor

Normal kapanış veya kritik işlem öncesinde:

```kotlin
FileLogger.flush()
```

Uygulama runtime'ını kontrollü kapatmak için:

```kotlin
FileLogger.shutdown()
```

Ani güç kaybı, native crash, force-stop ve işletim sistemi tarafından anlık process
öldürme durumlarında hiçbir Android logger yüzde yüz garanti veremez.

## 21. Yayın Öncesi Kontrol Listesi

- [ ] `google()` ve `mavenCentral()` repository'leri tanımlı.
- [ ] `filelogger` bağımlılığı eklendi.
- [ ] Gradle Sync başarılı.
- [ ] `Application` içinde FileLogger kuruldu.
- [ ] Application sınıfı manifest'e yazıldı.
- [ ] Debug logları cihazda test edildi.
- [ ] Production log seviyesi bilinçli seçildi.
- [ ] FileProvider manifest'e eklendi.
- [ ] `filelogger_paths.xml` oluşturuldu.
- [ ] Destek ZIP'i Gmail/Outlook ile test edildi.
- [ ] Kullanılan destination modülleri bilinçli olarak seçildi.
- [ ] Crashlytics kullanılıyorsa test non-fatal kaydı Firebase panelinde görüldü.
- [ ] OpenTelemetry kullanılıyorsa test logu Collector/backend üzerinde görüldü.
- [ ] Remote/OTLP kimlik bilgileri APK içine kalıcı yönetici sırrı olarak yazılmadı.
- [ ] Hassas veri içeren log satırları kaldırıldı.
- [ ] Kullanıcı izni ve silme akışı bağlandı.
- [ ] Gizlilik politikası güncellendi.
- [ ] Release APK/AAB üzerinde gerçek cihaz testi yapıldı.

## 22. En Basit Çalışan Kurulumun Özeti

Bir kullanıcı yalnızca şunları yaparak başlayabilir:

1. `settings.gradle.kts` içinde `mavenCentral()` bulunduğunu kontrol eder.
2. `filelogger:1.0.0-rc6` bağımlılığını ekler.
3. `Application.onCreate()` içinde `FileLogger.install(...)` çağırır.
4. İstediği yerde `FileLogger.e(...)` kullanır.
5. ZIP paylaşmak için FileProvider tanımlar.
6. `createSupportReport()` ve `createShareIntent()` ile paylaşım ekranını açar.

İlk entegrasyonda remote upload, Crashlytics, OpenTelemetry veya Compose viewer
eklemeyin. Önce temel dosya loglama ve e-posta paylaşımının çalıştığını doğrulayın;
sonra gerçekten ihtiyaç duyduğunuz isteğe bağlı modüllere geçin.
