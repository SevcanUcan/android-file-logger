package com.filelogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DefaultLoggerTest {

    @Test
    fun `debug log creates structured record`() {
        val destination = RecordingDestination()
        val logger = DefaultLogger(
            destination = destination,
            currentTimeMillis = { 42L },
            threadNameProvider = { "worker-1" },
            processNameProvider = { "com.test.app" }
        )

        logger.d("Startup", "Logger is ready")

        val record = destination.singleRecord()
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
        val destination = RecordingDestination()
        val logger = DefaultLogger(
            destination = destination,
            processNameProvider = { "com.test.app" }
        )
        val throwable = IllegalArgumentException("broken")

        logger.e("Sync", "Upload failed", throwable)

        val record = destination.singleRecord()
        assertEquals(LogLevel.ERROR, record.level)
        assertEquals("Upload failed", record.message)
        assertSame(throwable, record.throwable)
    }

    @Test
    fun `logs below minimum level are skipped`() {
        val destination = RecordingDestination()
        val logger = DefaultLogger(
            destination = destination,
            processNameProvider = { "com.test.app" },
            minimumLogLevel = LogLevel.WARN
        )

        logger.d("Debug", "hidden")
        logger.w("Warn", "visible")

        assertEquals(1, destination.recordCount())
        assertEquals(LogLevel.WARN, destination.singleRecord().level)
    }

    @Test
    fun `custom log policy can override minimum level by tag`() {
        val destination = RecordingDestination()
        val tagLevels = mapOf("Network" to LogLevel.ERROR)
        val logger = DefaultLogger(
            destination = destination,
            processNameProvider = { "com.test.app" },
            minimumLogLevel = LogLevel.DEBUG,
            isLoggable = { level, tag ->
                level.priority >= (tagLevels[tag] ?: LogLevel.DEBUG).priority
            }
        )

        logger.w("Network", "hidden")
        logger.e("Network", "visible")
        logger.d("UI", "visible")

        assertEquals(2, destination.recordCount())
        assertEquals(LogLevel.ERROR, destination.records()[0].level)
        assertEquals(LogLevel.DEBUG, destination.records()[1].level)
    }

    private class RecordingDestination : LogDestination {
        private val records = mutableListOf<LogRecord>()

        override fun write(record: LogRecord) {
            records += record
        }

        fun singleRecord(): LogRecord = records.single()

        fun recordCount(): Int = records.size

        fun records(): List<LogRecord> = records.toList()
    }
}
