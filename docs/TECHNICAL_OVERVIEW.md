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
- optional OkHttp, remote delivery, Crashlytics, OpenTelemetry, and Compose viewer modules

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

Low-memory and UI-hidden callbacks coalesce while one lifecycle flush is queued or
running. A blocked destination therefore cannot create an unbounded executor backlog;
a signal received during the running barrier requests one trailing barrier in the
same executor task. Further signals coalesce into that trailing pass.

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

`FileLogger.flush()` waits for the async queue to reach a flush marker. The marker is
ordered after records accepted before the call. When processed, it calls `flush()` on
each wrapped `FlushableLogDestination`.

`FileLogDestination` assigns write failures to monotonic flush intervals. A flush
returns `false` if any write in its barrier interval failed, even when a later write
succeeded. Reporting that failed interval does not permanently poison later flushes.
The file destination also opens the last written file and calls
`FileDescriptor.sync()`. Therefore `true` means the barrier was processed without a
known file-write failure and that sync call completed. It does not cover
non-flushable destinations or promise permanent media persistence.

Queue insertion and barrier waiting share one monotonic timeout deadline. The caller
can time out while the worker continues finishing already accepted work. A blocking
filesystem `fsync()`, file lock, or vendor storage call cannot be safely interrupted
by the portable Java API, so `timeoutMillis` is not a hard cancellation guarantee for
the underlying kernel operation.

Important limitation: no Android file logger can fully guarantee logs after abrupt process death, native crash, OS kill, or power loss. The current implementation improves best-effort durability, especially for explicit flush and `ERROR` logs.

When `autoFlushOnAppBackground` is true, `FileLogger` registers a `ComponentCallbacks2`
callback on the application context and schedules flush work on a dedicated daemon
executor for low-memory and UI-hidden trim signals. The lifecycle callback does not
wait for disk or OS file locks and does not require `FileLogger` to retain a `Context`.

## File Retention

`FileRotationPolicy` and `GlobalLogRetentionPolicy` handle:

- active file size rotation
- backup count
- global total size across eligible session log files
- global log age across eligible inactive sessions

Every session with a live lease is preserved, including multiple sessions in one
process and sessions owned by cooperating Android processes. Lease files are not log
candidates. An unlocked stale lease left after process death is reclaimed. Eligible
inactive files are removed in deterministic
`lastModified`, then root-relative-path order. Only names recognized as FileLogger
active/rotated files inside session-prefix directories are candidates; nested remote
spools and unrelated files are excluded. Canonical containment is rechecked before
deletion. If active data alone exceeds the limit, retention fails visibly instead of
deleting live data or claiming success.

Writes, rotation, retention, and FileLogger exports share a root coordinator. It uses
a JVM mutex and an OS file lock, so cooperating FileLogger processes serialize these
operations. This does not defend an app-private root against an untrusted process that
ignores the lock protocol. Java canonical checks also cannot eliminate a malicious
link-swap TOCTOU race; app-private internal storage remains part of the trust model.

Current naming:

- active file: `app_log.txt`
- backups: `app_log.txt.1`, `app_log.txt.2`, ...

## Session Logging

When `sessionLoggingEnabled` is true, logs are written under a per-session directory:

```text
files/logs/session_20260928_121314_123/app_log.txt
```

`LoggerConfig.sessionId` can be supplied by the host app when it wants to align logger output with an existing analytics, support, or crash-reporting session. If it is not supplied, the library creates a timestamp-based session id at init time. Session ids and file-name fields are single relative names. `logFolder` may be a nested relative path. Absolute paths, parent/current-directory traversal, and both separator styles are rejected. Canonical, component-aware containment is applied during init, writing, and `readLogs(sessionId)`.

The canonical check detects a link that exists at resolution time. It cannot fully eliminate a link-swap TOCTOU race between validation and a later Java file open. The default Android app-private `filesDir` trust boundary is therefore required; applications must not make the log tree writable by untrusted processes.

`FileLogger.session()` returns the active session metadata:

- session id
- session directory name
- session start timestamp

This makes support exports easier to reason about because each exported archive maps to a specific app run.

## Error Sync Fallback

When `errorSyncFallbackEnabled` is true, an `ERROR` submitted off the Android main
thread first drains the async queue, writes directly to the file destination, and
flushes. A failed internal ordering barrier is retained and reported by exactly one
subsequent external `flush()` or `close()` call; a later successful write cannot hide it.

On the Android main thread, `ERROR` is enqueued without waiting for disk, `fsync`, or an
OS file lock. This avoids turning a logging call into an ANR source and preserves FIFO
order with already queued records, but it weakens last-record crash durability: an
immediate process death can lose that queued error. Explicit `flush()` remains a
blocking API and should not be called on the main thread.

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
With the default sensitive policy, recognized sensitive attribute keys cause the complete
attribute value to be replaced. Pattern-based redaction covers known formats but cannot
prove that arbitrary personal or application-specific data has been removed.

