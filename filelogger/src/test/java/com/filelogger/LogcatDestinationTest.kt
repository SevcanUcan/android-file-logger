package com.filelogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogcatDestinationTest {

    @Test
    fun `logcat destination splits long messages into chunks`() {
        val printer = RecordingLogcatPrinter()
        val destination = LogcatDestination(
            chunkSize = 4,
            printer = printer
        )
        val record = testRecord(
            level = LogLevel.DEBUG,
            message = "abcdefghij"
        )

        destination.write(record)

        assertEquals(
            listOf(
                PrintedLog(LogLevel.DEBUG, "FileLogger", "abcd"),
                PrintedLog(LogLevel.DEBUG, "FileLogger", "efgh"),
                PrintedLog(LogLevel.DEBUG, "FileLogger", "ij")
            ),
            printer.messages
        )
    }

    @Test
    fun `logcat destination appends throwable to rendered message`() {
        val printer = RecordingLogcatPrinter()
        val destination = LogcatDestination(
            chunkSize = 64,
            printer = printer
        )
        val record = testRecord(
            level = LogLevel.ERROR,
            message = "Upload failed",
            throwable = IllegalArgumentException("broken")
        )

        destination.write(record)

        val combinedMessage = printer.messages.joinToString(separator = "") { it.message }

        assertTrue(printer.messages.isNotEmpty())
        assertTrue(combinedMessage.startsWith("Upload failed\n"))
        assertTrue(combinedMessage.contains("IllegalArgumentException"))
    }

    private data class PrintedLog(
        val level: LogLevel,
        val tag: String,
        val message: String
    )

    private class RecordingLogcatPrinter : LogcatPrinter {
        val messages = mutableListOf<PrintedLog>()

        override fun d(tag: String, message: String) {
            messages += PrintedLog(LogLevel.DEBUG, tag, message)
        }

        override fun i(tag: String, message: String) {
            messages += PrintedLog(LogLevel.INFO, tag, message)
        }

        override fun w(tag: String, message: String) {
            messages += PrintedLog(LogLevel.WARN, tag, message)
        }

        override fun e(tag: String, message: String) {
            messages += PrintedLog(LogLevel.ERROR, tag, message)
        }
    }
}
