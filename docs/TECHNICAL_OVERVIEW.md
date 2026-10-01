# Technical Overview

This document describes the current architecture of `android-file-logger`, the reliability decisions already implemented, and the remaining gaps that should guide the next work.

## Current State

The library now has a small but production-oriented core:

- structured log records with global and per-event attributes
- pluggable destinations
- Logcat and file output
- JSON and plain text formatters
- bounded async file queue
- explicit async overflow policy
- flush that reaches the file destination
- size and age based file retention
- synchronous fallback for `ERROR` logs
- dev/prod configuration presets
- per-process log file names to avoid basic multi-process file conflicts
- lifecycle auto flush on background/low-memory signals
- crash capture with a recent in-memory log buffer
- session-based log folders
- write-time destination redaction plus optional export redaction
- runtime collection consent and local/remote data deletion
- bounded support reports with app/device environment and custom diagnostic sections
- bounded, redacted breadcrumb timelines with crash persistence
- zip export with diagnostics metadata
- public diagnostics for queue pressure and current log files
- runtime per-tag log-level overrides
- lazy logging overloads for expensive messages
- builder-based config creation
- custom destination attachment
- stored-log query by level, tag, keyword, timestamp, and session
- write/flush/rotation and level-count metrics
- fault-injectable file operations
- optional OkHttp, remote delivery, and Compose viewer modules

## Public API

The main entry point is `FileLogger`.

```kotlin
FileLogger.init(
    context = applicationContext,
    config = LoggerConfig.prod()
)

FileLogger.d("Startup", "Logger ready")
FileLogger.i("Startup", "Initial data loaded")
FileLogger.w("Sync", "Retry scheduled")
FileLogger.e("Crash", "Unexpected failure", throwable)
FileLogger.flush()
FileLogger.shutdown()

val archive = FileLogger.exportLogs()
val supportReport = FileLogger.createSupportReport(
    SupportReportOptions(userNote = "Sync stopped", sections = mapOf("sync" to syncState))
)
val diagnostics = FileLogger.diagnostics()
val session = FileLogger.session()
```

### Runtime lifecycle

`FileLogger.init()` can be called again when configuration changes. Re-initialization:

- drains and closes the previous async queue
- unregisters the previous background-flush callback
- restores the previous uncaught-exception handler
- clears runtime tag-level overrides
- releases session and directory references

Applications and tests can explicitly release the logger runtime:

```kotlin
val closedCleanly = FileLogger.shutdown()
```

`shutdown()` waits for queued records up to the supplied timeout. New writes require
a subsequent `init()` call. Custom destinations that own resources can implement
`CloseableLogDestination` to participate in shutdown.

`FileLogger` is a singleton facade, but it must not retain an Android `Context`. During initialization it derives safe values from `applicationContext`, such as `filesDir`, package name, and process name, then keeps only the logger pipeline. This avoids Android Lint's static context leak warning.

## Core Model

`LogRecord` is the internal structured event passed through the system. It carries:

- timestamp
- level
- tag
- message
- throwable
- redacted throwable text
- string attributes
- thread name
- process name

`LogLevel` currently supports:

- `DEBUG`
- `INFO`
- `WARN`
- `ERROR`

Each level has a priority so `LoggerConfig.minimumLogLevel` can filter records before they reach destinations.

## Configuration

`LoggerConfig` controls the active policy:

- `minimumLogLevel`
- `logFormatter`
- `maxFileSize`
- `maxBackupFiles`
- `maxTotalLogSize`
- `maxLogAgeMillis`
- `asyncQueueCapacity`
- `asyncOverflowStrategy`
- `errorSyncFallbackEnabled`
- `useProcessSpecificLogFiles`
- `autoFlushOnAppBackground`
- `crashCaptureEnabled`
- `crashBufferSize`
- `breadcrumbCapacity`
- `maxBreadcrumbAttributes`
- `maxBreadcrumbBytes`
- `sessionLoggingEnabled`
- `sessionId`
- `sessionFolderPrefix`
- `customDestinations`
- `redactor`
- `collectionEnabled`

Two presets exist:

