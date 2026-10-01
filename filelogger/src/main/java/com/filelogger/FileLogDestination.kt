package com.filelogger

import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

internal class FileLogDestination(
    private val fileProvider: () -> File,
    private val formatter: LogFormatter,
    private val rotationPolicy: FileRotationPolicy? = null,
    private val fileSystem: LogFileSystem = SystemLogFileSystem
) : FlushableLogDestination, DiagnosticLogDestination {

    private val fileLock = Any()
    private var lastWrittenFile: File? = null
    private val writeFailures = AtomicLong(0)
    private val flushFailures = AtomicLong(0)
    private val writeCount = AtomicLong(0)
    private val totalWriteDurationNanos = AtomicLong(0)

    override fun write(record: LogRecord) {
        val startedAt = System.nanoTime()
        synchronized(fileLock) {
            val file = fileProvider().apply {
                parentFile?.let { directory ->
                    if (!fileSystem.createDirectories(directory)) {
                        throw IOException("Failed to create log directory: ${directory.absolutePath}")
                    }
                }
            }
            val encodedLine = formatter.format(record).toByteArray()

            try {
                rotationPolicy?.rotateIfNeeded(file, encodedLine)
                fileSystem.append(file, encodedLine)
                lastWrittenFile = file
                writeCount.incrementAndGet()
            } catch (e: Exception) {
                writeFailures.incrementAndGet()
                reportFailure("write failed", e)
            }
        }
        totalWriteDurationNanos.addAndGet(System.nanoTime() - startedAt)
    }

    override fun flush(timeoutMillis: Long): Boolean {
        synchronized(fileLock) {
            val file = lastWrittenFile ?: return true
            if (!fileSystem.exists(file)) {
                return true
            }

            return try {
                fileSystem.sync(file)
                true
            } catch (e: Exception) {
                flushFailures.incrementAndGet()
                reportFailure("flush failed", e)
                false
            }
        }
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
}
