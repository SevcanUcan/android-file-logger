package com.filelogger

import java.io.File

internal object LoggerEngine {

    fun createDefaultDestination(
        filesDirProvider: () -> File,
        configProvider: () -> LoggerConfig
    ): LogDestination {
        val syncFileDestination = FileLogDestination(
            fileProvider = {
                File(
                    File(filesDirProvider(), configProvider().logFolder),
                    configProvider().logFileName
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
        val fileDestination = if (configProvider().errorSyncFallbackEnabled) {
            ErrorSyncFallbackDestination(
                asyncDestination = asyncFileDestination,
                syncDestination = syncFileDestination
            )
        } else {
            asyncFileDestination
        }

        return CompositeLogDestination(
            listOf(
                LogcatDestination(),
                fileDestination
            )
        )
    }
}