- `LoggerConfig.dev()`: verbose, plain text, longer retention, larger queue
- `LoggerConfig.prod()`: `WARN+`, JSON, write-time sensitive-data redaction,
  tighter retention, and production defaults

The default constructor still exists for simple usage, but callers should prefer an explicit preset in real apps.

Java-style callers can use `LoggerConfig.Builder()`:

```kotlin
val config = LoggerConfig.Builder()
    .setMinimumLogLevel(LogLevel.WARN)
    .setAsyncQueueCapacity(1024)
    .setCrashCaptureEnabled(true)
    .setSessionId("support-ticket-1842")
    .addDestination(customDestination)
    .build()
```

## Runtime Filtering

Apps can temporarily tighten logging for noisy tags without rebuilding the logger:

```kotlin
FileLogger.setTagMinimumLevel("OkHttp", LogLevel.ERROR)
FileLogger.setTagMinimumLevel("Checkout", LogLevel.DEBUG)
FileLogger.clearTagMinimumLevels()
```

The global `minimumLogLevel` remains the default. A tag override only affects that exact tag.

For expensive log messages, callers can use lazy overloads:

```kotlin
FileLogger.d("Search") { expensiveDebugPayload() }
FileLogger.e("Sync", throwable) { "Sync failed for ${account.id}" }
```

The message lambda is only evaluated when the level is loggable for that tag.

Global context and event attributes provide queryable metadata without embedding it
into message text:

```kotlin
FileLogger.putContext("build", BuildConfig.VERSION_NAME)
FileLogger.i(
    "Checkout",
    "Payment submitted",
    mapOf("orderId" to order.id)
)
```

Event attributes override global values with the same key. Context can be inspected,
removed, or cleared with `context()`, `removeContext()`, and `clearContext()`.

## Destination Pipeline

Default flow:

1. `FileLogger` receives a log call.
2. `DefaultLogger` applies the minimum level policy.
3. `DefaultLogger` creates a `LogRecord`.
4. `RedactingLogDestination` sanitizes message, attribute values, and throwable text.
5. `CompositeLogDestination` fans the sanitized record out.
6. `LogcatDestination` writes immediately to Logcat.
7. File output goes through `ErrorSyncFallbackDestination`.
8. Non-error records go through `AsyncLogDestination`.
9. `ERROR` records go directly to `FileLogDestination` and are flushed.
10. `FileLogDestination` formats, rotates, writes, and can flush the file descriptor.
11. Optional custom destinations receive the same sanitized record.
12. Optional recent log buffer stores the last N sanitized records for crash capture.

## Async Queue

`AsyncLogDestination` uses a bounded queue. The queue size is configured with `asyncQueueCapacity`.

Overflow behavior is configured with `AsyncOverflowStrategy`:

- `DROP_OLDEST`: keep newer logs when the queue is full
- `DROP_NEWEST`: preserve already queued logs and drop incoming logs
- `BLOCK`: block the caller until there is queue space

Dropped records are counted internally. This is useful for tests now, and later it should become part of a public diagnostics API.

## Flush Reliability

`FileLogger.flush()` waits for the async queue to reach a flush marker. When the marker is processed, the async destination also calls `flush()` on the wrapped destination if it supports `FlushableLogDestination`.

`FileLogDestination.flush()` opens the last written file and calls `FileDescriptor.sync()`. This improves durability before export, share, background transitions, or controlled shutdown.

Important limitation: no Android file logger can fully guarantee logs after abrupt process death, native crash, OS kill, or power loss. The current implementation improves best-effort durability, especially for explicit flush and `ERROR` logs.

When `autoFlushOnAppBackground` is true, `FileLogger` registers a `ComponentCallbacks2` callback on the application context and flushes on low-memory and UI-hidden trim signals. The callback does not require `FileLogger` to retain a `Context`.

## File Retention

`FileRotationPolicy` handles:

- active file size rotation
- backup count
- max total log folder size
- max backup age

The active file is preserved when retention trims old files. Cleanup targets backup files first, sorted by age.

Current naming:

