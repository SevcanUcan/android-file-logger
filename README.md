# Android FileLogger

Production-oriented Android file logging with bounded async writes, durable flush,
hybrid retention, structured context, write-time redaction, user-controlled collection,
crash capture, support exports, diagnostics, and optional OkHttp, remote-upload, and
Compose viewer modules.

## Why not only Logcat or Timber?

Logcat is excellent while a device is attached, and Timber is excellent for routing
logs during development. FileLogger focuses on the support case after the event:
bounded on-device persistence, session folders, crash-adjacent durability, searchable
stored records, redacted ZIP export, and offline remote delivery. It can be used next
to Timber rather than replacing an application's existing logging facade.

## Modules

| Module | Purpose |
| --- | --- |
| `filelogger` | Core file, Logcat, crash, export, query, retention, and diagnostics APIs |
| `filelogger-okhttp` | Privacy-safe OkHttp request/response logging |
| `filelogger-remote` | Batching, gzip, retry/backoff, and persistent offline spool |
| `filelogger-ui` | Compose viewer with search and level filters |
| `app` | Interactive sample and device reliability tests |

## Install

For a source checkout:

```kotlin
dependencies {
    implementation(project(":filelogger"))
    implementation(project(":filelogger-okhttp")) // optional
    implementation(project(":filelogger-remote")) // optional
    implementation(project(":filelogger-ui"))     // optional
}
```

The project publishes Maven-compatible AARs and source JARs. JitPack coordinates
use the repository owner and tag:

```kotlin
repositories { maven("https://jitpack.io") }

dependencies {
    implementation("com.github.SevcanUcan.android-file-logger:filelogger:TAG")
}
```

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
