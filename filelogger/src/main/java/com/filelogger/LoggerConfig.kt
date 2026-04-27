package com.filelogger

data class LoggerConfig(
    val maxFileSize: Long = 3 * 1024 * 1024,
    val logFolder: String = "logs",
    val logFileName: String = "app_log.txt",
    val zipPrefix: String = "AppLog"
)
