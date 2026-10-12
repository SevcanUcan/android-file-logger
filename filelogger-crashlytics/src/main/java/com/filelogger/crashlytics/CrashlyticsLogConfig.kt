package com.filelogger.crashlytics

import com.filelogger.LogLevel

data class CrashlyticsLogConfig(
    val minimumLogLevel: LogLevel = LogLevel.WARN,
    val recordNonFatalExceptions: Boolean = true,
    val maxMessageLength: Int = 4_096,
    val maxAttributes: Int = 16,
    val attributePrefix: String = "filelogger."
) {
    init {
        require(maxMessageLength > 0) { "maxMessageLength must be greater than zero" }
        require(maxAttributes >= 0) { "maxAttributes must not be negative" }
        require(attributePrefix.isNotBlank()) { "attributePrefix must not be blank" }
    }
}
