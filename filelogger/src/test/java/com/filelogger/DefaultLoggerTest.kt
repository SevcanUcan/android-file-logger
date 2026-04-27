package com.filelogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DefaultLoggerTest {

    @Test
    fun `debug log creates structured record`() {
        val writer = RecordingLogWriter()
        val logger = DefaultLogger(
            logWriter = writer,
            currentTimeMillis = { 42L },
            threadNameProvider = { "worker-1" },
            processNameProvider = { "com.test.app" }
        )

        logger.d("Startup", "Logger is ready")

        val record = writer.singleRecord()
        assertEquals(42L, record.timestampMillis)
        assertEquals(LogLevel.DEBUG, record.level)
        assertEquals("Startup", record.tag)
        assertEquals("Logger is ready", record.message)
        assertEquals(null, record.throwable)
        assertEquals("worker-1", record.threadName)
        assertEquals("com.test.app", record.processName)
    }

    @Test
    fun `error log keeps throwable separate from message`() {
        val writer = RecordingLogWriter()
        val logger = DefaultLogger(
            logWriter = writer,
            processNameProvider = { "com.test.app" }
        )
        val throwable = IllegalArgumentException("broken")

        logger.e("Sync", "Upload failed", throwable)

        val record = writer.singleRecord()
        assertEquals(LogLevel.ERROR, record.level)
        assertEquals("Upload failed", record.message)
        assertSame(throwable, record.throwable)
    }

    private class RecordingLogWriter : LogWriter {
        private val records = mutableListOf<LogRecord>()

        override fun write(record: LogRecord) {
            records += record
        }

        fun singleRecord(): LogRecord = records.single()
    }
}
