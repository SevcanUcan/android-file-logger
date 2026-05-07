package com.filelogger

import java.io.File

internal object LoggerEngine {

    fun createDefaultDestination(
        filesDirProvider: () -> File,
        configProvider: () -> LoggerConfig,
        packageNameProvider: () -> String,
        processNameProvider: () -> String
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
                    File(filesDirProvider(), config.logFolder),
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
