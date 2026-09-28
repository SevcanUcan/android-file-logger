package com.filelogger

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class LogSessionTest {

    @Test
    fun `factory uses configured session id`() {
        val session = LogSessionFactory.create(
            config = LoggerConfig(sessionId = "manual-id", sessionFolderPrefix = "run"),
            currentTimeMillis = 42L
        )

        assertEquals("manual-id", session.id)
        assertEquals("run_manual-id", session.directoryName)
        assertEquals(42L, session.startedAtMillis)
    }

    @Test
    fun `directory resolver nests logs under session folder when enabled`() {
        val filesDir = File("files")
        val session = LogSession("abc", "session_abc", 42L)

        val directory = LogDirectoryResolver.resolve(
            filesDir = filesDir,
            config = LoggerConfig(logFolder = "logs", sessionLoggingEnabled = true),
            session = session
        )

        assertEquals(File(File(filesDir, "logs"), "session_abc"), directory)
    }

    @Test
    fun `directory resolver keeps legacy folder when session logging is disabled`() {
        val filesDir = File("files")
        val session = LogSession("abc", "session_abc", 42L)

        val directory = LogDirectoryResolver.resolve(
            filesDir = filesDir,
            config = LoggerConfig(logFolder = "logs", sessionLoggingEnabled = false),
            session = session
        )

        assertEquals(File(filesDir, "logs"), directory)
    }
}
