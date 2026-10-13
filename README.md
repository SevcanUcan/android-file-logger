# Android FileLogger

Production-oriented Android file logging with bounded async writes, durable flush,
hybrid retention, structured context, write-time redaction, user-controlled collection,
crash capture, support exports, diagnostics, and optional OkHttp, remote-upload,
Crashlytics, OpenTelemetry, and Compose viewer modules.

Türkçe ve başlangıç seviyesinde entegrasyon anlatımı için
[Sıfırdan Kurulum ve Kullanım Rehberi](docs/KULLANIM_REHBERI_TR.md) belgesine bakın.

## Why not only Logcat or Timber?

Logcat is excellent while a device is attached, and Timber is excellent for routing
logs during development. FileLogger focuses on the support case after the event:
bounded on-device persistence, session folders, crash-adjacent durability, searchable
stored records, redacted ZIP export, and offline remote delivery. It can be used next
to Timber rather than replacing an application's existing logging facade.

## Modules

| Module | Purpose |
| --- | --- |
| `filelogger-core` | Kotlin Multiplatform logging API and engine for Android, JVM, iOS, and Wasm |
| `filelogger` | Core file, Logcat, crash, export, query, retention, and diagnostics APIs |
| `filelogger-okhttp` | Privacy-safe OkHttp request/response logging |
| `filelogger-remote` | Batching, gzip, retry/backoff, and persistent offline spool |
| `filelogger-crashlytics` | Bounded custom logs and non-fatal reports for Firebase Crashlytics |
| `filelogger-otel` | Structured OpenTelemetry logs with trace-context correlation support |
| `filelogger-ui` | Compose viewer with search and level filters |
| `app` | Interactive sample and device reliability tests |

## Install

For a source checkout:

```kotlin
dependencies {
    implementation(project(":filelogger-core")) // common Kotlin code
    implementation(project(":filelogger"))
    implementation(project(":filelogger-okhttp")) // optional
    implementation(project(":filelogger-remote")) // optional
    implementation(project(":filelogger-crashlytics")) // optional
    implementation(project(":filelogger-otel"))        // optional
    implementation(project(":filelogger-ui"))     // optional
}
```

Maven Central is the primary distribution channel. Android projects normally
already have `mavenCentral()` in `dependencyResolutionManagement.repositories`:

```kotlin
repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation("io.github.sevcanucan:filelogger-core:1.0.0-rc5") // KMP common code
    implementation("io.github.sevcanucan:filelogger:1.0.0-rc5")
    implementation("io.github.sevcanucan:filelogger-okhttp:1.0.0-rc5") // optional
    implementation("io.github.sevcanucan:filelogger-remote:1.0.0-rc5") // optional
    implementation("io.github.sevcanucan:filelogger-crashlytics:1.0.0-rc5") // optional
    implementation("io.github.sevcanucan:filelogger-otel:1.0.0-rc5")         // optional
    implementation("io.github.sevcanucan:filelogger-ui:1.0.0-rc5")     // optional
}
```

These are library modules, not separate applications. Gradle packages only the
selected modules into the host application's single APK or AAB. Do not add every
module unless the application uses it.

`1.0.0-rc5` is the first Maven Central release candidate and includes the Kotlin Multiplatform
core, Crashlytics destination, and OpenTelemetry destination. Final `1.0.0`
publication remains deferred until the candidate has completed external application
validation. The coordinates become resolvable after the first Central Portal deployment
is approved and published.

JitPack remains available as an alternative for existing users:

```kotlin
repositories { maven("https://jitpack.io") }

dependencies {
    implementation("com.github.SevcanUcan.android-file-logger:filelogger:1.0.0-rc4")
}
```

Maintainers can follow the [Maven Central release guide](docs/MAVEN_CENTRAL_RELEASE.md).

## Kotlin Multiplatform core

`filelogger-core` exposes a platform-neutral logger, structured records, tag and
level filtering, failure-isolated composite destinations, and a thread-safe bounded
memory destination. It publishes Gradle module metadata plus Android, JVM, iOS
device, iOS simulator, and Wasm artifacts.

```kotlin
val recentLogs = BoundedMemoryLogDestination(capacity = 200)
val logger = DefaultLogger(
    destination = recentLogs,
    config = LoggerConfig(
        minimumLevel = LogLevel.INFO,
        context = mapOf("build" to "1.0.0")
    )
)

logger.i("Sync", "Upload started", mapOf("items" to "12"))
```

On Android, common Kotlin records can use the full file pipeline after the regular
`FileLogger.install(...)` call:

```kotlin
val sharedLogger = com.filelogger.core.DefaultLogger(
    destination = com.filelogger.kmp.FileLoggerDestination(),
    config = com.filelogger.core.LoggerConfig(processName = "android")
)
```

