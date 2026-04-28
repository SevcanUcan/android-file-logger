package com.filelogger

import org.junit.Assert.assertTrue
import org.junit.Test

class PlainTextLogFormatterTest {

    @Test
    fun `plain text formatter renders readable line`() {
        val formatter = PlainTextLogFormatter()

        val line = formatter.format(
            testRecord(
                timestampMillis = 123L,
                level = LogLevel.WARN,
                tag = "Sync",
                message = "Retry scheduled",
                threadName = "worker-1",
                processName = "com.test.app"
            )
        )

        assertTrue(line.contains("W/Sync"))
        assertTrue(line.contains("[thread=worker-1, process=com.test.app]"))
        assertTrue(line.contains("Retry scheduled"))
        assertTrue(line.endsWith("\n"))
    }

    @Test
    fun `plain text formatter appends throwable when present`() {
        val formatter = PlainTextLogFormatter()

        val line = formatter.format(
            testRecord(
                message = "Upload failed",
                throwable = IllegalArgumentException("broken")
            )
        )

        assertTrue(line.startsWith("1970-01-01"))
        assertTrue(line.contains("Upload failed\n"))
        assertTrue(line.contains("IllegalArgumentException"))
    }
}
