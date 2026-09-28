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
    val useProcessSpecificLogFiles: Boolean = true,
    val autoFlushOnAppBackground: Boolean = true,
    val crashCaptureEnabled: Boolean = true,
    val crashBufferSize: Int = 64,
    val sessionLoggingEnabled: Boolean = true,
    val sessionId: String? = null,
    val sessionFolderPrefix: String = "session",
    val customDestinations: List<LogDestination> = emptyList()
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
        require(crashBufferSize >= 0) {
            "crashBufferSize must not be negative"
        }
        require(sessionFolderPrefix.isNotBlank()) {
            "sessionFolderPrefix must not be blank"
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
                errorSyncFallbackEnabled = true,
                autoFlushOnAppBackground = true,
                crashCaptureEnabled = true,
                crashBufferSize = 128,
                sessionLoggingEnabled = true
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
                errorSyncFallbackEnabled = true,
                autoFlushOnAppBackground = true,
                crashCaptureEnabled = true,
                crashBufferSize = 64,
                sessionLoggingEnabled = true
            )
        }
    }

    class Builder {
        private var config = LoggerConfig()

        fun setMinimumLogLevel(level: LogLevel) = apply {
            config = config.copy(minimumLogLevel = level)
        }

        fun setLogFormatter(formatter: LogFormatter) = apply {
            config = config.copy(logFormatter = formatter)
        }

        fun setMaxFileSize(bytes: Long) = apply {
            config = config.copy(maxFileSize = bytes)
        }

        fun setMaxBackupFiles(count: Int) = apply {
            config = config.copy(maxBackupFiles = count)
        }

        fun setMaxTotalLogSize(bytes: Long) = apply {
            config = config.copy(maxTotalLogSize = bytes)
        }

        fun setMaxLogAgeMillis(millis: Long) = apply {
            config = config.copy(maxLogAgeMillis = millis)
        }

        fun setAsyncQueueCapacity(capacity: Int) = apply {
            config = config.copy(asyncQueueCapacity = capacity)
        }

        fun setAsyncOverflowStrategy(strategy: AsyncOverflowStrategy) = apply {
            config = config.copy(asyncOverflowStrategy = strategy)
        }

        fun setErrorSyncFallbackEnabled(enabled: Boolean) = apply {
            config = config.copy(errorSyncFallbackEnabled = enabled)
        }

        fun setAutoFlushOnAppBackground(enabled: Boolean) = apply {
            config = config.copy(autoFlushOnAppBackground = enabled)
        }

        fun setCrashCaptureEnabled(enabled: Boolean) = apply {
            config = config.copy(crashCaptureEnabled = enabled)
        }

        fun setCrashBufferSize(size: Int) = apply {
            config = config.copy(crashBufferSize = size)
        }

        fun setSessionLoggingEnabled(enabled: Boolean) = apply {
            config = config.copy(sessionLoggingEnabled = enabled)
        }

        fun setSessionId(id: String?) = apply {
            config = config.copy(sessionId = id)
        }

        fun setSessionFolderPrefix(prefix: String) = apply {
            config = config.copy(sessionFolderPrefix = prefix)
        }

        fun addDestination(destination: LogDestination) = apply {
            config = config.copy(customDestinations = config.customDestinations + destination)
        }

        fun build(): LoggerConfig = config
    }
}
