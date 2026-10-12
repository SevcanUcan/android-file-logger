package com.filelogger.crashlytics

import com.filelogger.LogLevel
import com.filelogger.LogRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashlyticsLogDestinationTest {

    @Test
    fun `filters records below configured level`() {
        val client = RecordingClient()
        val destination = destination(client)

        destination.write(record(level = LogLevel.INFO))

        assertTrue(client.logs.isEmpty())
    }

    @Test
    fun `forwards bounded message and metadata`() {
        val client = RecordingClient()
        val destination = destination(
            client,
            CrashlyticsLogConfig(maxMessageLength = 20, maxAttributes = 1)
        )

        destination.write(
            record(
                message = "a very long diagnostic message",
                attributes = linkedMapOf("account" to "42", "ignored" to "value")
            )
        )

        assertEquals(20, client.logs.single().length)
        assertEquals("WARN", client.keys["filelogger.level"])
        assertEquals("Checkout", client.keys["filelogger.tag"])
        assertEquals("account=42", client.keys["filelogger.attributes"])
    }

    @Test
    fun `records redacted throwable text as non fatal`() {
        val client = RecordingClient()
        val destination = destination(client)

        destination.write(record(level = LogLevel.ERROR, throwableText = "redacted stack"))

        assertEquals("redacted stack", client.throwables.single().message)
    }

    private fun destination(
        client: CrashlyticsClient,
        config: CrashlyticsLogConfig = CrashlyticsLogConfig()
    ) = CrashlyticsLogDestination(client, config, Unit)

    private fun record(
        level: LogLevel = LogLevel.WARN,
        message: String = "payment failed",
        attributes: Map<String, String> = emptyMap(),
        throwableText: String? = null
    ) = LogRecord(
        timestampMillis = 1L,
        level = level,
        tag = "Checkout",
        message = message,
        threadName = "main",
        processName = "app",
        attributes = attributes,
        throwableText = throwableText
    )

    private class RecordingClient : CrashlyticsClient {
        val logs = mutableListOf<String>()
        val keys = linkedMapOf<String, String>()
        val throwables = mutableListOf<Throwable>()

        override fun log(message: String) { logs += message }
        override fun setKey(key: String, value: String) { keys[key] = value }
        override fun recordException(throwable: Throwable) { throwables += throwable }
    }
}