Applications can bind collection to consent and account lifecycle:

```kotlin
FileLogger.setCollectionEnabled(consentGranted)
FileLogger.setCollectionEnabled(false, deleteExistingData = true)
FileLogger.deletePendingUploads()
FileLogger.deleteAllLogs(includeExports = true)
```

Disabled collection rejects new records and crash snapshots. Full deletion pauses
remote delivery before deleting data; it does not flush pending remote records to the
network. The remote destination clears its memory queue and persistent spool and does
not report success while an active upload or another process claim remains.

## Export And Redaction

`FileLogger.exportLogs()` requires a successful flush barrier. It writes and validates
a temporary ZIP, syncs it, and only then moves it to the final name while holding the
storage coordinator. A failed export never exposes its partial temp file as the final
archive. Java `renameTo` cannot provide an absolute power-loss durability guarantee on
every filesystem, so a replacement backup is retained/recovered around the commit.

```kotlin
val archive = FileLogger.exportLogs()
```

Write-time redaction is the primary privacy boundary. Export additionally uses
`LogRedactor.DEFAULT_SENSITIVE` by default so historical or externally-created lines
are sanitized while they are copied into the ZIP. Source log files are not changed:

```kotlin
FileLogger.exportLogs(
    redactor = LogRedactor { line ->
        line.replace(Regex("token=[^ ]+"), "token=***")
    }
)
```

A caller can explicitly disable export-time redaction, but this can disclose stored data:

```kotlin
FileLogger.exportLogs(redactor = LogRedactor.NONE)
```

The built-in default recognizes common email addresses, bearer and JWT values, JSON and key-value secret fields, API keys, cookies, phone/address fields, and long number sequences. It is regex-based defense in depth rather than a complete data-loss-prevention engine. Multiple redactors can be combined with `LogRedactor.chain(...)` for application-specific identifiers.

The OkHttp integration masks every URL query value in request, response, and failure logs. It redacts known email and JWT path segments and fully masks the path segment immediately following recognized credential operations such as `reset-password`, verification, activation, invitation, magic-link, and `token` routes. This path policy is heuristic: arbitrary application-specific opaque path secrets require a custom URL design or must not be logged. Request and response bodies remain disabled by default. When request-body logging is enabled, only repeatable text bodies are copied into an abortable bounded sink; one-shot, duplex, and binary bodies are skipped, and the request passed to OkHttp is never replaced. This bounds the logger-owned buffer, not allocations performed inside a custom `RequestBody`. Repeatable body implementations can be invoked once for logging and again by OkHttp. Collection state is checked lazily for the default `FileLogger`; custom `Logger` implementations do not expose a loggability signal.

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

The remote batch lifecycle is `accepted -> memory bounded -> temporary file -> synced
payload -> committed batch file -> uploader acknowledgement -> deletion`. Persist
failure leaves the batch in bounded memory and makes the current flush/close fail.
Later barriers retry it. Process death before local commit loses memory-only records;
committed files survive and are replayed. Temporary files are never uploaded.

New committed names contain a collision-resistant v2 UUID. Legacy names use a
persistent spool namespace to derive a restart-stable identity. Corrupt or previously
initialized-but-missing namespace metadata stops legacy upload instead of silently
changing its idempotency identity. Temp creation is exclusive and cross-process persist
operations use a spool lock. Unacknowledged
committed files are not quota-evicted; a full spool rejects the new persistence attempt
and reports failure. The payload file is
`fsync`ed before same-directory `renameTo`. The Java API cannot prove directory-entry
durability or atomic rename semantics for every filesystem; rename failure is treated
as persistence failure, but sudden power loss immediately after a successful rename
remains a filesystem-dependent risk. Before upload, a committed file is atomically
renamed to a claim file and held with an OS file lock. Cooperating local workers cannot
upload the same claim concurrently, and unlocked stale claims are recovered after a
restart. A crash after remote acknowledgement but before local deletion can still
cause duplicate delivery. `IdentifiedRemoteLogUploader` receives the stable batch ID
and an attempt-specific ID. Optional cancellation applies only to that attempt and
does not delete server data. Exactly-once effects still require server-side atomic
deduplication; the library provides no standalone exactly-once guarantee.

Remote spool payloads are bounded to 16 MiB on both persist and recovery reads. Empty
or oversized files are retained, counted as failures, and skipped for the remainder of
the current scan so later healthy batches are not starved. `flush()` remains `false`
while rejected durable data remains. The current file format has no embedded checksum,
so non-empty partial truncation is not always detectable. Consent revoke registers a
process-local revocation before a new upload attempt can register; the durable pause
marker is the cross-process linearization point. Successful revoke guarantees that no
new cooperating process claim starts after completion, not that a server forgets a
request already received.
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
