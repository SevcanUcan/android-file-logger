package com.example.filelogger

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.filelogger.AsyncOverflowStrategy
import com.filelogger.FileLogger
import com.filelogger.LogLevel
import com.filelogger.LoggerConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@LargeTest
@RunWith(AndroidJUnit4::class)
class FileLoggerSoakInstrumentationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        runCatching { FileLogger.shutdown() }
    }

    @Test
    fun sustainedConcurrentLoadStaysBoundedAndPersistsFinalError() {
        val folder = "soak_${System.nanoTime()}"
        val config = soakConfig(folder)
        val workerCount = 6
        val recordsPerWorker = 2_000
        val attemptedDebugRecords = workerCount * recordsPerWorker
        val failures = ConcurrentLinkedQueue<Throwable>()
        val completed = CountDownLatch(workerCount)
        val executor = Executors.newFixedThreadPool(workerCount)

        FileLogger.init(context, config)
        FileLogger.deleteAllLogs()
        val baseline = FileLogger.diagnostics()

        repeat(workerCount) { worker ->
            executor.execute {
                try {
                    repeat(recordsPerWorker) { index ->
                        FileLogger.d(
                            "Soak-$worker",
                            "record=$index payload=${"x".repeat(96)}"
                        )
                    }
                } catch (error: Throwable) {
                    failures += error
                } finally {
                    completed.countDown()
                }
            }
        }

        assertTrue("soak workers timed out", completed.await(30, TimeUnit.SECONDS))
        executor.shutdown()
        assertTrue("soak executor did not stop", executor.awaitTermination(10, TimeUnit.SECONDS))
        assertTrue("logging workers failed: $failures", failures.isEmpty())

        FileLogger.e("Soak", "final-durable-error")
        assertTrue("flush timed out", FileLogger.flush(15_000))

        val diagnostics = FileLogger.diagnostics()
        val totalRecords = diagnostics.totalRecords - baseline.totalRecords
        val debugRecords = diagnostics.debugRecords - baseline.debugRecords
        val errorRecords = diagnostics.errorRecords - baseline.errorRecords
        val fileWriteCount = diagnostics.fileWriteCount - baseline.fileWriteCount
        val droppedAsyncRecords = diagnostics.droppedAsyncRecords - baseline.droppedAsyncRecords

        assertEquals(attemptedDebugRecords.toLong() + 1, totalRecords)
        assertEquals(attemptedDebugRecords.toLong(), debugRecords)
        assertEquals(1, errorRecords)
        assertEquals(0, diagnostics.queuedAsyncRecords)
        assertEquals(config.asyncQueueCapacity, diagnostics.asyncQueueCapacity)
        assertEquals(0, diagnostics.fileWriteFailures)
        assertEquals(0, diagnostics.fileFlushFailures)
        assertTrue("rotation did not occur", diagnostics.fileRotations > 0)
        assertEquals(
            totalRecords,
            fileWriteCount + droppedAsyncRecords
        )

        val directory = File(context.filesDir, folder)
        val files = directory.listFiles().orEmpty().filter(File::isFile)
        assertTrue(files.isNotEmpty())
        assertTrue(files.size <= config.maxBackupFiles + 1)
        assertTrue(files.sumOf(File::length) <= config.maxTotalLogSize + config.maxFileSize)
        assertTrue(File(directory, config.logFileName).readText().contains("final-durable-error"))
    }

    @Test
    fun repeatedReinitializationLeavesOneWorkerAndKeepsLatestSessionWritable() {
        val folder = "lifecycle_soak_${System.nanoTime()}"
        val cycles = 20

        repeat(cycles) { cycle ->
            FileLogger.init(context, soakConfig(folder))
            repeat(250) { index -> FileLogger.i("Cycle-$cycle", "record-$index") }
            FileLogger.e("Cycle-$cycle", "cycle-$cycle-final")
            assertTrue("flush failed at cycle $cycle", FileLogger.flush(10_000))
        }

        val workers = Thread.getAllStackTraces().keys.count { thread ->
            thread.isAlive && thread.name == "FileLogger-Worker"
        }
        assertEquals(1, workers)
        assertTrue(
            File(context.filesDir, "$folder/soak.log")
                .readText()
                .contains("cycle-${cycles - 1}-final")
        )
    }

    private fun soakConfig(folder: String): LoggerConfig = LoggerConfig.dev().copy(
        minimumLogLevel = LogLevel.DEBUG,
        logFolder = folder,
        logFileName = "soak.log",
        maxFileSize = 16 * 1024,
        maxBackupFiles = 3,
        maxTotalLogSize = 64 * 1024,
        asyncQueueCapacity = 64,
        asyncOverflowStrategy = AsyncOverflowStrategy.DROP_OLDEST,
        useProcessSpecificLogFiles = false,
        autoFlushOnAppBackground = false,
        crashCaptureEnabled = false,
        sessionLoggingEnabled = false
    )
}