See [Multiplatform architecture](docs/MULTIPLATFORM.md) for targets, publication,
CI coverage, and current platform limitations.

## Quick start

Initialize once from `Application` using explicit environment defaults:

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        FileLogger.install(
            this,
            if (BuildConfig.DEBUG) LoggerConfig.dev() else LoggerConfig.prod()
        )
    }
}
```

```kotlin
FileLogger.d("Startup", "Application ready")
FileLogger.i("Startup", "Initial data loaded")
FileLogger.w("Sync", "Retry scheduled")
FileLogger.e("Checkout", "Payment failed", throwable)
```

`dev()` uses readable DEBUG logs and longer retention. `prod()` uses WARN+ JSON
logs and tighter storage limits. Both use a bounded queue, process-specific files,
session folders, background flush, crash capture, and synchronous ERROR fallback.

## Structured context and privacy

Attach stable context once and event-specific attributes at the call site:

```kotlin
FileLogger.putContext(
    mapOf(
        "build" to BuildConfig.VERSION_NAME,
        "accountTier" to "pro"
    )
)

FileLogger.i(
    tag = "Checkout",
    message = "Payment submitted",
    attributes = mapOf("orderId" to order.id, "provider" to "card")
)
```

Event attributes override global context with the same key. Attribute values are
redacted by the configured `LogRedactor` before any Logcat, file, memory, custom, or
remote destination receives them. `LoggerConfig.prod()` enables
`LogRedactor.DEFAULT_SENSITIVE`; custom policies can be supplied with
`LoggerConfig.Builder().setRedactor(...)`. Tags and attribute keys should remain
non-sensitive identifiers because they are intentionally preserved for filtering.

Collection can follow the host application's consent state:

```kotlin
FileLogger.setCollectionEnabled(consentGranted)

// Consent withdrawal or account deletion:
FileLogger.setCollectionEnabled(false, deleteExistingData = true)
// Or explicitly remove local logs, exports, and pending remote batches:
FileLogger.deleteAllLogs(includeExports = true)
```

When collection is disabled, new records and crash snapshots are dropped. Deletion
flushes and pauses the pipeline before removing persisted logs and asking erasable
custom destinations, including the remote module, to clear pending data.

## Reliability and retention

```kotlin
val config = LoggerConfig.Builder()
    .setMaxFileSize(5 * 1024 * 1024)
    .setMaxTotalLogSize(50 * 1024 * 1024)
    .setMaxLogAgeMillis(TimeUnit.DAYS.toMillis(7))
    .setAsyncQueueCapacity(2_048)
    .setAsyncOverflowStrategy(AsyncOverflowStrategy.DROP_OLDEST)
    .setErrorSyncFallbackEnabled(true)
    .build()
```

`flush()` drains the async queue and calls `FileDescriptor.sync()`. ERROR records
first drain earlier async records, then write and sync directly so file order is
preserved. No Android logger can guarantee persistence after power loss, native
abort, or an immediate OS kill; these APIs provide best-effort durability.

## Query stored logs

```kotlin
val errors = FileLogger.readLogs(
    LogQuery(
        levels = setOf(LogLevel.ERROR),
        tags = setOf("Checkout"),
        keyword = "payment",
        limit = 200
    )
)

