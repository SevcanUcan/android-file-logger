package com.filelogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositeLogDestinationTest {

    @Test
    fun `composite forwards record to all destinations`() {
        val first = RecordingDestination()
        val second = RecordingDestination()
        val composite = CompositeLogDestination(listOf(first, second))
        val record = testRecord()

        composite.write(record)

        assertEquals(listOf(record), first.records)
        assertEquals(listOf(record), second.records)
    }

    @Test
    fun `composite flush delegates to flushable destinations`() {
        val flushable = RecordingFlushableDestination()
        val composite = CompositeLogDestination(
            listOf(
                RecordingDestination(),
                flushable
            )
        )

        assertTrue(composite.flush(timeoutMillis = 100))
        assertEquals(1, flushable.flushCallCount)
    }

    @Test
    fun `composite combines destination diagnostics`() {
        val composite = CompositeLogDestination(
            listOf(
                RecordingDiagnosticDestination(LogDiagnostics(droppedAsyncRecords = 2)),
                RecordingDiagnosticDestination(LogDiagnostics(queuedAsyncRecords = 3))
            )
        )

        val diagnostics = composite.diagnostics()

        assertEquals(2L, diagnostics.droppedAsyncRecords)
        assertEquals(3, diagnostics.queuedAsyncRecords)
    }

    @Test
    fun `composite isolates write failures`() {
        val failing = FailingDestination()
        val recording = RecordingDestination()
        val composite = CompositeLogDestination(listOf(failing, recording))
        val record = testRecord()

        composite.write(record)

        assertEquals(listOf(record), recording.records)
    }

    @Test
    fun `composite reports flush failure without throwing`() {
        val composite = CompositeLogDestination(listOf(FailingFlushableDestination()))

        val flushed = composite.flush(timeoutMillis = 100)

        assertEquals(false, flushed)
    }

    private class RecordingDestination : LogDestination {
        val records = mutableListOf<LogRecord>()

        override fun write(record: LogRecord) {
            records += record
        }
    }

    private class RecordingFlushableDestination : FlushableLogDestination {
        var flushCallCount: Int = 0

        override fun write(record: LogRecord) = Unit

        override fun flush(timeoutMillis: Long): Boolean {
            flushCallCount += 1
            return true
        }
    }

    private class RecordingDiagnosticDestination(
        private val diagnostics: LogDiagnostics
    ) : LogDestination, DiagnosticLogDestination {
        override fun write(record: LogRecord) = Unit

        override fun diagnostics(): LogDiagnostics = diagnostics
    }

    private class FailingDestination : LogDestination {
        override fun write(record: LogRecord) {
            throw IllegalStateException("boom")
        }
    }

    private class FailingFlushableDestination : FlushableLogDestination {
        override fun write(record: LogRecord) = Unit

        override fun flush(timeoutMillis: Long): Boolean {
            throw IllegalStateException("boom")
        }
    }
}
