package com.filelogger

import java.util.concurrent.TimeUnit

data class LoggerConfig(
    val maxFileSize: Long = 3 * 1024 * 1024,
    val maxBackupFiles: Int = 3,
    val maxTotalLogSize: Long = 50 * 1024 * 1024,
    val maxLogAgeMillis: Long = TimeUnit.DAYS.toMillis(7),
    val logFolder: String = "logs",
    val logFileName: String = "app_log.txt",
    val zipPrefix: String = "AppLog",
    val minimumLogLevel: LogLevel = LogLevel.DEBUG,
    val logFormatter: LogFormatter = JsonLogFormatter(),
    val asyncQueueCapacity: Int = 1024,
    val asyncOverflowStrategy: AsyncOverflowStrategy = AsyncOverflowStrategy.DROP_OLDEST,
    val errorSyncFallbackEnabled: Boolean = true,
    val useProcessSpecificLogFiles: Boolean = true
) {
    init {
        require(asyncQueueCapacity > 0) {
            "asyncQueueCapacity must be greater than zero"
        }
        require(maxTotalLogSize >= 0L) {
            "maxTotalLogSize must not be negative"
        }
        require(maxLogAgeMillis >= 0L) {
            "maxLogAgeMillis must not be negative"
        }
    }

    companion object {
        fun dev(): LoggerConfig {
            return LoggerConfig(
                minimumLogLevel = LogLevel.DEBUG,
                logFormatter = PlainTextLogFormatter(),
                maxFileSize = 5 * 1024 * 1024,
                maxBackupFiles = 5,
                maxTotalLogSize = 50 * 1024 * 1024,
                maxLogAgeMillis = TimeUnit.DAYS.toMillis(10),
                asyncQueueCapacity = 2_048,
                asyncOverflowStrategy = AsyncOverflowStrategy.DROP_OLDEST,
                errorSyncFallbackEnabled = true
            )
        }

        fun prod(): LoggerConfig {
            return LoggerConfig(
                minimumLogLevel = LogLevel.WARN,
                logFormatter = JsonLogFormatter(),
                maxFileSize = 3 * 1024 * 1024,
                maxBackupFiles = 3,
                maxTotalLogSize = 25 * 1024 * 1024,
                maxLogAgeMillis = TimeUnit.DAYS.toMillis(7),
                asyncQueueCapacity = 1_024,
                asyncOverflowStrategy = AsyncOverflowStrategy.DROP_OLDEST,
                errorSyncFallbackEnabled = true
            )
        }
    }
}
