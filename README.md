# Android FileLogger

[English](README.md) | [Türkçe](docs/KULLANIM_REHBERI_TR.md)

Production-oriented Android file logging with bounded async writes, failure-aware flush,
hybrid retention, structured context, write-time redaction, user-controlled collection,
crash capture, support exports, diagnostics, and optional OkHttp, remote-upload,
Crashlytics, OpenTelemetry, and Compose viewer modules.

For a step-by-step Turkish integration guide, see
[Sıfırdan Kurulum ve Kullanım Rehberi](docs/KULLANIM_REHBERI_TR.md).

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

## Requirements

- Android `minSdk 24` or newer
- JDK 17 recommended for current Android builds
- Gradle Kotlin DSL or Groovy DSL
- Kotlin Multiplatform targets for `filelogger-core`: Android, JVM, iOS Arm64,
  iOS Simulator Arm64, and Wasm JS

## Install from Maven Central

`1.0.0-rc7` is published and publicly available from Maven Central. In the
project-level `settings.gradle.kts`, make sure Maven Central is enabled:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
```

Then add the Android library to the application module's `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.github.sevcanucan:filelogger:1.0.0-rc7")
}
```

Groovy DSL projects use the same repository in `settings.gradle` and this dependency
in `app/build.gradle`:

```groovy
dependencies {
    implementation 'io.github.sevcanucan:filelogger:1.0.0-rc7'
}
```

`filelogger` already brings `filelogger-core` transitively. Add only the optional
modules your application uses:

| Feature | Optional dependency |
| --- | --- |
| OkHttp request/response logging | `io.github.sevcanucan:filelogger-okhttp:1.0.0-rc7` |
| HTTPS batch upload | `io.github.sevcanucan:filelogger-remote:1.0.0-rc7` |
| Firebase Crashlytics delivery | `io.github.sevcanucan:filelogger-crashlytics:1.0.0-rc7` |
| OpenTelemetry delivery | `io.github.sevcanucan:filelogger-otel:1.0.0-rc7` |
| Compose log viewer | `io.github.sevcanucan:filelogger-ui:1.0.0-rc7` |

These modules are libraries, not separate applications. Gradle packages the selected
modules into the host application's single APK or AAB.

### Migrating from JitPack

Replace the old dependency:

```kotlin
implementation("com.github.SevcanUcan.android-file-logger:filelogger:1.0.0-rc4")
```

with the Maven Central coordinate:

```kotlin
implementation("io.github.sevcanucan:filelogger:1.0.0-rc7")
```

Remove the JitPack repository if no other dependency needs it. Do not include the
JitPack and Maven Central variants of the same module together. JitPack remains
available for existing installations:

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

Add it to the shared source set of a Kotlin Multiplatform project:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.sevcanucan:filelogger-core:1.0.0-rc7")
        }
    }
}
```

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
import android.app.Application
import com.filelogger.FileLogger
import com.filelogger.LoggerConfig

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

Register the class in `app/src/main/AndroidManifest.xml`:

```xml
<application
    android:name=".App"
    ... >
</application>
```

```kotlin
FileLogger.d("Startup", "Application ready")
FileLogger.i("Startup", "Initial data loaded")
FileLogger.w("Sync", "Retry scheduled")
FileLogger.e("Operation", "An error occurred during the operation", throwable)
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

When collection is disabled, new records and crash snapshots are dropped. Remote
destinations persist their pause before consent withdrawal reports success. Deletion
does not flush pending remote data to the network; it pauses delivery first, clears
eligible local data, and fails if an active upload or foreign process claim remains.

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

`FileLogger.flush()` places a barrier after records accepted before the call. It
returns `true` only when those records reached the file destination without a write
failure and `FileDescriptor.sync()` completed for the last written file. A failed
interval returns `false`; after that failure has been reported, a later recovered
interval may succeed. ERROR records first drain earlier async records, then write and
sync directly so file order is preserved.

The result does not cover non-flushable custom destinations and is not a permanent
power-loss guarantee. Android, the filesystem, or storage hardware can still lose
data after an abrupt process/native crash, OS kill, device reset, or hardware failure.

With session logging, age and total-size retention apply globally to eligible
FileLogger files below the configured log root. The active session is protected.
Cleanup is deterministic by modification time and relative path. If the configured
limit cannot be met without deleting active data, the write interval and its flush
report failure. FileLogger storage operations use a process-local mutex plus an OS
file lock; keep the root in app-private internal storage. Remote spool files are not
retention candidates.

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
val uri = FileProvider.getUriForFile(
    context,
    "${context.packageName}.filelogger.files",
    archive
)
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
val archive = FileLogger.exportLogs()
val uri = FileProvider.getUriForFile(
    context,
    "${context.packageName}.filelogger.files",
    archive
)
context.startActivity(FileLogger.createShareIntent(uri))
```

Exports include `diagnostics.json` and apply `LogRedactor.DEFAULT_SENSITIVE` by
default, including to historical lines that were stored without write-time
redaction. Export reads and sanitizes each line while writing the ZIP; it does not
modify the source log files. `LogRedactor.NONE` remains an explicit opt-out and can
expose stored secrets. The built-in policy covers common email, bearer/JWT,
JSON secret-field, API-key, cookie, phone/address-field, and long-number patterns;
regex redaction is defense in depth, not a guarantee that arbitrary sensitive data
will be recognized. The host application owns its `FileProvider` policy.

