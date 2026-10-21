package com.filelogger

import java.io.File

internal object LoggerEngine {

    fun createDefaultDestination(
        logDirectoryProvider: () -> File,
        configProvider: () -> LoggerConfig,
        packageNameProvider: () -> String,
        processNameProvider: () -> String,
        recentLogBuffer: RecentLogBuffer? = null,
        storageCoordinator: LogStorageCoordinator? = null
    ): LogDestination {
        val activeDirectory = logDirectoryProvider()
        val config = configProvider()
        val rootDirectory = if (config.sessionLoggingEnabled) {
            activeDirectory.parentFile ?: activeDirectory
        } else {
            activeDirectory
        }
        val coordinator = storageCoordinator ?: LogStorageCoordinator(rootDirectory)
        val sessionLease = if (config.sessionLoggingEnabled) {
            SessionLease.acquire(coordinator, activeDirectory)
        } else {
            null
        }
        val effectiveFileName = if (config.useProcessSpecificLogFiles) {
            ProcessLogFileName.forProcess(config.logFileName, packageNameProvider(), processNameProvider())
        } else {
            config.logFileName
        }
        val retentionPolicy = GlobalLogRetentionPolicy(
            root = coordinator.root,
            activeDirectory = activeDirectory,
            baseLogFileName = config.logFileName,
            sessionLoggingEnabled = config.sessionLoggingEnabled,
            sessionFolderPrefix = config.sessionFolderPrefix,
            maxTotalLogSize = config.maxTotalLogSize,
            maxLogAgeMillis = config.maxLogAgeMillis
        )
        try {
            coordinator.withExclusiveAccess {
                retentionPolicy.enforce(
                    ContainedPathResolver.resolve(
                        root = activeDirectory,
                        relativePath = effectiveFileName,
                        fieldName = "logFileName",
                        allowNested = false
                    )
                )
            }
        } catch (error: Exception) {
            runCatching { sessionLease?.close() }
            throw error
        }
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

                ContainedPathResolver.resolve(
                    root = logDirectoryProvider(),
                    relativePath = logFileName,
                    fieldName = "logFileName",
                    allowNested = false
                )
            },
            formatter = configProvider().logFormatter,
            rotationPolicy = FileRotationPolicy(
                maxFileSize = configProvider().maxFileSize,
                maxBackupFiles = configProvider().maxBackupFiles,
                maxTotalLogSize = configProvider().maxTotalLogSize,
                maxLogAgeMillis = configProvider().maxLogAgeMillis,
                globalRetentionPolicy = retentionPolicy
            ),
            storageCoordinator = coordinator,
            closeAction = sessionLease?.let { lease -> { lease.close() } }
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
