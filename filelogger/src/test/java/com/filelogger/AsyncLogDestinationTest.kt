package com.filelogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

    @Test
    fun `flush waits for delegate flush after queued logs are delivered`() {
        val delegate = RecordingFlushableDestination(flushResult = true)
        val destination = AsyncLogDestination(
            delegate = delegate,
            workerName = "AsyncLogDestinationTest"
        )
        val record = testRecord(message = "durable")

        destination.write(record)

        assertTrue(destination.flush(timeoutMillis = 1_000))
        assertEquals(listOf(record), delegate.records)
        assertEquals(1, delegate.flushCount)
    }

    @Test
    fun `flush fails when delegate flush fails`() {
        val delegate = RecordingFlushableDestination(flushResult = false)
        val destination = AsyncLogDestination(
            delegate = delegate,
            workerName = "AsyncLogDestinationTest"
        )

        destination.write(testRecord(message = "not-durable"))

        assertFalse(destination.flush(timeoutMillis = 1_000))
        assertEquals(1, delegate.flushCount)
    }

    @Test
    fun `drop oldest removes oldest queued record when queue is full`() {
        val delegate = BlockingDestination()
        val destination = AsyncLogDestination(
            delegate = delegate,
            workerName = "AsyncLogDestinationTest",
            queueCapacity = 2,
            overflowStrategy = AsyncOverflowStrategy.DROP_OLDEST
        )
        val first = testRecord(message = "first")
        val second = testRecord(message = "second")
        val third = testRecord(message = "third")
        val fourth = testRecord(message = "fourth")

        destination.write(first)
        assertTrue(delegate.awaitFirstWrite())
        destination.write(second)
        destination.write(third)
        destination.write(fourth)

        delegate.release()

        assertTrue(destination.flush(timeoutMillis = 1_000))
        assertEquals(listOf(first, third, fourth), delegate.records)
        assertEquals(1L, destination.droppedRecords())
    }

    @Test
    fun `drop newest drops incoming record when queue is full`() {
        val delegate = BlockingDestination()
        val destination = AsyncLogDestination(
            delegate = delegate,
            workerName = "AsyncLogDestinationTest",
            queueCapacity = 2,
            overflowStrategy = AsyncOverflowStrategy.DROP_NEWEST
        )
        val first = testRecord(message = "first")
        val second = testRecord(message = "second")
        val third = testRecord(message = "third")
        val fourth = testRecord(message = "fourth")

        destination.write(first)
        assertTrue(delegate.awaitFirstWrite())
        destination.write(second)
        destination.write(third)
        destination.write(fourth)

        delegate.release()

        assertTrue(destination.flush(timeoutMillis = 1_000))
        assertEquals(listOf(first, second, third), delegate.records)
        assertEquals(1L, destination.droppedRecords())
    }

    @Test
    fun `block waits for queue space instead of dropping records`() {
        val delegate = BlockingDestination()
        val destination = AsyncLogDestination(
            delegate = delegate,
            workerName = "AsyncLogDestinationTest",
            queueCapacity = 1,
            overflowStrategy = AsyncOverflowStrategy.BLOCK
        )
        val first = testRecord(message = "first")
        val second = testRecord(message = "second")
        val third = testRecord(message = "third")
        val thirdWriteCompleted = AtomicBoolean(false)

        destination.write(first)
        assertTrue(delegate.awaitFirstWrite())
        destination.write(second)

        val blockedWriter = Thread {
            destination.write(third)
            thirdWriteCompleted.set(true)
        }.apply {
            name = "AsyncLogDestinationBlockingWriter"
            start()
        }

        Thread.sleep(50)
        assertFalse(thirdWriteCompleted.get())

        delegate.release()
        blockedWriter.join(1_000)

        assertTrue(thirdWriteCompleted.get())
        assertTrue(destination.flush(timeoutMillis = 1_000))
        assertEquals(listOf(first, second, third), delegate.records)
        assertEquals(0L, destination.droppedRecords())
    }

    private class RecordingDestination : LogDestination {
        val records = Collections.synchronizedList(mutableListOf<LogRecord>())

        override fun write(record: LogRecord) {
            records += record
        }
    }

    private class BlockingDestination : LogDestination {
        val records = Collections.synchronizedList(mutableListOf<LogRecord>())
        private val firstWriteStarted = CountDownLatch(1)
        private val releaseFirstWrite = CountDownLatch(1)

        override fun write(record: LogRecord) {
            records += record
            if (records.size == 1) {
                firstWriteStarted.countDown()
                releaseFirstWrite.await(1, TimeUnit.SECONDS)
            }
        }

        fun awaitFirstWrite(): Boolean {
            return firstWriteStarted.await(1, TimeUnit.SECONDS)
        }

        fun release() {
            releaseFirstWrite.countDown()
        }
    }

    private class RecordingFlushableDestination(
        private val flushResult: Boolean
    ) : FlushableLogDestination {
        val records = Collections.synchronizedList(mutableListOf<LogRecord>())
        var flushCount = 0
            private set

        override fun write(record: LogRecord) {
            records += record
        }

        override fun flush(timeoutMillis: Long): Boolean {
            flushCount += 1
            return flushResult
        }
    }
}
