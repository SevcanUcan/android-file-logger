package com.filelogger

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

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
}
