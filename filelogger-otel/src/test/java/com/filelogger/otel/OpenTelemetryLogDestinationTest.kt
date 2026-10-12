package com.filelogger.otel

import com.filelogger.LogLevel
import com.filelogger.LogRecord
import io.opentelemetry.api.logs.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenTelemetryLogDestinationTest {

    @Test
    fun `filters records below configured level`() {
        val events = mutableListOf<OpenTelemetryEvent>()
        val destination = destination(events, OpenTelemetryLogConfig(minimumLogLevel = LogLevel.WARN))

        destination.write(record(level = LogLevel.INFO))

        assertTrue(events.isEmpty())
    }

    @Test
    fun `maps severity timestamp and resource context`() {
        val events = mutableListOf<OpenTelemetryEvent>()
        val destination = destination(events)

        destination.write(record(level = LogLevel.ERROR, throwableText = "safe stack"))

        val event = events.single()
        assertEquals(123L, event.timestampMillis)
        assertEquals(Severity.ERROR, event.severity)
        assertEquals("ERROR", event.severityText)
        assertEquals("Checkout", event.attributes["log.tag"])
        assertEquals("safe stack", event.attributes["exception.stacktrace"])
    }

    @Test
    fun `bounds message and custom attributes`() {
        val events = mutableListOf<OpenTelemetryEvent>()
        val destination = destination(
            events,
            OpenTelemetryLogConfig(maxMessageLength = 5, maxAttributes = 1)
        )

        destination.write(
            record(
                message = "123456789",
                attributes = linkedMapOf("order" to "123456", "ignored" to "x")
            )
        )

        val event = events.single()
        assertEquals("12345", event.body)
        assertEquals("12345", event.attributes["app.order"])
        assertTrue("app.ignored" !in event.attributes)
    }

    private fun destination(
        events: MutableList<OpenTelemetryEvent>,
        config: OpenTelemetryLogConfig = OpenTelemetryLogConfig()
    ) = OpenTelemetryLogDestination(OpenTelemetryLogEmitter(events::add), config, Unit)

    private fun record(
        level: LogLevel = LogLevel.INFO,
        message: String = "payment submitted",
        attributes: Map<String, String> = emptyMap(),
        throwableText: String? = null
    ) = LogRecord(
        timestampMillis = 123L,
        level = level,
        tag = "Checkout",
        message = message,
        threadName = "main",
        processName = "app",
        attributes = attributes,
        throwableText = throwableText
    )
}
