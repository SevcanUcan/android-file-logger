package com.filelogger.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LogDestinationTest {

    @Test
    fun boundedDestinationDropsTheOldestRecord() {
        val destination = BoundedMemoryLogDestination(capacity = 2)

        destination.write(record("first"))
        destination.write(record("second"))
        destination.write(record("third"))

        assertEquals(listOf("second", "third"), destination.snapshot().map { it.message })
    }

    @Test
    fun boundedDestinationRejectsInvalidCapacity() {
        assertFailsWith<IllegalArgumentException> { BoundedMemoryLogDestination(0) }
    }

    @Test
    fun compositeContinuesAfterDestinationFailure() {
        val delivered = mutableListOf<LogRecord>()
        val composite = CompositeLogDestination(
            LogDestination { error("unavailable") },
            LogDestination { delivered += it }
        )

        composite.write(record("kept"))

        assertEquals(listOf("kept"), delivered.map { it.message })
    }

    private fun record(message: String) = LogRecord(
        timestampMillis = 1L,
        level = LogLevel.INFO,
        tag = "test",
        message = message
    )
}
