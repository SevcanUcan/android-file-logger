# Multiplatform Architecture

## Scope

`filelogger-core` is the platform-neutral entry point for Kotlin code shared by
Android, JVM, iOS, and Wasm applications. The existing `filelogger` Android artifact
continues to own Android `Context`, Logcat, lifecycle flush, crash capture, file I/O,
retention, export, and sharing.

This split keeps the existing Android API and artifact compatible while making the
logging model and dispatch engine usable from `commonMain`.

## Published targets

| Target | Publication |
| --- | --- |
| Common metadata | `filelogger-core` |
| Android | `filelogger-core-android` |
| JVM | `filelogger-core-jvm` |
| iOS device | `filelogger-core-iosarm64` |
| iOS simulator | `filelogger-core-iossimulatorarm64` |
| Wasm/JS | `filelogger-core-wasm-js` |

The Kotlin Multiplatform plugin publishes root module metadata, target artifacts,
and source artifacts. Consumers should depend on `filelogger-core` from
`commonMain`; Gradle selects the correct target artifact.

## Runtime model

`DefaultLogger` performs level and per-tag filtering before allocating a record.
Static context is snapshotted at construction and event attributes override matching
static keys. Throwables are converted to text immediately so records do not retain
platform exception objects.

Destination exceptions are isolated by default. Set
`propagateDestinationFailures = true` only in tests or specialized hosts that need
fail-fast behavior. `CompositeLogDestination` continues delivery when one child
destination fails.

`BoundedMemoryLogDestination` stores immutable snapshots through Kotlin's common
atomic API. Concurrent producers cannot exceed its configured capacity; when full,
the oldest record is replaced.

## Android bridge

Initialize the Android logger normally, then route a common logger through it:

```kotlin
FileLogger.install(application, LoggerConfig.prod())

val logger = com.filelogger.core.DefaultLogger(
    destination = com.filelogger.kmp.FileLoggerDestination(),
    config = com.filelogger.core.LoggerConfig(
        processName = application.packageName,
        context = mapOf("platform" to "android")
    )
)
```

The bridge maps levels, attributes, process name, execution name, and throwable text
into the existing Android pipeline. Queue bounds, error sync fallback, rotation,
retention, redaction, flush, and diagnostics therefore remain centralized.

## Build and publication checks

```shell
./gradlew :filelogger-core:jvmTest
./gradlew :filelogger-core:wasmJsNodeTest
./gradlew :filelogger-core:compileKotlinIosArm64
./gradlew :filelogger-core:compileKotlinIosSimulatorArm64
./gradlew apiCheck publishAllPublicationsToTestRepository
```

CI runs JVM/Wasm checks on Linux and compiles both Apple targets on macOS. Binary
API dumps are tracked for Android and JVM. Kotlin/Native and Wasm ABI validation is
not yet provided by the current binary compatibility validator, so compile and
publication checks are the release gates for those targets.

## Kotlin Toolchain 0.12

Kotlin Toolchain 0.12 can publish the same target family from a concise module file,
but it remains Alpha and would replace this repository's mature Gradle/AGP build
rather than act as a Gradle plugin. The library therefore uses the production Kotlin
Multiplatform Gradle plugin today. The source-set layout and target set intentionally
match a future Toolchain module:

```yaml
product:
  type: lib
  platforms: [jvm, android, iosArm64, iosSimulatorArm64, wasmJs]
```

A future Toolchain migration should first prove Android instrumentation tests,
binary API checks, Compose sample builds, JitPack/Maven coordinates, signing, and
release rollback in a parallel pipeline.

## Current limitations

- iOS and Wasm file destinations are not included yet; applications provide a
  platform destination or use the bounded memory destination.
- iOS x64 simulators and macOS/Linux native targets are not currently published.
- The common core is intentionally synchronous. Platform destinations decide how
  to queue, batch, persist, or upload records.
- `executionName` defaults to `unknown` because a portable thread concept does not
  exist on every target. Hosts can inject `executionNameProvider`.
