package com.filelogger

data class LoggerConfig(
    val maxFileSize: Long = 3 * 1024 * 1024,
    val maxBackupFiles: Int = 3,
    val logFolder: String = "logs",
    val logFileName: String = "app_log.txt",
    val zipPrefix: String = "AppLog",
    val logFormatter: LogFormatter = JsonLogFormatter(),
    val asyncQueueCapacity: Int = 1024,
    val asyncOverflowStrategy: AsyncOverflowStrategy = AsyncOverflowStrategy.DROP_OLDEST
) {
    init {
        require(asyncQueueCapacity > 0) {
            "asyncQueueCapacity must be greater than zero"
        }
    }
}
