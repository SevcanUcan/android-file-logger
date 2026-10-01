package com.filelogger.ui

import com.filelogger.LogLevel
import com.filelogger.StoredLogEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class LogViewerFilterTest {

    @Test
    fun `filters by selected levels`() {
        val entries = listOf(entry(LogLevel.DEBUG, "debug"), entry(LogLevel.ERROR, "error"))

        val result = LogViewerFilter(levels = setOf(LogLevel.ERROR)).apply(entries)

        assertEquals(listOf("error"), result.map { it.message })
    }

    @Test
    fun `searches tag message and throwable case insensitively`() {
        val entries = listOf(
            entry(LogLevel.DEBUG, "ready", tag = "Startup"),
            entry(LogLevel.ERROR, "failed", throwable = "IllegalStateException: TOKEN"),
            entry(LogLevel.WARN, "network slow")
        )

        assertEquals(1, LogViewerFilter(search = "startup").apply(entries).size)
        assertEquals(1, LogViewerFilter(search = "token").apply(entries).size)
        assertEquals(1, LogViewerFilter(search = "NETWORK").apply(entries).size)
    }

    @Test
    fun `empty search and all levels preserve entries`() {
        val entries = listOf(entry(LogLevel.DEBUG, "one"), entry(LogLevel.WARN, "two"))

        assertEquals(entries, LogViewerFilter().apply(entries))
    }

    @Test
    fun `searches structured attribute keys and values`() {
        val entries = listOf(
            entry(LogLevel.INFO, "checkout", attributes = mapOf("orderId" to "A-42")),
            entry(LogLevel.INFO, "profile")
        )

        assertEquals(1, LogViewerFilter(search = "orderId").apply(entries).size)
        assertEquals(1, LogViewerFilter(search = "a-42").apply(entries).size)
    }

    private fun entry(
        level: LogLevel,
        message: String,
        tag: String = "Test",
        throwable: String? = null,
        attributes: Map<String, String> = emptyMap()
    ) = StoredLogEntry(
        timestampMillis = 1,
        level = level,
        tag = tag,
        message = message,
        throwable = throwable,
        threadName = "main",
        processName = "app",
        attributes = attributes,
        sessionId = "session",
        sourceFileName = "app.log"
    )
}
