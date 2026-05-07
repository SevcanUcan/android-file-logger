package com.filelogger

import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessLogFileNameTest {

    @Test
    fun `main process keeps configured file name`() {
        val fileName = ProcessLogFileName.forProcess(
            baseFileName = "app_log.txt",
            packageName = "com.test.app",
            processName = "com.test.app"
        )

        assertEquals("app_log.txt", fileName)
    }

    @Test
    fun `secondary process gets process specific file name`() {
        val fileName = ProcessLogFileName.forProcess(
            baseFileName = "app_log.txt",
            packageName = "com.test.app",
            processName = "com.test.app:sync"
        )

        assertEquals("app_log_sync.txt", fileName)
    }

    @Test
    fun `process suffix is sanitized`() {
        val fileName = ProcessLogFileName.forProcess(
            baseFileName = "app.log",
            packageName = "com.test.app",
            processName = "com.test.app:remote service"
        )

        assertEquals("app_remote_service.log", fileName)
    }
}
