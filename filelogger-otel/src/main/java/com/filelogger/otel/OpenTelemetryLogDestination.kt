package com.filelogger.otel

import com.filelogger.LogDestination
import com.filelogger.LogLevel
import com.filelogger.LogRecord
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import java.util.concurrent.TimeUnit

class OpenTelemetryLogDestination private constructor(
    private val emitter: OpenTelemetryLogEmitter,
    private val config: OpenTelemetryLogConfig
) : LogDestination {

    constructor(
        openTelemetry: OpenTelemetry,
        config: OpenTelemetryLogConfig = OpenTelemetryLogConfig()
    ) : this(
        OpenTelemetryApiEmitter(
            openTelemetry.logsBridge.loggerBuilder(config.instrumentationName).apply {
                config.instrumentationVersion?.let(::setInstrumentationVersion)
            }.build()
        ),
        config
    )

    override fun write(record: LogRecord) {
        if (!record.level.isAtLeast(config.minimumLogLevel)) return
        emitter.emit(
            OpenTelemetryEvent(
                timestampMillis = record.timestampMillis,
                severity = record.level.toSeverity(),
                severityText = record.level.name,
                body = record.message.take(config.maxMessageLength),
                attributes = buildMap {
                    put("log.tag", record.tag)
                    put("thread.name", record.threadName)
                    put("process.name", record.processName)
                    record.throwableText?.let {
                        put("exception.stacktrace", it.take(config.maxMessageLength))
                    }
                    record.attributes.entries.take(config.maxAttributes).forEach { (key, value) ->
                        put("${config.attributePrefix}$key", value.take(config.maxMessageLength))
                    }
                }
            )
        )
    }

    internal constructor(
        emitter: OpenTelemetryLogEmitter,
        config: OpenTelemetryLogConfig,
        @Suppress("UNUSED_PARAMETER") testing: Unit = Unit
    ) : this(emitter, config)
}

internal data class OpenTelemetryEvent(
    val timestampMillis: Long,
    val severity: Severity,
    val severityText: String,
    val body: String,
    val attributes: Map<String, String>
)

internal fun interface OpenTelemetryLogEmitter {
    fun emit(event: OpenTelemetryEvent)
}

private class OpenTelemetryApiEmitter(
    private val logger: Logger
) : OpenTelemetryLogEmitter {
    override fun emit(event: OpenTelemetryEvent) {
        val builder = logger.logRecordBuilder()
            .setTimestamp(event.timestampMillis, TimeUnit.MILLISECONDS)
            .setSeverity(event.severity)
            .setSeverityText(event.severityText)
            .setBody(event.body)
        event.attributes.forEach { (key, value) ->
            builder.setAttribute(AttributeKey.stringKey(key), value)
        }
        builder.emit()
    }
}

private fun LogLevel.toSeverity(): Severity = when (this) {
    LogLevel.DEBUG -> Severity.DEBUG
    LogLevel.INFO -> Severity.INFO
    LogLevel.WARN -> Severity.WARN
    LogLevel.ERROR -> Severity.ERROR
}

private fun LogLevel.isAtLeast(minimum: LogLevel): Boolean = ordinal >= minimum.ordinal
