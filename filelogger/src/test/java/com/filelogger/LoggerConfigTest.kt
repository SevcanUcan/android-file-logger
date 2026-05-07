package com.filelogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class LoggerConfigTest {

    @Test
    fun `dev preset keeps verbose human readable logs longer`() {
        val config = LoggerConfig.dev()

        assertEquals(LogLevel.DEBUG, config.minimumLogLevel)
        assertTrue(config.logFormatter is PlainTextLogFormatter)
        assertEquals(TimeUnit.DAYS.toMillis(10), config.maxLogAgeMillis)
        assertEquals(2_048, config.asyncQueueCapacity)
    }

    @Test
    fun `prod preset filters debug logs and uses json`() {
        val config = LoggerConfig.prod()

        assertEquals(LogLevel.WARN, config.minimumLogLevel)
        assertTrue(config.logFormatter is JsonLogFormatter)
        assertEquals(TimeUnit.DAYS.toMillis(7), config.maxLogAgeMillis)
        assertEquals(1_024, config.asyncQueueCapacity)
    }
}
