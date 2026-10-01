package com.filelogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.io.path.createTempDirectory

class LogReaderTest {

    @Test
    fun `reads and filters json logs by level tag and keyword`() {
        val directory = createTempDirectory("filelogger-reader").toFile()
        val file = File(directory, "app.log")
        file.writeText(
            json("2026-09-29 10:00:00.000", "D", "Startup", "ready") +
                json("2026-09-29 10:00:01.000", "E", "Sync", "Token refresh failed")
        )

        val result = LogReader.read(
            directory,
            "app.log",
            "session-1",
            LogQuery(
                levels = setOf(LogLevel.ERROR),
                tags = setOf("Sync"),
                keyword = "refresh"
            )
        )

        assertEquals(1, result.size)
        assertEquals("Token refresh failed", result.single().message)
        assertEquals("session-1", result.single().sessionId)
    }

    @Test
    fun `reads plain text and attaches throwable continuation lines`() {
        val directory = createTempDirectory("filelogger-reader").toFile()
        File(directory, "app.log").writeText(
            "2026-09-29 10:00:00.000 E/Crash [thread=main, process=app] failed\n" +
                "java.lang.IllegalStateException: boom\n" +
                "\tat sample.Main.run(Main.kt:1)\n"
        )

        val entry = LogReader.read(
            directory,
            "app.log",
            null,
            LogQuery()
        ).single()

        assertEquals("failed", entry.message)
        assertTrue(entry.throwable.orEmpty().contains("IllegalStateException"))
        assertTrue(entry.throwable.orEmpty().contains("Main.kt:1"))
    }

    @Test
    fun `reads info and filters json structured attributes`() {
        val directory = createTempDirectory("filelogger-reader").toFile()
        File(directory, "app.log").writeText(
            json(
                "2026-09-29 10:00:00.000",
                "I",
                "Checkout",
                "started",
                "{\"screen\":\"checkout\",\"order\":\"A-1\"}"
            )
        )

        val entry = LogReader.read(
            directory,
            "app.log",
            null,
            LogQuery(attributes = mapOf("order" to "A-1"), keyword = "checkout")
        ).single()

        assertEquals(LogLevel.INFO, entry.level)
        assertEquals(mapOf("screen" to "checkout", "order" to "A-1"), entry.attributes)
    }

    @Test
    fun `reads plain text structured attributes`() {
        val directory = createTempDirectory("filelogger-reader").toFile()
        File(directory, "app.log").writeText(
            "2026-09-29 10:00:00.000 I/Checkout [thread=main, process=app]" +
                "[attributes={\"order\":\"A-1\"}] completed\n"
        )

        val entry = LogReader.read(directory, "app.log", null, LogQuery()).single()

        assertEquals("completed", entry.message)
        assertEquals(mapOf("order" to "A-1"), entry.attributes)
    }

    @Test
    fun `reads rotated files oldest first then active file`() {
        val directory = createTempDirectory("filelogger-reader").toFile()
        File(directory, "app.log.2").writeText(json("2026-09-29 09:00:00.000", "D", "T", "oldest"))
        File(directory, "app.log.1").writeText(json("2026-09-29 10:00:00.000", "D", "T", "older"))
        File(directory, "app.log").writeText(json("2026-09-29 11:00:00.000", "D", "T", "current"))

        val messages = LogReader.read(directory, "app.log", null, LogQuery())
            .map { it.message }

        assertEquals(listOf("oldest", "older", "current"), messages)
    }

    @Test
    fun `filters inclusive timestamp range and applies limit`() {
        val directory = createTempDirectory("filelogger-reader").toFile()
        File(directory, "app.log").writeText(
            json("2026-09-29 10:00:00.000", "D", "T", "first") +
                json("2026-09-29 10:00:01.000", "W", "T", "second") +
                json("2026-09-29 10:00:02.000", "E", "T", "third")
        )
        val boundary = timestamp("2026-09-29 10:00:01.000")

        val result = LogReader.read(
            directory,
            "app.log",
            null,
            LogQuery(fromTimestampMillis = boundary, toTimestampMillis = boundary, limit = 1)
        )

        assertEquals(listOf("second"), result.map { it.message })
    }

    @Test
    fun `skips malformed and unrelated files`() {
        val directory = createTempDirectory("filelogger-reader").toFile()
        File(directory, "app.log").writeText("not a log\n" + json("2026-09-29 10:00:00.000", "D", "T", "valid"))
        File(directory, "private.txt").writeText(json("2026-09-29 10:00:00.000", "E", "T", "secret"))

        val result = LogReader.read(directory, "app.log", null, LogQuery())

        assertEquals(listOf("valid"), result.map { it.message })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `query rejects invalid timestamp range`() {
        LogQuery(fromTimestampMillis = 2, toTimestampMillis = 1)
    }

    private fun json(
        time: String,
        level: String,
        tag: String,
        message: String,
        attributes: String = "{}"
    ): String {
        return """{"time":"$time","level":"$level","tag":"$tag","message":"$message","throwable":null,"thread":"main","process":"app","attributes":$attributes}""" + "\n"
    }

    private fun timestamp(value: String): Long {
        return requireNotNull(
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).parse(value)
        ).time
    }
}
