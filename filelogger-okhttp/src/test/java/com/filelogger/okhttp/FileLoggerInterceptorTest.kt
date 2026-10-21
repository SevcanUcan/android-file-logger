package com.filelogger.okhttp

import com.filelogger.FileLogger
import com.filelogger.Logger
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

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
    fun `redacts authorization and cookie headers case insensitively`() {
        server.enqueue(MockResponse().setBody("ok"))
        val client = client(NetworkLogConfig(logRequestHeaders = true))
        val request = Request.Builder()
            .url(server.url("/headers"))
            .header("authorization", "Bearer lower-case-secret")
            .header("cOoKiE", "session=cookie-secret")
            .build()

        client.newCall(request).execute().close()

        val combined = logger.debug.joinToString("\n")
        assertFalse(combined.contains("lower-case-secret"))
        assertFalse(combined.contains("cookie-secret"))
        assertTrue(combined.contains("authorization: [REDACTED]", ignoreCase = true))
        assertTrue(combined.contains("cookie: [REDACTED]", ignoreCase = true))
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

    @Test
    fun `redacts every query value in request and response logs`() {
        server.enqueue(MockResponse().setBody("ok"))
        val client = client(NetworkLogConfig())
        val url = server.url("/expenses?token=query-secret&email=dev@example.com&page=2")

        client.newCall(Request.Builder().url(url).build()).execute().close()

        val combined = logger.debug.joinToString("\n")
        assertFalse(combined.contains("query-secret"))
        assertFalse(combined.contains("dev@example.com"))
        assertFalse(combined.contains("page=2"))
        assertTrue(combined.contains("token=[REDACTED]"))
        assertTrue(combined.contains("page=[REDACTED]"))
    }

    @Test
    fun `redacts query values in failure logs`() {
        val url = server.url("/failure?token=failure-secret&user=42")
        server.shutdown()
        val client = client(NetworkLogConfig())

        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute()
        }

        val message = logger.errors.single().first
        assertFalse(message.contains("failure-secret"))
        assertFalse(message.contains("user=42"))
        assertTrue(message.contains("token=[REDACTED]"))
    }

    @Test
    fun `request body capture stops near configured limit for a 100 MB stream`() {
        val maxBodyBytes = 16L * 1024
        val body = CountingBody(totalBytes = 100L * 1024 * 1024)
        val request = Request.Builder()
            .url("https://example.test/upload")
            .post(body)
            .build()
        val interceptor = FileLoggerInterceptor(
            logger = logger,
            config = NetworkLogConfig(logRequestBody = true, maxBodyBytes = maxBodyBytes)
        )

        interceptor.intercept(FakeChain(request))

        assertTrue(
            "body logging produced ${body.producedBytes} bytes for a $maxBodyBytes-byte limit",
            body.producedBytes <= maxBodyBytes + CountingBody.CHUNK_SIZE
        )
        assertTrue(logger.debug.single { it.startsWith("-->") }.contains("[truncated]"))
    }

    @Test
    fun `disabled FileLogger does not evaluate request body`() {
        val body = CountingBody(totalBytes = 1024)
        val request = Request.Builder()
            .url("https://example.test/disabled")
            .post(body)
            .build()
        FileLogger.setCollectionEnabled(false)
        try {
            FileLoggerInterceptor(
                config = NetworkLogConfig(logRequestBody = true)
            ).intercept(FakeChain(request))
        } finally {
            FileLogger.setCollectionEnabled(true)
        }

        assertEquals(0L, body.producedBytes)
    }

    @Test
    fun `body logging does not change bytes sent to the network`() {
        server.enqueue(MockResponse().setBody("ok"))
        val payload = "streamed-network-payload"
        val body = CountingBody(
            totalBytes = payload.toByteArray().size.toLong(),
            chunk = payload.toByteArray()
        )
        val client = client(NetworkLogConfig(logRequestBody = true, maxBodyBytes = 8))

        client.newCall(Request.Builder().url(server.url("/stream")).post(body).build())
            .execute()
            .close()

        assertEquals(payload, server.takeRequest().body.readUtf8())
        assertEquals(2, body.writeCalls)
    }

    @Test
    fun `one shot duplex and binary request bodies are not evaluated for logging`() {
        val oneShot = CountingBody(totalBytes = 16, oneShot = true)
        val duplex = CountingBody(totalBytes = 16, duplex = true)
        val binary = CountingBody(
            totalBytes = 16,
            mediaType = "application/octet-stream".toMediaType()
        )
        val interceptor = FileLoggerInterceptor(
            logger = logger,
            config = NetworkLogConfig(logRequestBody = true)
        )

        interceptor.intercept(FakeChain(requestWith(oneShot)))
        interceptor.intercept(FakeChain(requestWith(duplex)))
        interceptor.intercept(FakeChain(requestWith(binary)))

        assertEquals(0L, oneShot.producedBytes)
        assertEquals(0L, duplex.producedBytes)
        assertEquals(0L, binary.producedBytes)
    }

    @Test
    fun `redacts json secrets and jwt in enabled request body`() {
        val json = """{"password":"hunter2","access_token":"token-value","jwt":"eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature"}"""
        val request = Request.Builder()
            .url("https://example.test/json")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        val interceptor = FileLoggerInterceptor(
            logger = logger,
            config = NetworkLogConfig(logRequestBody = true)
        )

        interceptor.intercept(FakeChain(request))

        val message = logger.debug.single { it.startsWith("-->") }
        assertFalse(message.contains("hunter2"))
        assertFalse(message.contains("token-value"))
        assertFalse(message.contains("eyJhbGciOiJIUzI1NiJ9"))
    }

    @Test
    fun `redacts url user info in request and response logs`() {
        server.enqueue(MockResponse().setBody("ok"))
        val url = server.url("/private?token=query-secret#client-fragment").newBuilder()
            .username("alice@example.com")
            .password("p@ss word")
            .build()

        client(NetworkLogConfig()).newCall(Request.Builder().url(url).build()).execute().close()

        val combined = logger.debug.joinToString("\n")
        assertFalse("URL username leaked into request/response logs: $combined", combined.contains("alice", ignoreCase = true))
        assertFalse("Encoded URL password leaked into request/response logs", combined.contains("p%40ss", ignoreCase = true))
        assertFalse("URL password leaked into request/response logs", combined.contains("p@ss", ignoreCase = true))
        assertFalse("Query value leaked into request/response logs", combined.contains("query-secret"))
        assertFalse("Fragment leaked into request/response logs", combined.contains("client-fragment"))
    }

    @Test
    fun `redacts url user info in failure logs`() {
        val url = server.url("/failure?token=query-secret#client-fragment").newBuilder()
            .username("encoded-user")
            .password("encoded-secret")
            .build()
        server.shutdown()

        runCatching {
            client(NetworkLogConfig()).newCall(Request.Builder().url(url).build()).execute()
        }

        val message = logger.errors.single().first
        assertFalse("URL username leaked into failure log: $message", message.contains("encoded-user"))
        assertFalse("URL password leaked into failure log", message.contains("encoded-secret"))
        assertFalse("Query value leaked into failure log", message.contains("query-secret"))
        assertFalse("Fragment leaked into failure log", message.contains("client-fragment"))
    }

    @Test
    fun `redacts credential path segment after a sensitive operation`() {
        val secret = "secret-token-value"
        val request = Request.Builder()
            .url("https://example.test/reset-password/$secret")
            .build()

        FileLoggerInterceptor(logger, NetworkLogConfig()).intercept(FakeChain(request))

        val combined = logger.debug.joinToString("\n")
        assertFalse("Credential-bearing URL path leaked into logs: $combined", combined.contains(secret))
        assertTrue(combined.contains("/reset-password/[REDACTED]"))
    }

    @Test
    fun `redacts token routes and email addresses in url paths`() {
        val token = "path-token-secret"
        val email = "john@example.com"
        val interceptor = FileLoggerInterceptor(logger, NetworkLogConfig())

        interceptor.intercept(
            FakeChain(Request.Builder().url("https://example.test/v1/auth/token/$token").build())
        )
        interceptor.intercept(
            FakeChain(Request.Builder().url("https://example.test/users/$email").build())
        )

        val combined = logger.debug.joinToString("\n")
        assertFalse("Token route value leaked into logs: $combined", combined.contains(token))
        assertFalse("Email path value leaked into logs: $combined", combined.contains(email))
    }

    private fun client(config: NetworkLogConfig): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(FileLoggerInterceptor(logger, config))
            .build()

    private fun requestWith(body: RequestBody): Request = Request.Builder()
        .url("https://example.test/body")
        .post(body)
        .build()

    private class CountingBody(
        private val totalBytes: Long,
        private val chunk: ByteArray = ByteArray(CHUNK_SIZE) { 'x'.code.toByte() },
        private val oneShot: Boolean = false,
        private val duplex: Boolean = false,
        private val mediaType: okhttp3.MediaType = "text/plain".toMediaType()
    ) : RequestBody() {
        var producedBytes: Long = 0
            private set
        var writeCalls: Int = 0
            private set

        override fun contentType() = mediaType

        override fun contentLength(): Long = totalBytes

        override fun isOneShot(): Boolean = oneShot

        override fun isDuplex(): Boolean = duplex

        override fun writeTo(sink: BufferedSink) {
            writeCalls += 1
            var remaining = totalBytes
            while (remaining > 0) {
                val count = minOf(remaining, chunk.size.toLong()).toInt()
                sink.write(chunk, 0, count)
                producedBytes += count
                remaining -= count
            }
        }

        companion object {
            const val CHUNK_SIZE = 8 * 1024
        }
    }

    private class FakeChain(private val request: Request) : Interceptor.Chain {
        override fun request(): Request = request

        override fun proceed(request: Request): Response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("ok".toResponseBody("text/plain".toMediaType()))
            .build()

        override fun connection(): Connection? = null

        override fun call(): Call = throw UnsupportedOperationException("not required")

        override fun connectTimeoutMillis(): Int = 10_000

        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

        override fun readTimeoutMillis(): Int = 10_000

        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

        override fun writeTimeoutMillis(): Int = 10_000

        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }

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
