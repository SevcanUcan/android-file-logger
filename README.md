# Android File Logger

`android-file-logger` is a logging library for Android projects.

It started from a simple need: writing logs to a file in a way that could be reused across projects. Over time, the goal grew into building a logging library that stays easy to use, but is flexible enough to support different logging needs without turning into a mess.

## What this project is for

A lot of Android projects start with `Logcat` only. That is usually fine during development, but it becomes limiting when logs need to be stored, reviewed later, shared, or sent somewhere else.

At the same time, many teams end up building small in-app logging helpers that work for a while, then slowly become harder to maintain.

This project is an attempt to provide a cleaner middle ground: a reusable logging library with a simple API, background file writing, and room to grow into multiple output targets.

## Current state

The project is still under active development, but the base library module is already in place.

At the moment, it provides:

- a dedicated Android library module
- a simple logger API
- background file writing
- a module structure that can be built and published cleanly
- a foundation that can be expanded into a more modular architecture

## Direction

The plan is to keep the library practical and modular without making it unnecessarily complex.

That includes:

- a clear public logger API
- separation between API and implementation
- support for multiple destinations such as file, Logcat, and remote services
- configurable output formatting
- asynchronous, non-blocking logging
- basic file rotation support
- dependency injection support
- export and sharing flows for cases where logs need to be sent out

## Output formats

Not every team wants logs in the same format.

Some prefer JSON because it is easier to parse and process. Others prefer plain text because it is easier to read, inspect, and share quickly. Both are valid.

This library is being shaped to support different formatting styles depending on the use case:

- JSON
- plain text
- custom formatters later when needed

So this is not meant to be a JSON-only logger. Formatting should be a replaceable part of the system.

## Module structure

```text
app/            sample app module
filelogger/     reusable Android library module
```

## Installation

For now, the library can be added as a module dependency.

In `settings.gradle.kts`:

```kotlin
include(":filelogger")
```

In your app module:

```kotlin
dependencies {
    implementation(project(":filelogger"))
}
```

## Basic usage

Initialize the logger:

```kotlin
import com.filelogger.FileLogger

class App : Application() {

    override fun onCreate() {
        super.onCreate()

        FileLogger.init(applicationContext)
    }
}
```

Write logs:

```kotlin
FileLogger.d("MainActivity", "App started")
FileLogger.w("MainActivity", "Potential issue detected")
FileLogger.e("MainActivity", "Unexpected error", throwable)
```

## Log data

The intended log structure includes fields such as:

- timestamp
- level
- tag
- message
- throwable
- thread
- process

These details should become more configurable as the library grows.

## Roadmap

Some of the next steps are:

- defining a cleaner public API
- introducing a proper `LogDestination` model
- adding formatter abstractions such as `JsonLogFormatter` and `PlainTextLogFormatter`
- supporting multiple destinations
- improving async reliability
- adding file size management and rotation
- expanding test coverage
- improving documentation and example usage

## Design approach

A few things matter in this project from the start:

- keeping the API easy to understand
- keeping classes small and focused
- preferring composition over inheritance
- avoiding unnecessary Android coupling where possible
- making the library testable
- avoiding complexity that does not pay off

## Contributing

The project is still taking shape, so feedback is always useful.

If you are interested in this kind of library, ideas and contributions are welcome, especially around:

- API design
- formatter design
- async reliability
- file rotation
- remote logging
- real-world usage feedback

## License

A license has not been added yet.
