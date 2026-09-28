package com.filelogger

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentLogBufferTest {

    @Test
    fun `buffer keeps only latest records`() {
        val buffer = RecentLogBuffer(capacity = 2)
        val first = testRecord(message = "first")
        val second = testRecord(message = "second")
        val third = testRecord(message = "third")

        buffer.write(first)
        buffer.write(second)
        buffer.write(third)

        assertEquals(listOf(second, third), buffer.snapshot())
    }

    @Test
    fun `zero capacity buffer stays empty`() {
        val buffer = RecentLogBuffer(capacity = 0)

        buffer.write(testRecord())

        assertEquals(emptyList<LogRecord>(), buffer.snapshot())
    }
}
