package com.filelogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
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

    private fun ZipFile.readEntry(name: String): String {
        return getInputStream(getEntry(name)).bufferedReader().readText()
    }
}
