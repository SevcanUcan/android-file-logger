package com.filelogger.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.io.path.createTempDirectory

class LegacyKotlinSourceCompatibilityTest {
    @Test
    fun `rc6 Kotlin constructor copy and destructuring remain source compatible`() {
        val original = RemoteLogBatch(
            payload = byteArrayOf(1, 2, 3),
            contentType = "application/x-ndjson",
            contentEncoding = null
        )
        val copied = original.copy(payload = byteArrayOf(4))
        val (payload, contentType, contentEncoding) = copied
        val destination = RemoteLogDestination(
            uploader = RemoteLogUploader { true },
            config = RemoteLogConfig(createTempDirectory("legacy-kotlin-source").toFile())
        )

        assertEquals(1, payload.size)
        assertEquals("application/x-ndjson", contentType)
        assertNull(contentEncoding)
        destination.close(2_000)
    }
}
