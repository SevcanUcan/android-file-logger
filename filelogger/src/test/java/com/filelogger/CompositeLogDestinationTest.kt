package com.filelogger

import org.junit.Assert.assertEquals
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

    private class RecordingDestination : LogDestination {
        val records = mutableListOf<LogRecord>()

        override fun write(record: LogRecord) {
            records += record
        }
    }
}
