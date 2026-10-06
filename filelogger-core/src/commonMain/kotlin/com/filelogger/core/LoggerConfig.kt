package com.filelogger.core

data class LoggerConfig(
    val minimumLevel: LogLevel = LogLevel.DEBUG,
    val tagMinimumLevels: Map<String, LogLevel> = emptyMap(),
    val context: Map<String, String> = emptyMap(),
    val processName: String = "unknown",
    val propagateDestinationFailures: Boolean = false
)
