# Technical Overview

This document describes the current architecture of `android-file-logger`, the reliability decisions already implemented, and the remaining gaps that should guide the next work.

## Current State

The library now has a small but production-oriented core:

- structured log records
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
- zip export with optional line redaction and diagnostics metadata
- public diagnostics for queue pressure and current log files
- runtime per-tag log-level overrides
- lazy logging overloads for expensive messages
- builder-based config creation
- custom destination attachment

## Public API

The main entry point is `FileLogger`.

```kotlin
FileLogger.init(
    context = applicationContext,
    config = LoggerConfig.prod()
)

FileLogger.d("Startup", "Logger ready")
FileLogger.w("Sync", "Retry scheduled")
FileLogger.e("Crash", "Unexpected failure", throwable)
FileLogger.flush()
FileLogger.shutdown()

val archive = FileLogger.exportLogs()
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
- thread name
- process name

`LogLevel` currently supports:

- `DEBUG`
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
- `sessionLoggingEnabled`
- `sessionId`
- `sessionFolderPrefix`
- `customDestinations`

Two presets exist:

- `LoggerConfig.dev()`: verbose, plain text, longer retention, larger queue
- `LoggerConfig.prod()`: `WARN+`, JSON, tighter retention, production defaults

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

## Destination Pipeline

Default flow:

1. `FileLogger` receives a log call.
2. `DefaultLogger` applies the minimum level policy.
3. `DefaultLogger` creates a `LogRecord`.
4. `CompositeLogDestination` fans the record out.
5. `LogcatDestination` writes immediately to Logcat.
6. File output goes through `ErrorSyncFallbackDestination`.
7. Non-error records go through `AsyncLogDestination`.
8. `ERROR` records go directly to `FileLogDestination` and are flushed.
9. `FileLogDestination` formats, rotates, writes, and can flush the file descriptor.
10. Optional custom destinations receive the same record.
11. Optional recent log buffer stores the last N records for crash capture.

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

This reduces the chance of losing the final important log before a crash. It does not replace full crash capture; it is a lightweight reliability layer.

## Crash Capture

When `crashCaptureEnabled` is true, the default pipeline includes a `RecentLogBuffer`. It stores the last `crashBufferSize` records in memory.

`FileLogger` installs an uncaught exception handler. On crash it:

1. writes buffered records back through the destination as `ERROR` records with a `CrashBuffer/` tag prefix
2. logs the uncaught exception as `FileLoggerCrash`
3. calls `flush()`
4. delegates to the previously installed uncaught exception handler

This is best-effort crash capture. It helps with Kotlin/Java uncaught exceptions, but cannot cover every native crash or OS kill scenario.

## Export And Redaction

`FileLogger.exportLogs()` flushes the pipeline and writes current session log files to a zip archive.

```kotlin
val archive = FileLogger.exportLogs()
```

Callers can provide a `LogRedactor` to sanitize lines before they enter the zip:

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

## Remaining Gaps

These are the next meaningful gaps after the recent reliability work:

- **Integration tests:** current coverage is mostly JVM unit tests; Android instrumentation should verify real app file paths, lifecycle, and process behavior.
- **Fault injection:** failing file writes, rename failures, and disk-full conditions need dedicated tests.
- **OkHttp network logging:** no interceptor extension module yet.
- **Remote upload destination:** no batching, retry, gzip, or upload destination yet.
- **In-app log viewer:** diagnostics and export exist, but no UI component yet.
- **Structured query API:** export exists, but there is no public log reader/filter API over existing files yet.

## Recommended Next Work

1. Add Android instrumentation tests for init, export, lifecycle callback, and app file paths.
2. Add fault-injection tests for failing file writes, failed `renameTo`, and disk-full behavior.
3. Add an optional OkHttp extension module.
4. Add a remote upload destination with batching, gzip, retry, and backoff.
5. Build an in-app log viewer on top of diagnostics, filtering, and export.
