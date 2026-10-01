package com.filelogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory

class FaultInjectionTest {

    @Test
    fun `disk full write is isolated and reported in diagnostics`() {
        val file = File(createTempDirectory("filelogger-fault").toFile(), "app.log")
        val destination = FileLogDestination(
            fileProvider = { file },
            formatter = PlainTextLogFormatter(),
            fileSystem = FakeLogFileSystem(appendFailure = IOException("disk full"))
        )

        destination.write(testRecord())

        assertEquals(1, destination.diagnostics().fileWriteFailures)
        assertFalse(file.exists())
    }

    @Test
    fun `sync failure returns false and is reported`() {
        val file = File(createTempDirectory("filelogger-fault").toFile(), "app.log")
        val fileSystem = FakeLogFileSystem(syncFailure = IOException("I/O stopped"))
        val destination = FileLogDestination(
            fileProvider = { file },
            formatter = PlainTextLogFormatter(),
            fileSystem = fileSystem
        )
        destination.write(testRecord())

        assertFalse(destination.flush(100))
        assertEquals(1, destination.diagnostics().fileFlushFailures)
    }

    @Test(expected = IOException::class)
    fun `rename failure aborts rotation instead of deleting active log`() {
        val directory = createTempDirectory("filelogger-fault").toFile()
        val file = File(directory, "app.log").apply { writeText("current") }
        val policy = FileRotationPolicy(
            maxFileSize = 1,
            maxBackupFiles = 1,
            fileSystem = FakeLogFileSystem(renameResult = false)
        )

        try {
            policy.rotateIfNeeded(file, ByteArray(2))
        } finally {
            assertEquals("current", file.readText())
        }
    }

    @Test(expected = IOException::class)
    fun `delete failure is surfaced during retention`() {
        val directory = createTempDirectory("filelogger-fault").toFile()
        val file = File(directory, "app.log").apply { writeText("current") }
        File(directory, "app.log.1").apply {
            writeText("expired")
            setLastModified(1)
        }
        val policy = FileRotationPolicy(
            maxFileSize = 100,
            maxBackupFiles = 1,
            maxLogAgeMillis = 1,
            currentTimeMillis = { 10 },
            fileSystem = FakeLogFileSystem(deleteResult = false)
        )

        policy.rotateIfNeeded(file, ByteArray(1))
    }

    private class FakeLogFileSystem(
        private val appendFailure: IOException? = null,
        private val syncFailure: IOException? = null,
        private val deleteResult: Boolean = true,
        private val renameResult: Boolean = true
    ) : LogFileSystem by SystemLogFileSystem {
        override fun append(file: File, bytes: ByteArray) {
            appendFailure?.let { throw it }
            SystemLogFileSystem.append(file, bytes)
        }

        override fun sync(file: File) {
            syncFailure?.let { throw it }
            SystemLogFileSystem.sync(file)
        }

        override fun delete(file: File): Boolean {
            return if (deleteResult) SystemLogFileSystem.delete(file) else false
        }

        override fun rename(source: File, target: File): Boolean {
            return if (renameResult) SystemLogFileSystem.rename(source, target) else false
        }
    }
}
