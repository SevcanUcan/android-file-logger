package com.filelogger.core

import kotlinx.datetime.Clock

class DefaultLogger(
    private val destination: LogDestination,
    config: LoggerConfig = LoggerConfig(),
    private val timestampProvider: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val executionNameProvider: () -> String = { "unknown" }
) : Logger {
    private val config = config.copy(
        tagMinimumLevels = config.tagMinimumLevels.toMap(),
        context = config.context.toMap()
    )

    override fun log(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable?,
        attributes: Map<String, String>
    ) {
        val minimumLevel = config.tagMinimumLevels[tag] ?: config.minimumLevel
        if (!level.isAtLeast(minimumLevel)) return

        val write = {
            destination.write(
                LogRecord(
                    timestampMillis = timestampProvider(),
                    level = level,
                    tag = tag,
                    message = message,
                    throwableText = throwable?.stackTraceToString(),
                    executionName = executionNameProvider(),
                    processName = config.processName,
                    attributes = config.context + attributes
                )
            )
        }

        if (config.propagateDestinationFailures) {
            write()
        } else {
            try {
                write()
            } catch (_: Exception) {
                // Logging failures must not crash the host application by default.
            }
        }
    }
}
