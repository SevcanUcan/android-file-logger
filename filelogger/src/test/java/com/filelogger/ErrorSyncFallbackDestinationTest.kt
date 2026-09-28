package com.filelogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
        assertEquals(1, asyncDestination.flushCount)
        assertEquals(1, syncDestination.flushCount)
    }

    @Test
    fun `error drains earlier async logs before synchronous write`() {
        val writtenRecords = mutableListOf<LogRecord>()
        val asyncDestination = BufferedFlushableDestination(writtenRecords)
        val syncDestination = DirectFlushableDestination(writtenRecords)
        val destination = ErrorSyncFallbackDestination(
            asyncDestination = asyncDestination,
            syncDestination = syncDestination
        )
        val debug = testRecord(level = LogLevel.DEBUG, message = "debug")
        val warning = testRecord(level = LogLevel.WARN, message = "warning")
        val error = testRecord(level = LogLevel.ERROR, message = "error")

        destination.write(debug)
        destination.write(warning)
        destination.write(error)

        assertEquals(listOf(debug, warning, error), writtenRecords)
    }

    @Test
    fun `concurrent log cannot overtake error while queue is draining`() {
        val writtenRecords = Collections.synchronizedList(mutableListOf<LogRecord>())
        val flushStarted = CountDownLatch(1)
        val releaseFlush = CountDownLatch(1)
        val asyncDestination = BlockingBufferedDestination(
            writtenRecords = writtenRecords,
            flushStarted = flushStarted,
            releaseFlush = releaseFlush
        )
        val syncDestination = DirectFlushableDestination(writtenRecords)
        val destination = ErrorSyncFallbackDestination(
            asyncDestination = asyncDestination,
            syncDestination = syncDestination
        )
        val first = testRecord(level = LogLevel.DEBUG, message = "first")
        val error = testRecord(level = LogLevel.ERROR, message = "error")
        val late = testRecord(level = LogLevel.DEBUG, message = "late")
        val lateWriteCompleted = AtomicBoolean(false)
        destination.write(first)

        val errorWriter = Thread { destination.write(error) }.apply { start() }
        assertTrue(flushStarted.await(1, TimeUnit.SECONDS))
        val lateWriter = Thread {
            destination.write(late)
            lateWriteCompleted.set(true)
        }.apply { start() }

        Thread.sleep(50)
        assertFalse(lateWriteCompleted.get())
        releaseFlush.countDown()
        errorWriter.join(1_000)
        lateWriter.join(1_000)
        destination.flush(timeoutMillis = 1_000)

        assertTrue(lateWriteCompleted.get())
        assertEquals(listOf(first, error, late), writtenRecords)
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

    private class BufferedFlushableDestination(
        private val writtenRecords: MutableList<LogRecord>
    ) : FlushableLogDestination {
        private val pendingRecords = mutableListOf<LogRecord>()

        override fun write(record: LogRecord) {
            pendingRecords += record
        }

        override fun flush(timeoutMillis: Long): Boolean {
            writtenRecords += pendingRecords
            pendingRecords.clear()
            return true
        }
    }

    private class BlockingBufferedDestination(
        private val writtenRecords: MutableList<LogRecord>,
        private val flushStarted: CountDownLatch,
        private val releaseFlush: CountDownLatch
    ) : FlushableLogDestination {
        private val pendingRecords = mutableListOf<LogRecord>()
        private var shouldBlock = true

        override fun write(record: LogRecord) {
            pendingRecords += record
        }

        override fun flush(timeoutMillis: Long): Boolean {
            if (shouldBlock) {
                shouldBlock = false
                flushStarted.countDown()
                if (!releaseFlush.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    return false
                }
            }
            writtenRecords += pendingRecords
            pendingRecords.clear()
            return true
        }
    }

    private class DirectFlushableDestination(
        private val writtenRecords: MutableList<LogRecord>
    ) : FlushableLogDestination {
        override fun write(record: LogRecord) {
            writtenRecords += record
        }

        override fun flush(timeoutMillis: Long): Boolean = true
    }
}
