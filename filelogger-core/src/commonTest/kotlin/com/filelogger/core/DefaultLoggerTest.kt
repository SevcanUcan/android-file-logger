package com.filelogger.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DefaultLoggerTest {

    @Test
    fun filtersRecordsBelowTheConfiguredLevel() {
        val destination = BoundedMemoryLogDestination()
        val logger = DefaultLogger(
            destination = destination,
            config = LoggerConfig(minimumLevel = LogLevel.WARN),
            timestampProvider = { 42L }
        )

        logger.d("sync", "debug")
        logger.i("sync", "info")
        logger.w("sync", "warn")

        assertEquals(listOf(LogLevel.WARN), destination.snapshot().map { it.level })
    }

    @Test
    fun tagLevelOverridesTheGlobalLevel() {
        val destination = BoundedMemoryLogDestination()
        val logger = DefaultLogger(
            destination = destination,
            config = LoggerConfig(
                minimumLevel = LogLevel.ERROR,
                tagMinimumLevels = mapOf("network" to LogLevel.DEBUG)
            )
        )

        logger.d("network", "request")
        logger.w("database", "retry")

        assertEquals(listOf("network"), destination.snapshot().map { it.tag })
    }

    @Test
    fun eventAttributesOverrideStaticContext() {
        val destination = BoundedMemoryLogDestination()
        val logger = DefaultLogger(
            destination = destination,
            config = LoggerConfig(context = mapOf("tenant" to "global", "build" to "rc")),
            timestampProvider = { 99L },
            executionNameProvider = { "worker-1" }
        )

        logger.i("checkout", "paid", mapOf("tenant" to "event"))

        val record = destination.snapshot().single()
        assertEquals(99L, record.timestampMillis)
        assertEquals("worker-1", record.executionName)
        assertEquals(mapOf("tenant" to "event", "build" to "rc"), record.attributes)
    }

    @Test
    fun capturesThrowableTextWithoutHoldingTheThrowable() {
        val destination = BoundedMemoryLogDestination()
        val logger = DefaultLogger(destination)

        logger.e("checkout", "failed", IllegalStateException("payment rejected"))

        val throwableText = destination.snapshot().single().throwableText.orEmpty()
        assertTrue(throwableText.contains("IllegalStateException"))
        assertTrue(throwableText.contains("payment rejected"))
    }

    @Test
    fun isolatesDestinationFailuresByDefault() {
        val logger = DefaultLogger(LogDestination { error("disk unavailable") })

        logger.i("storage", "write")
    }

    @Test
    fun canPropagateDestinationFailuresForTests() {
        val logger = DefaultLogger(
            destination = LogDestination { error("disk unavailable") },
            config = LoggerConfig(propagateDestinationFailures = true)
        )

        assertFailsWith<IllegalStateException> { logger.i("storage", "write") }
    }
}
