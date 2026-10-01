package com.filelogger.okhttp

import com.filelogger.Logger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class FileLoggerInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var logger: RecordingLogger

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        logger = RecordingLogger()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `logs request response status and duration`() {
        server.enqueue(MockResponse().setResponseCode(201).setBody("created"))
        val client = client(NetworkLogConfig())

        client.newCall(Request.Builder().url(server.url("/expenses")).build()).execute().close()

        assertTrue(logger.debug.single { it.startsWith("-->") }.contains("GET"))
        val responseLog = logger.debug.single { it.startsWith("<--") }
        assertTrue(responseLog.contains("201 GET"))
        assertTrue(responseLog.contains("ms)"))
    }

    @Test
    fun `redacts sensitive headers and keeps body disabled by default`() {
        server.enqueue(MockResponse().setBody("secret-response"))
        val client = client(NetworkLogConfig(logRequestHeaders = true))
        val request = Request.Builder()
            .url(server.url("/private"))
            .header("Authorization", "Bearer top-secret")
            .post("secret-request".toRequestBody("text/plain".toMediaType()))
            .build()

        client.newCall(request).execute().close()

        val combined = logger.debug.joinToString("\n")
        assertTrue(combined.contains("Authorization: [REDACTED]"))
        assertFalse(combined.contains("top-secret"))
        assertFalse(combined.contains("secret-request"))
        assertFalse(combined.contains("secret-response"))
    }

    @Test
    fun `truncates enabled request and response bodies`() {
        server.enqueue(MockResponse().setBody("response-is-long"))
        val client = client(
            NetworkLogConfig(
                logRequestBody = true,
                logResponseBody = true,
                maxBodyBytes = 8
            )
        )
        val request = Request.Builder()
            .url(server.url("/body"))
            .post("request-is-long".toRequestBody("text/plain".toMediaType()))
            .build()

        client.newCall(request).execute().close()

        assertTrue(logger.debug.any { it.contains("request-") && it.contains("[truncated]") })
        assertTrue(logger.debug.any { it.contains("response") && it.contains("[truncated]") })
    }

    @Test
    fun `logs and rethrows network failures`() {
        val url = server.url("/failure")
        server.shutdown()
        val client = client(NetworkLogConfig())

        val error = runCatching {
            client.newCall(Request.Builder().url(url).build()).execute()
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals(1, logger.errors.size)
        assertTrue(logger.errors.single().second === error)
    }

    private fun client(config: NetworkLogConfig): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(FileLoggerInterceptor(logger, config))
            .build()

    private class RecordingLogger : Logger {
        val debug = mutableListOf<String>()
        val errors = mutableListOf<Pair<String, Throwable?>>()

        override fun d(tag: String, msg: String) {
            debug += msg
        }

        override fun i(tag: String, msg: String) = Unit

        override fun w(tag: String, msg: String) = Unit

        override fun e(tag: String, msg: String, tr: Throwable?) {
            errors += msg to tr
        }
    }
}
