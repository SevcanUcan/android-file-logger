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
        assertEquals(true, config.useProcessSpecificLogFiles)
    }

    @Test
    fun `prod preset filters debug logs and uses json`() {
        val config = LoggerConfig.prod()

        assertEquals(LogLevel.WARN, config.minimumLogLevel)
        assertTrue(config.logFormatter is JsonLogFormatter)
        assertEquals(TimeUnit.DAYS.toMillis(7), config.maxLogAgeMillis)
        assertEquals(1_024, config.asyncQueueCapacity)
        assertEquals(true, config.useProcessSpecificLogFiles)
        assertTrue(config.redactor.redact("token=secret") != "token=secret")
    }

    @Test
    fun `builder creates config with custom destination`() {
        val destination = RecordingDestination()
        val config = LoggerConfig.Builder()
            .setMinimumLogLevel(LogLevel.ERROR)
            .setAsyncQueueCapacity(16)
            .setCrashCaptureEnabled(false)
            .setBreadcrumbCapacity(24)
            .setMaxBreadcrumbAttributes(8)
            .setMaxBreadcrumbBytes(4096)
            .setCollectionEnabled(false)
            .setSessionId("qa-001")
            .setSessionFolderPrefix("run")
            .addDestination(destination)
            .build()

        assertEquals(LogLevel.ERROR, config.minimumLogLevel)
        assertEquals(16, config.asyncQueueCapacity)
        assertEquals(false, config.crashCaptureEnabled)
        assertEquals(24, config.breadcrumbCapacity)
        assertEquals(8, config.maxBreadcrumbAttributes)
        assertEquals(4096, config.maxBreadcrumbBytes)
        assertEquals(false, config.collectionEnabled)
        assertEquals("qa-001", config.sessionId)
        assertEquals("run", config.sessionFolderPrefix)
        assertEquals(listOf(destination), config.customDestinations)
    }

    @Test
    fun `session folder prefix must not be blank`() {
        val error = runCatching {
            LoggerConfig(sessionFolderPrefix = " ")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    private class RecordingDestination : LogDestination {
        override fun write(record: LogRecord) = Unit
    }
}
