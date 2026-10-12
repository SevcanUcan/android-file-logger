package com.filelogger.otel

import com.filelogger.LogLevel

data class OpenTelemetryLogConfig(
    val minimumLogLevel: LogLevel = LogLevel.INFO,
    val instrumentationName: String = "com.filelogger",
    val instrumentationVersion: String? = null,
    val maxMessageLength: Int = 16_384,
    val maxAttributes: Int = 32,
    val attributePrefix: String = "app."
) {
    init {
        require(instrumentationName.isNotBlank()) { "instrumentationName must not be blank" }
        require(maxMessageLength > 0) { "maxMessageLength must be greater than zero" }
        require(maxAttributes >= 0) { "maxAttributes must not be negative" }
        require(attributePrefix.isNotBlank()) { "attributePrefix must not be blank" }
    }
}
