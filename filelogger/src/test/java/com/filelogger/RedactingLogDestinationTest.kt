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
