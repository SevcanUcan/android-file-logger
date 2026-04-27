package com.filelogger

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

class FileLogDestinationTest {

    @Test
    fun `file destination writes structured json line`() {
        val logFile = File.createTempFile("filelogger", ".log")
        val destination = FileLogDestination(fileProvider = { logFile })
        val record = testRecord(
            level = LogLevel.ERROR,
            message = "Upload failed",
            throwable = IllegalStateException("boom")
        )

        destination.write(record)

        val line = logFile.readText().trim()
        val payload = Gson().fromJson(line, Map::class.java)

        assertEquals("E", payload["level"])
        assertEquals("FileLogger", payload["tag"])
        assertEquals("Upload failed", payload["message"])
        assertEquals("main", payload["thread"])
        assertEquals("com.test.app", payload["process"])
        assertNotNull(payload["time"])
        assertNotNull(payload["throwable"])
    }
}