Declare the matching provider in the host application's manifest:

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

Create `app/src/main/res/xml/filelogger_paths.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths xmlns:android="http://schemas.android.com/apk/res/android">
    <files-path
        name="filelogger_exports"
        path="log_exports/" />
</paths>
```

The manifest authority and the authority passed to `getUriForFile()` must match.

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

Bodies are disabled by default. All URL query values are masked in request,
response, and failure logs. Authorization, cookies, proxy authorization, and API
key headers are redacted case-insensitively. Email and JWT path segments are
redacted, and the segment following a recognized credential operation such as
`reset-password` or `token` is fully masked. Arbitrary application-specific opaque
path secrets cannot be identified reliably and should not be placed in URLs.
Enabled repeatable text bodies use a
bounded capture sink; one-shot, duplex, and binary bodies are not evaluated for
logging, and the original request is always passed to OkHttp unchanged. The limit
bounds FileLogger's capture buffer, but cannot prevent memory allocated internally
by a custom `RequestBody`. A repeatable body may still be evaluated once for logging
and once by OkHttp, so body logging should remain disabled for side-effecting body
implementations. Disabled collection avoids body evaluation for the default
`FileLogger`; a custom `Logger` has no loggability contract, so the interceptor
cannot infer whether that logger will discard a message.

## Safe file paths

`logFolder` accepts nested relative paths. File names, session ids, session folder
prefixes, and ZIP prefixes must be single relative names. Absolute paths, `..`,
current-directory segments, and both slash styles used for traversal are rejected
instead of normalized silently. Resolved paths are checked against their canonical
root with path-component-aware containment, including `readLogs(sessionId)`.
Canonical checks detect links that already exist when resolution occurs. As with
ordinary Java file APIs, an attacker able to replace an app-private directory
between validation and opening could still create a TOCTOU race; keep log roots in
Android internal storage and do not expose them to untrusted writers.

## Remote destination

```kotlin
val remote = RemoteLogDestination(
    uploader = RemoteLogUploader { batch ->
        api.upload(batch.payload, batch.contentEncoding)
        true
    },
    config = RemoteLogConfig(
        spoolDirectory = File(noBackupFilesDir, "pending_logs"),
        batchSize = 50,
        maxRetries = 3
    )
)

FileLogger.init(
    this,
    LoggerConfig.prod().copy(customDestinations = listOf(remote))
)
```

Batches are written to a uniquely named temporary file, synced, and renamed to a
committed `batch_*` file before upload. Existing `batch_*` files remain recoverable.
The default policy never evicts an unacknowledged committed batch to make room. When
the spool quota is full, the new memory batch is rejected for persistence, the barrier
fails, and diagnostics report the failure while existing committed files remain.
Accepted but not-yet-persisted records are bounded by the configured queue capacity
plus one batch. A spool failure makes `flush()` return `false` and the in-memory batch
is retried by a later flush or shutdown; an abrupt process kill before that retry can
still lose that memory-only batch.

Committed remote payloads have an internal 16 MiB safety ceiling. Empty or oversized
files are not loaded into the heap or silently deleted, make delivery barriers fail,
and remain available for operator inspection. A rejected file is skipped for the
current scan so later healthy batches can still be delivered. The spool format does
not contain a payload checksum, so arbitrary non-empty truncation cannot always be
distinguished from valid content.

For `RemoteLogDestination`, `flush() == true` means every record before the barrier
was persisted, the application-provided uploader returned `true`, and no committed
spool batch remained. It does not independently prove server-side durable storage:
the uploader must return `true` only after the backend acknowledgement it considers
authoritative. Offline local spooling therefore keeps the batch but returns `false`.
Cooperating workers use an atomic claim name plus an OS file lock, so one committed
file has one local uploader owner at a time. A process can still crash after the server
accepts a batch but before the local claim is deleted. The same batch may then be sent
again after restart. For idempotent uploads, opt in without changing `RemoteLogBatch`:

```kotlin
val remote = RemoteLogDestination.withIdentifiedUploader(
    uploader = IdentifiedRemoteLogUploader { batch, context ->
        api.upload(batch.payload, batch.contentEncoding, idempotencyKey = context.batchId)
    },
    config = RemoteLogConfig(File(noBackupFilesDir, "pending_logs"))
)
```

`batchId` remains stable across retry, claim recovery, and restart. `attemptId` names
one uploader invocation and changes for each retry. Optional
`CancellableIdentifiedRemoteLogUploader` cancellation targets only that attempt; it
does not recall data already received by a server. Exactly-once effects still require
the backend to atomically deduplicate `batchId` values.

`setCollectionEnabled(false, erasePendingData = false)` durably pauses new collection,
persistence, claims, uploads, and retries while preserving already accepted data.
Explicit re-enablement resumes those pending batches. With `erasePendingData = true`,
success means local committed, temporary, and claim data was removed. An unresolved
upload or another process claim makes the operation fail within its timeout.
Within one destination, revoke linearizes before a new upload attempt can register.
Across processes, the durable pause-marker update is the linearization point; another
process may finish work started before that update, but no new cooperating claim may
start after a successful revoke returns.

Keep a durable remote spool under `noBackupFilesDir`. If an application intentionally
uses `filesDir`, it must exclude that path from Android cloud backup and device transfer.
The sample excludes `logs/`, `log_exports/`, and `pending_logs/`; applications using
different folder names must add matching exclusions to their own backup rules.

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
