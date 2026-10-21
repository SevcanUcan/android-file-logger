package com.filelogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory

class FlushCorrectnessRegressionTest {

    @Test
    fun `disk write failure is reported by the following flush`() {
        val destination = fileDestination(FailingAppendFileSystem(failOnWrites = setOf(1)))

        destination.write(testRecord(message = "lost"))

        assertFalse("flush must not report success for a failed accepted write", destination.flush(1_000))
        assertEquals(1, destination.diagnostics().fileWriteFailures)
    }

    @Test
    fun `one failed write makes its flush barrier fail`() {
        val destination = fileDestination(FailingAppendFileSystem(failOnWrites = setOf(2)))

        destination.write(testRecord(message = "written"))
        destination.write(testRecord(message = "failed"))
        destination.write(testRecord(message = "written-after-failure"))

        assertFalse("A successful later write must not hide an earlier write failure", destination.flush(1_000))
        assertEquals(1, destination.diagnostics().fileWriteFailures)
    }

    @Test
    fun `a later flush can succeed after the failed interval is acknowledged and storage recovers`() {
        val fileSystem = FailingAppendFileSystem(failOnWrites = setOf(1))
        val destination = fileDestination(fileSystem)

        destination.write(testRecord(message = "failed"))
        val failedInterval = destination.flush(1_000)
        destination.write(testRecord(message = "recovered"))
        val recoveredInterval = destination.flush(1_000)

        assertEquals(listOf(false, true), listOf(failedInterval, recoveredInterval))
    }

    @Test
    fun `flush timeout remains bounded while a prior write is blocked`() {
        val delegate = BlockingDestination()
        val destination = AsyncLogDestination(delegate, workerName = "flush-timeout")
        destination.write(testRecord(message = "blocked"))
        assertTrue(delegate.started.await(1, TimeUnit.SECONDS))

        val startedAt = System.nanoTime()
        assertFalse(destination.flush(25))
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertTrue("flush timeout must remain bounded, elapsed=$elapsedMillis", elapsedMillis < 1_000)
        delegate.release.countDown()
        destination.close(2_000)
    }

    @Test
    fun `flush barrier includes every write accepted before it`() {
        val delegate = RecordingDestination()
        val destination = AsyncLogDestination(delegate, workerName = "flush-barrier", queueCapacity = 2_048)
        val writers = 8
        val writesPerThread = 100
        val ready = CountDownLatch(writers)
        val start = CountDownLatch(1)
        val done = CountDownLatch(writers)

        repeat(writers) { worker ->
            Thread {
                ready.countDown()
                start.await()
                repeat(writesPerThread) { index ->
                    destination.write(testRecord(message = "$worker-$index"))
                }
                done.countDown()
            }.start()
        }
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))

        assertTrue(destination.flush(5_000))
        assertEquals(writers * writesPerThread, delegate.records.size)
        destination.close(2_000)
    }

    @Test
    fun `worker termination while flushing cannot report success`() {
        val destination = AsyncLogDestination(
            delegate = object : LogDestination {
                override fun write(record: LogRecord) {
                    throw IOException("writer terminated")
                }
            },
            workerName = "terminated-writer"
        )

        destination.write(testRecord(message = "terminate"))

        assertFalse(destination.flush(1_000))
        assertFalse(destination.close(1_000))
    }

    @Test
    fun `error ordering flush does not hide an earlier asynchronous write failure`() {
        val syncDestination = fileDestination(FailingAppendFileSystem(failOnWrites = setOf(1)))
        val asyncDestination = AsyncLogDiagnosticsDestination(
            AsyncLogDestination(syncDestination, workerName = "error-failure-barrier")
        )
        val destination = ErrorSyncFallbackDestination(asyncDestination, syncDestination)

        destination.write(testRecord(level = LogLevel.DEBUG, message = "fails-before-error"))
        destination.write(testRecord(level = LogLevel.ERROR, message = "error-is-written"))

        assertFalse(
            "The internal ordering flush must not consume a failure before the caller observes it",
            destination.flush(2_000)
        )
        destination.close(2_000)
    }

    @Test
    fun `ordering failure is acknowledged by exactly one external flush`() {
        val syncDestination = fileDestination(FailingAppendFileSystem(failOnWrites = setOf(1)))
        val asyncDestination = AsyncLogDiagnosticsDestination(
            AsyncLogDestination(syncDestination, workerName = "error-failure-ack")
        )
        val destination = ErrorSyncFallbackDestination(asyncDestination, syncDestination)

        destination.write(testRecord(level = LogLevel.DEBUG, message = "fails-before-error"))
        destination.write(testRecord(level = LogLevel.ERROR, message = "error-is-written"))

        assertFalse(destination.flush(2_000))
        assertTrue("An acknowledged ordering failure must not poison later barriers", destination.flush(2_000))
        destination.close(2_000)
    }

    @Test
    fun `error logging does not block behind a held storage lock`() {
        val root = createTempDirectory("filelogger-error-lock-latency").toFile()
        val coordinator = LogStorageCoordinator(root)
        val syncDestination = FileLogDestination(
            fileProvider = { File(root, "app.log") },
            formatter = PlainTextLogFormatter(),
            storageCoordinator = coordinator
        )
        val asyncDestination = AsyncLogDiagnosticsDestination(
            AsyncLogDestination(syncDestination, workerName = "error-main-thread-latency")
        )
        val destination = ErrorSyncFallbackDestination(
            asyncDestination,
            syncDestination,
            useNonBlockingErrorWrite = { true }
        )
        val lockAcquired = CountDownLatch(1)
        val releaseLock = CountDownLatch(1)
        val holder = Thread {
            coordinator.withExclusiveAccess {
                lockAcquired.countDown()
                releaseLock.await(2, TimeUnit.SECONDS)
            }
        }.apply { start() }
        assertTrue(lockAcquired.await(1, TimeUnit.SECONDS))
        Thread {
            Thread.sleep(750)
            releaseLock.countDown()
        }.start()

        val startedAt = System.nanoTime()
        destination.write(testRecord(level = LogLevel.ERROR, message = "must-not-block-caller"))
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        holder.join(2_000)
        assertTrue(
            "ERROR logging blocked the caller for ${elapsedMillis}ms while storage was locked",
            elapsedMillis < 250
        )
        destination.close(2_000)
    }

    private fun fileDestination(fileSystem: LogFileSystem): FileLogDestination {
        val file = File(createTempDirectory("filelogger-flush").toFile(), "app.log")
        return FileLogDestination(
            fileProvider = { file },
            formatter = PlainTextLogFormatter(),
            fileSystem = fileSystem
        )
    }

    private class FailingAppendFileSystem(
        private val failOnWrites: Set<Int>
    ) : LogFileSystem by SystemLogFileSystem {
        private val writes = AtomicInteger()

        override fun append(file: File, bytes: ByteArray) {
            val writeNumber = writes.incrementAndGet()
            if (writeNumber in failOnWrites) throw IOException("injected append failure $writeNumber")
            SystemLogFileSystem.append(file, bytes)
        }
    }

    private class BlockingDestination : LogDestination {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun write(record: LogRecord) {
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
    }

    private class RecordingDestination : LogDestination {
        val records = Collections.synchronizedList(mutableListOf<LogRecord>())
        override fun write(record: LogRecord) {
            records += record
        }
    }
}
