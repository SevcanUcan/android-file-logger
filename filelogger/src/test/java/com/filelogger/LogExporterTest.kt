package com.filelogger

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.CRC32
import kotlin.io.path.createTempDirectory

class LogExporterTest {

    @Test
    fun `export writes log files into zip`() {
        val directory = createTempDirectory("filelogger-export").toFile()
        File(directory, "app.log").writeText("hello\n")
        File(directory, "app.log.1").writeText("older\n")
        val output = File(directory.parentFile, "logs.zip")

        LogExporter.export(
            logDirectory = directory,
            outputFile = output,
            baseLogFileName = "app.log"
        )

        assertTrue(output.exists())
        ZipFile(output).use { zip ->
            assertEquals("hello\n", zip.readEntry("app.log"))
            assertEquals("older\n", zip.readEntry("app.log.1"))
        }
    }

    @Test
    fun `export applies redactor to each line`() {
        val directory = createTempDirectory("filelogger-export").toFile()
        File(directory, "app.log").writeText("token=secret\nsafe\n")
        val output = File(directory.parentFile, "logs-redacted.zip")

        LogExporter.export(
            logDirectory = directory,
            outputFile = output,
            baseLogFileName = "app.log",
            redactor = LogRedactor { line -> line.replace("secret", "***") }
        )

        ZipFile(output).use { zip ->
            assertEquals("token=***\nsafe\n", zip.readEntry("app.log"))
        }
    }

    @Test
    fun `default export redacts secrets without changing source log`() {
        val directory = createTempDirectory("filelogger-safe-export").toFile()
        val source = File(directory, "app.log")
        val original = "{\"password\":\"hunter2\",\"token\":\"secret-token\"}\n"
        source.writeText(original)
        val output = File(directory.parentFile, "logs-safe-default.zip")

        LogExporter.export(
            logDirectory = directory,
            outputFile = output,
            baseLogFileName = "app.log"
        )

        ZipFile(output).use { zip ->
            val exported = zip.readEntry("app.log")
            assertTrue(exported.contains("[REDACTED]"))
            assertTrue(!exported.contains("hunter2"))
            assertTrue(!exported.contains("secret-token"))
        }
        assertEquals(original, source.readText())
    }

    @Test
    fun `explicit none redactor preserves export content`() {
        val directory = createTempDirectory("filelogger-unsafe-export").toFile()
        File(directory, "app.log").writeText("token=visible-secret\n")
        val output = File(directory.parentFile, "logs-explicit-none.zip")

        LogExporter.export(
            logDirectory = directory,
            outputFile = output,
            baseLogFileName = "app.log",
            redactor = LogRedactor.NONE
        )

        ZipFile(output).use { zip ->
            assertEquals("token=visible-secret\n", zip.readEntry("app.log"))
        }
    }

    @Test
    fun `export skips unrelated files`() {
        val directory = createTempDirectory("filelogger-export").toFile()
        File(directory, "app.log").writeText("log\n")
        File(directory, "notes.txt").writeText("private\n")
        val output = File(directory.parentFile, "logs-filtered.zip")

        LogExporter.export(
            logDirectory = directory,
            outputFile = output,
            baseLogFileName = "app.log"
        )

        ZipFile(output).use { zip ->
            assertEquals("log\n", zip.readEntry("app.log"))
            assertEquals(null, zip.getEntry("notes.txt"))
        }
    }

    @Test
    fun `export writes metadata entries`() {
        val directory = createTempDirectory("filelogger-export").toFile()
        File(directory, "app.log").writeText("log\n")
        val output = File(directory.parentFile, "logs-metadata.zip")

        LogExporter.export(
            logDirectory = directory,
            outputFile = output,
            baseLogFileName = "app.log",
            metadataEntries = mapOf("diagnostics.json" to "{\"ok\":true}\n")
        )

        ZipFile(output).use { zip ->
            assertEquals("{\"ok\":true}\n", zip.readEntry("diagnostics.json"))
        }
    }

    @Test
    fun `export can create metadata only archive`() {
        val directory = createTempDirectory("filelogger-export").toFile()
        File(directory, "app.log").writeText("private log\n")
        val output = File(directory.parentFile, "metadata-only.zip")

        LogExporter.export(
            logDirectory = directory,
            outputFile = output,
            baseLogFileName = "app.log",
            metadataEntries = mapOf("support-report.json" to "{}\n"),
            includeLogs = false
        )

        ZipFile(output).use { zip ->
            assertEquals(null, zip.getEntry("app.log"))
            assertEquals("{}\n", zip.readEntry("support-report.json"))
        }
    }

    @Test
    fun `export entries have valid CRC and complete content`() {
        val directory = createTempDirectory("filelogger-export-integrity").toFile()
        val expected = buildString {
            repeat(2_000) { index -> append("line-").append(index).append('-').append("x".repeat(32)).append('\n') }
        }
        File(directory, "app.log").writeText(expected)
        val output = File(directory.parentFile, "logs-integrity.zip")

        LogExporter.export(directory, output, "app.log", redactor = LogRedactor.NONE)

        ZipFile(output).use { zip ->
            val entry = zip.getEntry("app.log")
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            val crc = CRC32().apply { update(bytes) }.value
            assertEquals(expected, bytes.toString(Charsets.UTF_8))
            assertEquals("ZIP entry CRC must match the complete exported bytes", entry.crc, crc)
            assertEquals(bytes.size.toLong(), entry.size)
        }
    }

    @Test
    fun `export preserves parseable ndjson records`() {
        val directory = createTempDirectory("filelogger-export-ndjson").toFile()
        val records = listOf(
            "{\"level\":\"INFO\",\"message\":\"started\"}",
            "{\"level\":\"ERROR\",\"message\":\"failed\",\"attempt\":2}"
        )
        File(directory, "app.log").writeText(records.joinToString(separator = "\n", postfix = "\n"))
        val output = File(directory.parentFile, "logs-ndjson.zip")

        LogExporter.export(directory, output, "app.log", redactor = LogRedactor.NONE)

        ZipFile(output).use { zip ->
            val exportedRecords = zip.readEntry("app.log").lineSequence().filter { it.isNotBlank() }.toList()
            assertEquals(records.size, exportedRecords.size)
            exportedRecords.forEach { line ->
                assertTrue("Every exported NDJSON line must remain a JSON object", JsonParser.parseString(line).isJsonObject)
            }
        }
    }

    @Test
    fun `failed export never leaves a partial archive at the final path`() {
        val directory = createTempDirectory("filelogger-export-failure").toFile()
        File(directory, "app.log").writeText("first\nsecond\n")
        val output = File(
            createTempDirectory("filelogger-export-failure-output").toFile(),
            "logs-must-not-be-partial.zip"
        )
        var lines = 0

        val failure = runCatching {
            LogExporter.export(
                logDirectory = directory,
                outputFile = output,
                baseLogFileName = "app.log",
                redactor = LogRedactor { line ->
                    lines += 1
                    if (lines == 2) error("injected export failure")
                    line
                }
            )
        }.exceptionOrNull()

        assertTrue("The fault injection must abort export", failure != null)
        assertFalse("A failed export must not leave a truncated final ZIP", output.exists())
    }

    private fun ZipFile.readEntry(name: String): String {
        return getInputStream(getEntry(name)).bufferedReader().readText()
    }
}
