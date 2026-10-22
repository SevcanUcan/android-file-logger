package com.filelogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactingLogDestinationTest {

    @Test
    fun `redacts message attributes and throwable before delegate`() {
        val delegate = RecordingDestination()
        val destination = RedactingLogDestination(delegate, LogRedactor.DEFAULT_SENSITIVE)

        destination.write(
            testRecord(
                message = "email=user@example.com token=abc123",
                throwable = IllegalStateException("password=hunter2"),
                attributes = mapOf("authorization" to "Bearer secret-token")
            )
        )

        val record = delegate.record
        assertFalse(record.message.contains("user@example.com"))
        assertFalse(record.message.contains("abc123"))
        assertFalse(record.attributes.getValue("authorization").contains("secret-token"))
        assertNull(record.throwable)
        assertFalse(record.throwableText.orEmpty().contains("hunter2"))
        assertTrue(record.throwableText.orEmpty().contains("IllegalStateException"))
    }

    @Test
    fun `preserves flush close and diagnostics contracts`() {
        val delegate = RecordingDestination()
        val destination = RedactingLogDestination(delegate, LogRedactor.NONE)

        assertTrue(destination.flush(10))
        assertTrue(destination.close(10))
        assertTrue(destination.erasePendingData(10))
        assertEquals(7, destination.diagnostics().totalRecords)
        assertEquals(1, delegate.flushCalls)
        assertEquals(1, delegate.closeCalls)
        assertEquals(1, delegate.eraseCalls)
    }

    @Test
    fun `redacts complete values for sensitive attribute keys`() {
        val delegate = RecordingDestination()
        val destination = RedactingLogDestination(delegate, LogRedactor.DEFAULT_SENSITIVE)

        destination.write(
            testRecord(
                attributes = mapOf(
                    "password" to "opaque-value-42",
                    "access_token" to "another-opaque-value",
                    "screen" to "checkout"
                )
            )
        )

        assertEquals("[REDACTED]", delegate.record.attributes.getValue("password"))
        assertEquals("[REDACTED]", delegate.record.attributes.getValue("access_token"))
        assertEquals("checkout", delegate.record.attributes.getValue("screen"))
    }

    @Test
    fun `chaining the default redactor cannot bypass sensitive attribute key redaction`() {
        val delegate = RecordingDestination()
        val chained = LogRedactor.chain(
            LogRedactor.DEFAULT_SENSITIVE,
            LogRedactor { value -> value }
        )
        val destination = RedactingLogDestination(delegate, chained)

        destination.write(
            testRecord(attributes = mapOf("password" to "opaque-value-without-a-pattern"))
        )

        assertEquals("[REDACTED]", delegate.record.attributes.getValue("password"))
    }

    private class RecordingDestination :
        CloseableLogDestination,
        DiagnosticLogDestination,
        ErasableLogDestination {
        lateinit var record: LogRecord
        var flushCalls = 0
        var closeCalls = 0
        var eraseCalls = 0

        override fun write(record: LogRecord) {
            this.record = record
        }

        override fun flush(timeoutMillis: Long): Boolean {
            flushCalls += 1
            return true
        }

        override fun close(timeoutMillis: Long): Boolean {
            closeCalls += 1
            return true
        }

        override fun diagnostics(): LogDiagnostics = LogDiagnostics(totalRecords = 7)

        override fun erasePendingData(timeoutMillis: Long): Boolean {
            eraseCalls += 1
            return true
        }
    }
}
