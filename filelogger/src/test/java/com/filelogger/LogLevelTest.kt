package com.filelogger

import org.junit.Assert.assertEquals
import org.junit.Test

class LogLevelTest {

    @Test
    fun `short names stay stable`() {
        assertEquals("D", LogLevel.DEBUG.shortName)
        assertEquals("W", LogLevel.WARN.shortName)
        assertEquals("E", LogLevel.ERROR.shortName)
    }
}
