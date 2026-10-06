package com.filelogger.core

data class LogRecord(
    val timestampMillis: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwableText: String? = null,
    val executionName: String = "unknown",
    val processName: String = "unknown",
    val attributes: Map<String, String> = emptyMap()
)
