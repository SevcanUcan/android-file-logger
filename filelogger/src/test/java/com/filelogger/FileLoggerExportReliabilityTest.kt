package com.filelogger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class FileLoggerExportReliabilityTest {
    @Test
    fun `facade refuses export when flush barrier fails`() {
        val root = createTempDirectory("filelogger-export-flush-failure").toFile()
        val logs = File(root, "logs").apply { mkdirs() }
        File(logs, "app.log").writeText("must-not-be-labelled-complete\n")
        val output = File(root, "exports/logs.zip")
        setField("destination", object : FlushableLogDestination {
            override fun write(record: LogRecord) = Unit
            override fun flush(timeoutMillis: Long): Boolean = false
        })
        setField("logDirectory", logs)
        setField("logsRootDirectory", logs)
        setField("exportDirectory", output.parentFile)
        setField("storageCoordinator", LogStorageCoordinator(logs))

        try {
            val failure = runCatching { FileLogger.exportLogs(output) }.exceptionOrNull()
            assertTrue("A failed flush barrier must abort export", failure is IllegalStateException)
            assertFalse("No archive may be published after a failed flush", output.exists())
        } finally {
            setField("destination", null)
            setField("logDirectory", null)
            setField("logsRootDirectory", null)
            setField("exportDirectory", null)
            setField("storageCoordinator", null)
        }
    }

    private fun setField(name: String, value: Any?) {
        FileLogger::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(FileLogger, value)
        }
    }
}
