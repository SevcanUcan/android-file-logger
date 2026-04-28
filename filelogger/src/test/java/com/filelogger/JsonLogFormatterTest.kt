package com.filelogger

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class JsonLogFormatterTest {

    @Test
    fun `json formatter writes structured log line`() {
        val formatter = JsonLogFormatter()
        val payload = Gson().fromJson(
            formatter.format(
                testRecord(
                    level = LogLevel.ERROR,
                    message = "Upload failed",
                    throwable = IllegalStateException("boom")
                )
            ).trim(),
            Map::class.java
        )

        assertEquals("E", payload["level"])
        assertEquals("FileLogger", payload["tag"])
        assertEquals("Upload failed", payload["message"])
        assertEquals("main", payload["thread"])
        assertEquals("com.test.app", payload["process"])
        assertNotNull(payload["time"])
        assertNotNull(payload["throwable"])
    }
}
