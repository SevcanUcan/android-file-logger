package com.filelogger.remote

import com.filelogger.LogLevel
import com.filelogger.LogRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlin.io.path.createTempDirectory

class RemoteLogDestinationTest {

    @Test
    fun `batches gzips uploads and clears spool`() {
        val directory = createTempDirectory("filelogger-remote").toFile()
        val batches = mutableListOf<RemoteLogBatch>()
        val destination = RemoteLogDestination(
            uploader = RemoteLogUploader { batch -> batches += batch; true },
            config = RemoteLogConfig(directory, batchSize = 2),
            sleeper = {}
        )

        destination.write(record("first"))
        destination.write(record("second"))

        assertTrue(destination.flush(2_000))
        assertEquals(1, batches.size)
        assertEquals("gzip", batches.single().contentEncoding)
        val decoded = GZIPInputStream(ByteArrayInputStream(batches.single().payload))
            .bufferedReader().readText()
        assertTrue(decoded.contains("first"))
        assertTrue(decoded.contains("second"))
        assertEquals(1, destination.diagnostics().uploadedRemoteBatches)
        assertEquals(0, destination.diagnostics().pendingRemoteBatches)
        destination.close(2_000)
    }

    @Test
    fun `retries with exponential backoff before success`() {
        val delays = mutableListOf<Long>()
        var attempts = 0
        val destination = RemoteLogDestination(
            uploader = RemoteLogUploader { ++attempts >= 3 },
            config = RemoteLogConfig(
                createTempDirectory("filelogger-remote").toFile(),
                batchSize = 1,
                maxRetries = 3,
                initialBackoffMillis = 10
            ),
            sleeper = delays::add
        )

        destination.write(record("retry"))

        assertTrue(destination.flush(2_000))
        assertEquals(3, attempts)
        assertEquals(listOf(10L, 20L), delays)
        destination.close(2_000)
    }

    @Test
    fun `keeps failed batch on disk for next destination`() {
        val directory = createTempDirectory("filelogger-remote").toFile()
        val failing = RemoteLogDestination(
            uploader = RemoteLogUploader { false },
            config = RemoteLogConfig(directory, batchSize = 1, maxRetries = 0),
            sleeper = {}
        )
        failing.write(record("offline"))

        assertFalse(failing.flush(2_000))
        assertEquals(1, failing.diagnostics().pendingRemoteBatches)
        failing.close(2_000)

        val payloads = mutableListOf<ByteArray>()
        val recovered = RemoteLogDestination(
            uploader = RemoteLogUploader { batch -> payloads += batch.payload; true },
            config = RemoteLogConfig(directory, batchSize = 1),
            sleeper = {}
        )

        assertTrue(recovered.flush(2_000))
        assertEquals(1, payloads.size)
        assertEquals(0, recovered.diagnostics().pendingRemoteBatches)
        recovered.close(2_000)
    }

    @Test
    fun `close flushes partial batch and stops worker`() {
        val directory = createTempDirectory("filelogger-remote").toFile()
        var uploads = 0
        val destination = RemoteLogDestination(
            uploader = RemoteLogUploader { uploads += 1; true },
            config = RemoteLogConfig(directory, batchSize = 10),
            sleeper = {}
        )
        destination.write(record("partial"))

        assertTrue(destination.close(2_000))
        assertEquals(1, uploads)
        destination.write(record("after-close"))
        assertEquals(1, destination.diagnostics().droppedAsyncRecords)
    }

    @Test
    fun `erase pending data clears queued records without uploading`() {
        val directory = createTempDirectory("filelogger-remote").toFile()
        var uploads = 0
        val destination = RemoteLogDestination(
            uploader = RemoteLogUploader { uploads += 1; true },
            config = RemoteLogConfig(directory, batchSize = 10),
            sleeper = {}
        )
        destination.write(record("private"))

        assertTrue(destination.erasePendingData(2_000))
        assertTrue(destination.flush(2_000))
        assertEquals(0, uploads)
        assertEquals(0, destination.diagnostics().pendingRemoteBatches)
        destination.close(2_000)
    }

    @Test
    fun `erase pending data removes failed spool batches`() {
        val directory = createTempDirectory("filelogger-remote").toFile()
        val destination = RemoteLogDestination(
            uploader = RemoteLogUploader { false },
            config = RemoteLogConfig(directory, batchSize = 1, maxRetries = 0),
            sleeper = {}
        )
        destination.write(record("offline-private"))

        assertTrue(destination.erasePendingData(2_000))
        assertEquals(0, destination.diagnostics().pendingRemoteBatches)
        destination.close(2_000)
    }

    private fun record(message: String) = LogRecord(
        timestampMillis = 1,
        level = LogLevel.DEBUG,
        tag = "Remote",
        message = message,
        threadName = "test",
        processName = "test"
    )
}
