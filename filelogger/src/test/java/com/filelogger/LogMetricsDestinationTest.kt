package com.filelogger

import org.junit.Assert.assertEquals
import org.junit.Test

class LogMetricsDestinationTest {

    @Test
    fun `counts records by level`() {
        val destination = LogMetricsDestination()

        destination.write(testRecord(level = LogLevel.DEBUG))
        destination.write(testRecord(level = LogLevel.INFO))
        destination.write(testRecord(level = LogLevel.WARN))
        destination.write(testRecord(level = LogLevel.ERROR))
        destination.write(testRecord(level = LogLevel.ERROR))

        val diagnostics = destination.diagnostics()
        assertEquals(5, diagnostics.totalRecords)
        assertEquals(1, diagnostics.debugRecords)
        assertEquals(1, diagnostics.infoRecords)
        assertEquals(1, diagnostics.warningRecords)
        assertEquals(2, diagnostics.errorRecords)
    }

    @Test
    fun `diagnostics combine core file and remote metrics`() {
        val combined = LogDiagnostics(
            totalRecords = 3,
            fileWriteFailures = 1,
            uploadedRemoteBatches = 2
        ) + LogDiagnostics(
            totalRecords = 4,
            fileRotations = 2,
            pendingRemoteBatches = 1
        )

        assertEquals(7, combined.totalRecords)
        assertEquals(1, combined.fileWriteFailures)
        assertEquals(2, combined.fileRotations)
        assertEquals(2, combined.uploadedRemoteBatches)
        assertEquals(1, combined.pendingRemoteBatches)
    }
}