val olderSession = FileLogger.readLogs("support-session-42", LogQuery(limit = 500))
```

The reader supports built-in JSON and plain-text formats, structured attributes,
rotated files, throwable continuation lines, timestamp ranges, exact attribute
matching, and malformed-line isolation.

## Support report and share

Create one bounded, privacy-filtered support package with logs, logger diagnostics,
non-identifying app/device metadata, a user note, and application-defined state:

```kotlin
val archive = FileLogger.createSupportReport(
    SupportReportOptions(
        userNote = feedbackText,
        sections = mapOf(
            "feature-flags" to enabledFlags.joinToString("\n"),
            "sync-state" to "pending=$pendingCount\nlastResult=$lastSyncResult"
        )
    )
)
val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", archive)
context.startActivity(FileLogger.createShareIntent(uri))
```

The ZIP contains `support-report.json`, `diagnostics.json`, optional
`user-note.txt`, optional `sections/*.txt`, and the current logs. Supplemental
content is redacted before export and protected by section-count, per-section, and
aggregate byte limits. Section names are path-safe identifiers and must not contain
sensitive data. Set `includeLogs = false` for a metadata-only
report. The automatic environment manifest deliberately excludes persistent device
identifiers, account data, network addresses, and location.

### Breadcrumb timeline

Record the small user and application transitions that explain how the app reached
its current state:

```kotlin
FileLogger.addBreadcrumb(
    message = "Opened checkout",
    category = "navigation",
    attributes = mapOf("source" to "cart")
)
```

Breadcrumbs use the configured redactor at collection time, are held in a thread-safe in-memory ring,
and written to `breadcrumbs.json` in the support report. The default policy keeps the
latest 64 records, permits 16 attributes and 8 KiB per record, and preserves the
timeline in crash logs before delegating to the previous crash handler. Category and
attribute names are non-sensitive identifiers. Collection consent and
`deleteAllLogs()` apply to breadcrumbs; `includeBreadcrumbs = false` excludes them
from an individual report.

For a logs-only archive, use the lower-level export API:

```kotlin
val archive = FileLogger.exportLogs(redactor = LogRedactor.DEFAULT_SENSITIVE)
val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", archive)
context.startActivity(FileLogger.createShareIntent(uri))
```

Exports include `diagnostics.json`. The built-in redactor masks common emails,
bearer tokens, secrets, passwords, API keys, and long number sequences. The host
application owns its `FileProvider` policy.

## OkHttp

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(
        FileLoggerInterceptor(
            config = NetworkLogConfig(
                logRequestHeaders = true,
                logResponseHeaders = true
            )
        )
    )
    .build()
```

Bodies are disabled by default. Authorization, cookies, proxy authorization, and
API key headers are redacted. Enabled text bodies are bounded and binary bodies are
not rendered.

## Remote destination

```kotlin
val remote = RemoteLogDestination(
    uploader = RemoteLogUploader { batch ->
        api.upload(batch.payload, batch.contentEncoding)
        true
    },
    config = RemoteLogConfig(
        spoolDirectory = File(cacheDir, "pending_logs"),
        batchSize = 50,
        maxRetries = 3
    )
)

FileLogger.init(
    this,
    LoggerConfig.prod().copy(customDestinations = listOf(remote))
)
```

Batches are committed to disk before upload, gzip-compressed by default, retried
with exponential backoff, and retained for the next process when offline.

## Crashlytics destination

Complete the normal Firebase/Crashlytics setup in the host application, then attach
the optional destination. The adapter sends bounded `WARN` and `ERROR` records by
default. Redacted throwable text is reported as a non-fatal exception.

```kotlin
val crashlytics = CrashlyticsLogDestination(
    config = CrashlyticsLogConfig(
        minimumLogLevel = LogLevel.WARN,
        recordNonFatalExceptions = true
    )
)

FileLogger.install(
    this,
    LoggerConfig.prod().copy(customDestinations = listOf(crashlytics))
)
```

## OpenTelemetry destination

The host application owns the OpenTelemetry SDK, OTLP exporter, authentication,
endpoint, and sampling policy. FileLogger only emits structured records through the
configured `OpenTelemetry` instance, so the same adapter works with a vendor or a
self-hosted Collector.

OpenTelemetry Java requires Android applications to enable core library desugaring.
Follow the current [Android desugaring setup](https://developer.android.com/studio/write/java8-support)
in the consuming app module.

```kotlin
val otel = OpenTelemetryLogDestination(
    openTelemetry = openTelemetrySdk,
    config = OpenTelemetryLogConfig(
        minimumLogLevel = LogLevel.INFO,
        instrumentationVersion = BuildConfig.VERSION_NAME
    )
)

FileLogger.install(
    this,
    LoggerConfig.prod().copy(customDestinations = listOf(otel))
)
```

Destinations can be combined. For example, an enterprise application can keep local
files, report errors to Crashlytics, and send structured logs to OpenTelemetry in the
same APK. Never place SMTP credentials or remote service secrets in the mobile app.

## Compose viewer

```kotlin
@Composable
fun SupportLogsScreen() {
    FileLoggerViewer()
}
```

The viewer provides keyword and attribute search, DEBUG/INFO/WARN/ERROR chips,
refresh, session/process metadata, attribute and throwable display, and lazy scrolling.

## Diagnostics

`FileLogger.diagnostics()` reports record counts by level, queue pressure and drops,
file writes/latency/failures/rotations, current files, and remote uploaded/failed/
pending batches. Diagnostics are also included in every export archive.

## Test strategy

The repository includes unit and Android device tests for:

- bounded queue overflow, concurrency, flush, close, and ordering
- file rotation, age/size retention, disk-full/write/sync/rename/delete failures
- dev/prod configuration, write-time redaction, consent/deletion, support reports,
  bounded breadcrumbs, export, query, metrics, structured context, and session behavior
- OkHttp privacy, body limits, timing, status, and network failures
- remote batching, gzip, retry/backoff, offline recovery, and shutdown
- viewer filtering and sample actions
- two-process concurrent writes and real secondary-process crash capture

Run local verification:

```shell
./gradlew test lintDebug assembleDebug
./gradlew connectedDebugAndroidTest
./gradlew publishAllPublicationsToTestRepository
```

## License

[MIT](LICENSE)
