package com.filelogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AsyncLogDestinationTest {

    @Test
    fun `flush waits until queued logs are delivered`() {
        val delegate = RecordingDestination()
        val destination = AsyncLogDestination(
            delegate = delegate,
            workerName = "AsyncLogDestinationTest"
        )
        val first = testRecord(message = "first")
        val second = testRecord(message = "second")

        destination.write(first)
        destination.write(second)

        assertTrue(destination.flush(timeoutMillis = 1_000))
        assertEquals(listOf(first, second), delegate.records)
    }

    @Test
    fun `flush succeeds immediately when nothing was written`() {
        val destination = AsyncLogDestination(
            delegate = RecordingDestination(),
            workerName = "AsyncLogDestinationTest"
        )

        assertTrue(destination.flush(timeoutMillis = 100))
    }

    private class RecordingDestination : LogDestination {
        val records = mutableListOf<LogRecord>()

        override fun write(record: LogRecord) {
            records += record
        }
    }
}
