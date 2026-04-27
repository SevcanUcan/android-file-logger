package com.filelogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LogRecordTest {

    @Test
    fun `record keeps all structured fields`() {
        val throwable = IllegalStateException("boom")

        val record = LogRecord(
            timestampMillis = 123L,
            level = LogLevel.ERROR,
            tag = "SyncWorker",
            message = "Upload failed",
            throwable = throwable,
            threadName = "main",
            processName = "com.example.app"
        )

        assertEquals(123L, record.timestampMillis)
        assertEquals(LogLevel.ERROR, record.level)
        assertEquals("SyncWorker", record.tag)
        assertEquals("Upload failed", record.message)
        assertSame(throwable, record.throwable)
        assertEquals("main", record.threadName)
        assertEquals("com.example.app", record.processName)
    }
}
