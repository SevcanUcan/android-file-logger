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
}
