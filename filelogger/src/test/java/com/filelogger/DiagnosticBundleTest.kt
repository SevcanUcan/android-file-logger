package com.filelogger

import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticBundleTest {
    @Test
    fun `metadata includes reliability metrics`() {
        val json = DiagnosticBundle.toJson(
            appPackageName = "com.test",
            processName = "com.test:sync",
            session = null,
            generatedAtMillis = 10,
            diagnostics = LogDiagnostics(
                totalRecords = 8,
                infoRecords = 3,
                errorRecords = 2,
                fileWriteFailures = 1,
                fileRotations = 3,
                uploadedRemoteBatches = 4,
                pendingRemoteBatches = 1
            ),
            config = LoggerConfig()
        )

        assertTrue(json.contains("\"totalRecords\": 8"))
        assertTrue(json.contains("\"infoRecords\": 3"))
        assertTrue(json.contains("\"errorRecords\": 2"))
        assertTrue(json.contains("\"fileWriteFailures\": 1"))
        assertTrue(json.contains("\"fileRotations\": 3"))
        assertTrue(json.contains("\"uploadedRemoteBatches\": 4"))
        assertTrue(json.contains("\"pendingRemoteBatches\": 1"))
    }
}
