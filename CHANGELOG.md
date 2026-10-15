# Changelog

All notable changes to this project will be documented in this file.

The project follows Semantic Versioning. Release candidates may change before the
final `1.0.0` API baseline is declared stable.

## Unreleased

## [1.0.0-rc6] - 2026-10-15

### Changed

- Prepared the Maven Central release candidate under the verified
  `io.github.sevcanucan` namespace.
- Added signed Central Portal publications, complete POM metadata, sources
  and javadoc artifacts, and a protected manual release workflow.
- Added a dedicated GitHub Actions workflow to diagnose Maven signing.
- Validated the GPG private key and Gradle publication signing in CI.
- Preserved existing JitPack publishing compatibility.

## [1.0.0-rc4] - 2026-10-12

### Added

- Added an optional Firebase Crashlytics destination with level filtering,
  bounded metadata, and redacted non-fatal reporting.
- Added an optional OpenTelemetry logs destination with severity mapping,
  bounded attributes, and compatibility with application-owned OTLP pipelines.
- Documented that optional destinations are library modules packaged into the
  host application's single APK or AAB, not separate applications.

## [1.0.0-rc3] - 2026-10-12

### Added

- Added the `filelogger-core` Kotlin Multiplatform API and engine for Android,
  JVM, iOS device/simulator, and Wasm/JS targets.
- Added an Android bridge so shared Kotlin log records can use the existing
  FileLogger disk, retention, export, and diagnostics pipeline.
- Added bounded, thread-safe in-memory logging for shared code.

### Changed

- Split JVM, Android API, Wasm, iOS compilation, and publication checks into
  explicit CI verification gates.
- Stabilized Compose API validation by excluding compiler-generated singleton
  classes from the public API baseline.

## [1.0.0-rc2] - 2026-10-01

### Fixed

- Removed the core module's unnecessary transitive AndroidX Core dependency so
  applications on compileSdk 35 and Android Gradle Plugin 8.6 can consume the library.
- Preserved support-report app version metadata with platform APIs back to API 24.

## [1.0.0-rc1] - 2026-10-01

### Added

- Bounded asynchronous logging with drop-oldest, drop-newest, and blocking overflow policies.
- Durable flush, lifecycle auto-flush, synchronous ordered `ERROR` fallback, and crash capture.
- Hybrid retention by active-file size, backup count, total storage, and maximum age.
- Process-specific files, session folders, structured attributes, global context, and `INFO` logs.
- Write-time sensitive-data redaction, collection consent, local deletion, and remote-spool deletion.
- Searchable JSON/plain stored logs, diagnostics, metrics, ZIP export, and share-intent support.
- Privacy-bounded support reports with app/device metadata, user notes, and custom sections.
- Redacted bounded breadcrumb timelines included in support reports and persisted during crashes.
- Optional OkHttp interceptor, offline remote delivery, and Compose log viewer modules.
- Sample application, fault-injection tests, multi-process tests, and real crash simulations.

### Reliability

- Verified debug and release artifacts with JVM, Android instrumentation, lint, and local Maven publication checks.
- Verified integration in the Expensio Drive application with unit and device UI tests.
- Added GitHub Actions verification and Android emulator jobs.
- Added public API compatibility baselines and an R8-minified instrumentation target.

### Known boundaries

- Native crashes, force-stop, sudden power loss, and OS termination can occur before managed callbacks finish.
- Remote delivery requires an application-provided uploader and security policy.
- Final `1.0.0` publication is intentionally deferred until release-candidate validation is complete.