- active file: `app_log.txt`
- backups: `app_log.txt.1`, `app_log.txt.2`, ...

## Session Logging

When `sessionLoggingEnabled` is true, logs are written under a per-session directory:

```text
files/logs/session_20260928_121314_123/app_log.txt
```

`LoggerConfig.sessionId` can be supplied by the host app when it wants to align logger output with an existing analytics, support, or crash-reporting session. If it is not supplied, the library creates a timestamp-based session id at init time.

`FileLogger.session()` returns the active session metadata:

- session id
- session directory name
- session start timestamp

This makes support exports easier to reason about because each exported archive maps to a specific app run.

## Error Sync Fallback

When `errorSyncFallbackEnabled` is true, `ERROR` logs bypass the async queue and write directly to the file destination, followed by a flush.

Before the synchronous write, the async queue is drained while new writes are held.
This preserves the file order of records submitted before and after the `ERROR` log.

This reduces the chance of losing the final important log before a crash. It does not replace full crash capture; it is a lightweight reliability layer.

## Crash Capture

When `crashCaptureEnabled` is true, the default pipeline includes a `RecentLogBuffer`. It stores the last `crashBufferSize` records in memory.

`FileLogger` installs an uncaught exception handler. On crash it:

1. writes buffered records back through the destination as `ERROR` records with a `CrashBuffer/` tag prefix
2. logs the uncaught exception as `FileLoggerCrash`
3. calls `flush()`
4. delegates to the previously installed uncaught exception handler

This is best-effort crash capture. It helps with Kotlin/Java uncaught exceptions, but cannot cover every native crash or OS kill scenario.

## Write-Time Privacy And Consent

`LoggerConfig.redactor` is applied once around the complete destination composite.
Messages, attribute values, and formatted throwable text are sanitized before Logcat,
disk, the recent crash buffer, custom destinations, or remote delivery can observe a
record. The original `Throwable` is removed after its sanitized text is produced so a
custom destination cannot accidentally read the unredacted exception message.

`LoggerConfig.prod()` uses `LogRedactor.DEFAULT_SENSITIVE`. Development configuration
keeps `LogRedactor.NONE` so local debugging remains explicit and unsurprising. Tags and
attribute keys are kept intact for indexing and must be treated as non-sensitive names.

Applications can bind collection to consent and account lifecycle:

```kotlin
FileLogger.setCollectionEnabled(consentGranted)
FileLogger.setCollectionEnabled(false, deleteExistingData = true)
FileLogger.deletePendingUploads()
FileLogger.deleteAllLogs(includeExports = true)
```

Disabled collection rejects new records and crash snapshots. Full deletion pauses
collection, flushes queued work, invokes `ErasableLogDestination` implementations,
and removes session logs and optionally support exports. The remote destination uses
this contract to clear both its memory queue and persistent spool.

## Export And Redaction

`FileLogger.exportLogs()` flushes the pipeline and writes current session log files to a zip archive.

```kotlin
val archive = FileLogger.exportLogs()
```

Write-time redaction is the primary privacy boundary. Callers can additionally provide
a `LogRedactor` to sanitize historical or externally-created lines before they enter
the zip:

```kotlin
FileLogger.exportLogs(
    redactor = LogRedactor { line ->
        line.replace(Regex("token=[^ ]+"), "token=***")
    }
)
```

A built-in preset covers common support-risk fields:

```kotlin
FileLogger.exportLogs(redactor = LogRedactor.DEFAULT_SENSITIVE)
```

It masks common email addresses, bearer tokens, password/token/secret/api-key style key-value fields, and long number sequences. Multiple redactors can be combined with `LogRedactor.chain(...)`.

Every archive also includes `diagnostics.json` with app package, process name, session metadata, queue diagnostics, and the effective logger policy. This gives support and QA enough context to understand an archive without opening the app.

`FileLogger.createShareIntent(uri)` returns an `ACTION_SEND` intent for an already exposed content `Uri`. The library does not declare a `FileProvider`; host apps should expose the archive with their own provider policy.

## Support Reports

`FileLogger.createSupportReport()` builds a single support artifact on top of the
same flush and ZIP pipeline. A report contains:

