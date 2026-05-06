package com.filelogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorSyncFallbackDestinationTest {

    @Test
    fun `error logs are written synchronously and flushed`() {
        val asyncDestination = RecordingFlushableDestination()
        val syncDestination = RecordingFlushableDestination()
        val destination = ErrorSyncFallbackDestination(
            asyncDestination = asyncDestination,
            syncDestination = syncDestination
        )
        val error = testRecord(
            level = LogLevel.ERROR,
            message = "crash soon"
        )

        destination.write(error)

        assertEquals(emptyList<LogRecord>(), asyncDestination.records)
        assertEquals(listOf(error), syncDestination.records)
        assertEquals(1, syncDestination.flushCount)
    }

    @Test
    fun `non-error logs stay asynchronous`() {
        val asyncDestination = RecordingFlushableDestination()
        val syncDestination = RecordingFlushableDestination()
        val destination = ErrorSyncFallbackDestination(
            asyncDestination = asyncDestination,
            syncDestination = syncDestination
        )
        val debug = testRecord(
            level = LogLevel.DEBUG,
            message = "background"
        )

        destination.write(debug)

        assertEquals(listOf(debug), asyncDestination.records)
        assertEquals(emptyList<LogRecord>(), syncDestination.records)
        assertEquals(0, syncDestination.flushCount)
    }

    @Test
    fun `flush combines async and sync destinations`() {
        val asyncDestination = RecordingFlushableDestination()
        val syncDestination = RecordingFlushableDestination()
        val destination = ErrorSyncFallbackDestination(
            asyncDestination = asyncDestination,
            syncDestination = syncDestination
        )

        assertTrue(destination.flush(timeoutMillis = 1_000))
        assertEquals(1, asyncDestination.flushCount)
        assertEquals(1, syncDestination.flushCount)
    }

    private class RecordingFlushableDestination : FlushableLogDestination {
        val records = mutableListOf<LogRecord>()
        var flushCount = 0
            private set

        override fun write(record: LogRecord) {
            records += record
        }

        override fun flush(timeoutMillis: Long): Boolean {
            flushCount += 1
            return true
        }
    }
}
