package com.filelogger

import java.io.File

internal object LoggerEngine {

    fun createDefaultDestination(
        logDirectoryProvider: () -> File,
        configProvider: () -> LoggerConfig,
        packageNameProvider: () -> String,
        processNameProvider: () -> String,
        recentLogBuffer: RecentLogBuffer? = null
    ): LogDestination {
        val syncFileDestination = FileLogDestination(
            fileProvider = {
                val config = configProvider()
                val logFileName = if (config.useProcessSpecificLogFiles) {
                    ProcessLogFileName.forProcess(
                        baseFileName = config.logFileName,
                        packageName = packageNameProvider(),
                        processName = processNameProvider()
                    )
                } else {
                    config.logFileName
                }

                File(
                    logDirectoryProvider(),
                    logFileName
                )
            },
            formatter = configProvider().logFormatter,
            rotationPolicy = FileRotationPolicy(
                maxFileSize = configProvider().maxFileSize,
                maxBackupFiles = configProvider().maxBackupFiles,
                maxTotalLogSize = configProvider().maxTotalLogSize,
                maxLogAgeMillis = configProvider().maxLogAgeMillis
            ),
        )
        val asyncFileDestination = AsyncLogDestination(
            delegate = syncFileDestination,
            queueCapacity = configProvider().asyncQueueCapacity,
            overflowStrategy = configProvider().asyncOverflowStrategy
        )
        val diagnosticAsyncDestination = AsyncLogDiagnosticsDestination(asyncFileDestination)
        val fileDestination = if (configProvider().errorSyncFallbackEnabled) {
            ErrorSyncFallbackDestination(
                asyncDestination = diagnosticAsyncDestination,
                syncDestination = syncFileDestination
            )
        } else {
            diagnosticAsyncDestination
        }

        return RedactingLogDestination(
            delegate = CompositeLogDestination(
                listOf(
                    LogcatDestination(),
                    fileDestination,
                    LogMetricsDestination()
                ) + listOfNotNull(recentLogBuffer) + configProvider().customDestinations
            ),
            redactor = configProvider().redactor
        )
    }
}