- current process log files, unless `includeLogs` is false
- `diagnostics.json` with logger health and effective policy
- `support-report.json` with schema/report IDs and app/device environment
- optional redacted `user-note.txt`
- optional redacted, application-owned `sections/*.txt`

The environment includes package/version, Android SDK/release, manufacturer/model,
supported ABIs, and locale. It does not collect Android ID, advertising ID, serial,
account details, IP/MAC addresses, or location.

`SupportReportOptions` defaults to `LogRedactor.DEFAULT_SENSITIVE`, at most 16 custom
sections, 128 KiB per supplemental section, and 1 MiB across the note and sections.
Invalid section names, count overflows, individual overflows, and aggregate overflows
fail before an archive is returned. Section names are non-sensitive identifiers.
This keeps accidental database dumps and path traversal out of the support package.

## Breadcrumb Timeline

`FileLogger.addBreadcrumb()` captures navigation, user actions, and state transitions
that are useful during support without turning each action into a full log record.
Breadcrumbs use the configured redactor immediately and remain in a thread-safe bounded memory
buffer. By default, the buffer keeps 64 entries; each entry permits 16 attributes and
8 KiB across its message, keys, and values.

```kotlin
FileLogger.addBreadcrumb(
    message = "Retry requested",
    category = "sync",
    attributes = mapOf("attempt" to "2")
)
```

The current timeline appears as `breadcrumbs.json` in support reports. Setting
`SupportReportOptions.includeBreadcrumbs` to false omits it. Disabled collection
rejects new breadcrumbs, while deletion and shutdown clear the in-memory buffer.
During an uncaught exception, the crash handler snapshots breadcrumbs before adding
new crash records and synchronously persists them with `Breadcrumb/<category>` tags.

## Diagnostics

`FileLogger.diagnostics()` returns:

- dropped async record count
- queued async record count
- total async queue capacity
- current log file names

This is intended for debug screens, test assertions, and future in-app log viewer support.

## Multi-Process Guard

By default, `useProcessSpecificLogFiles` is true.

The main process keeps the configured file name:

```text
app_log.txt
```

Secondary processes get a suffix:

```text
app_log_sync.txt
app_log_remote.txt
```

This avoids multiple Android processes appending to the same file without cross-process locking. It is a pragmatic guard, not a full multi-process coordination system.

## Context Ownership

`FileLogger` must never keep `Context` in a field. `init()` should extract:

- `filesDir`
- package name
- process name

Those values are safe to retain because they are not Android component references. This keeps the singleton facade convenient without leaking `Activity`, `Service`, or even `Application` context references.

## Current Test Coverage

Unit tests cover:

- structured record creation
- minimum log level filtering
- formatter output
- Logcat chunking
- destination fan-out
- bounded async queue behavior
- async overflow policies
- async flush propagation
- file write and flush
- size rotation
- age and total-size retention
- error sync fallback
- process-specific file naming
- config presets
- export zip creation and redaction
- export metadata entries
- default sensitive redaction
- diagnostics aggregation
- recent crash buffer behavior
- session folder resolution
- tag-specific filtering
- config builder

## Extension Modules

- `filelogger-okhttp` records method, URL, status, and duration with opt-in bounded
  text bodies and sensitive-header masking.
- `filelogger-remote` writes batches to an offline disk spool before gzip upload,
  retries with exponential backoff, and replays pending batches after restart.
- `filelogger-ui` exposes a Compose viewer with search, level filtering, refresh,
  throwable rendering, and lazy scrolling.

## Verification Boundaries

Unit tests cover fault injection, queue pressure, formatters, retention, export,
query, metrics, OkHttp, remote upload, and viewer filtering. Android device tests
cover real app storage, concurrent writes from two processes, and an actual
uncaught exception in a secondary process.

Native crashes, ANRs, sudden power loss, and force-stop remain platform boundaries:
the process may terminate before managed-code callbacks or `fsync()` complete.
Applications needing those guarantees should combine FileLogger exports with a
dedicated native crash/ANR reporting service.
