package com.filelogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class FileLogDestinationTest {

    @Test
    fun `file destination writes formatter output`() {
        val logFile = File.createTempFile("filelogger", ".log")
        val destination = FileLogDestination(
            fileProvider = { logFile },
            formatter = object : LogFormatter {
                override fun format(record: LogRecord): String {
                    return "${record.level.shortName}|${record.tag}|${record.message}\n"
                }
            }
        )
        val record = testRecord(
            level = LogLevel.ERROR,
            message = "Upload failed"
        )

        destination.write(record)

        assertEquals("E|FileLogger|Upload failed\n", logFile.readText())
    }

    @Test
    fun `file destination rotates when max size is exceeded`() {
        val directory = createTempDirectory("filelogger-destination").toFile()
        val logFile = File(directory, "app.log")
        val destination = FileLogDestination(
            fileProvider = { logFile },
            formatter = object : LogFormatter {
                override fun format(record: LogRecord): String {
                    return record.message + "\n"
                }
            },
            rotationPolicy = FileRotationPolicy(
                maxFileSize = 10,
                maxBackupFiles = 2
            )
        )

        destination.write(testRecord(message = "123456"))
        destination.write(testRecord(message = "abcdef"))

        assertEquals("abcdef\n", logFile.readText())
        assertEquals("123456\n", File(directory, "app.log.1").readText())
        assertFalse(File(directory, "app.log.2").exists())
    }

    @Test
    fun `file destination flush succeeds after a write`() {
        val logFile = File.createTempFile("filelogger", ".log")
        val destination = FileLogDestination(
            fileProvider = { logFile },
            formatter = object : LogFormatter {
                override fun format(record: LogRecord): String {
                    return record.message + "\n"
                }
            }
        )

        destination.write(testRecord(message = "flush-me"))

        assertTrue(destination.flush(timeoutMillis = 1_000))
        assertEquals("flush-me\n", logFile.readText())
    }

    @Test
    fun `file destination flush succeeds before any write`() {
        val logFile = File.createTempFile("filelogger", ".log")
        val destination = FileLogDestination(
            fileProvider = { logFile },
            formatter = PlainTextLogFormatter()
        )

        assertTrue(destination.flush(timeoutMillis = 1_000))
    }
}
