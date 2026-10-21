package com.filelogger

import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

internal class FileLogDestination(
    private val fileProvider: () -> File,
    private val formatter: LogFormatter,
    private val rotationPolicy: FileRotationPolicy? = null,
    private val fileSystem: LogFileSystem = SystemLogFileSystem,
    private val storageCoordinator: LogStorageCoordinator? = null,
    private val closeAction: (() -> Unit)? = null
) : CloseableLogDestination, DiagnosticLogDestination {

    private val fileLock = Any()
    private var lastWrittenFile: File? = null
    private var writeFailureGeneration: Long = 0
    private var reportedWriteFailureGeneration: Long = 0
    private val writeFailures = AtomicLong(0)
    private val flushFailures = AtomicLong(0)
    private val writeCount = AtomicLong(0)
    private val totalWriteDurationNanos = AtomicLong(0)

    override fun write(record: LogRecord) {
        val startedAt = System.nanoTime()
        synchronized(fileLock) {
            try {
                withStorageAccess {
                    val file = fileProvider().apply {
                        parentFile?.let { directory ->
                            if (!fileSystem.createDirectories(directory)) {
                                throw IOException("Failed to create log directory: ${directory.absolutePath}")
                            }
                        }
                    }
                    val encodedLine = formatter.format(record).toByteArray()

                    rotationPolicy?.rotateIfNeeded(file, encodedLine)
                    fileSystem.append(file, encodedLine)
                    lastWrittenFile = file
                    writeCount.incrementAndGet()
                    rotationPolicy?.enforceRetention(file)
                }
            } catch (e: Exception) {
                writeFailureGeneration += 1
                writeFailures.incrementAndGet()
                reportFailure("write failed", e)
            }
        }
        totalWriteDurationNanos.addAndGet(System.nanoTime() - startedAt)
    }

    override fun flush(timeoutMillis: Long): Boolean {
        synchronized(fileLock) {
            val barrierFailureGeneration = writeFailureGeneration
            val syncSucceeded = try {
                withStorageAccess {
                    val file = lastWrittenFile ?: return@withStorageAccess true
                    if (!fileSystem.exists(file)) {
                        return@withStorageAccess true
                    }
                    fileSystem.sync(file)
                    true
                }
            } catch (e: Exception) {
                flushFailures.incrementAndGet()
                reportFailure("flush failed", e)
                false
            }
            val writesFailedInBarrier = barrierFailureGeneration > reportedWriteFailureGeneration
            reportedWriteFailureGeneration = barrierFailureGeneration
            return syncSucceeded && !writesFailedInBarrier
        }
    }

    override fun close(timeoutMillis: Long): Boolean {
        val flushed = flush(timeoutMillis)
        val closed = runCatching { closeAction?.invoke() }.isSuccess
        return flushed && closed
    }

    override fun diagnostics(): LogDiagnostics = LogDiagnostics(
        fileWriteFailures = writeFailures.get(),
        fileFlushFailures = flushFailures.get(),
        fileRotations = rotationPolicy?.rotationCount() ?: 0,
        fileWriteCount = writeCount.get(),
        totalFileWriteDurationNanos = totalWriteDurationNanos.get()
    )

    private fun reportFailure(message: String, error: Exception) {
        runCatching { Log.e("FileLogger", message, error) }
    }

    private fun <T> withStorageAccess(block: () -> T): T =
        storageCoordinator?.withExclusiveAccess(block) ?: block()
}
