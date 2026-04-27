package com.filelogger

data class LogRecord(
    val timestampMillis: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwable: Throwable? = null,
    val threadName: String,
    val processName: String
)
