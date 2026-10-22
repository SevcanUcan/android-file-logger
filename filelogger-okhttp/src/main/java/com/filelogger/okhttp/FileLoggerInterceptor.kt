package com.filelogger.okhttp

import com.filelogger.FileLogger
import com.filelogger.LogLevel
import com.filelogger.LogRedactor
import com.filelogger.Logger
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.ForwardingSink
import okio.buffer
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class FileLoggerInterceptor(
    private val logger: Logger = FileLogger,
    private val config: NetworkLogConfig = NetworkLogConfig()
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (shouldLog(LogLevel.DEBUG)) {
            logger.d(
                config.tag,
                buildRequestMessage(
                    request.method,
                    safeUrl(request.url),
                    request.headers,
                    request.body
                )
            )
        }
        val startedAt = System.nanoTime()

        return try {
            val response = chain.proceed(request)
            val durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            if (shouldLog(LogLevel.DEBUG)) {
                logger.d(
                    config.tag,
                    buildResponseMessage(
                        code = response.code,
                        method = request.method,
                        url = safeUrl(request.url),
                        durationMillis = durationMillis,
                        headers = response.headers,
                        response = response
                    )
                )
            }
            response
        } catch (error: IOException) {
            val durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            if (shouldLog(LogLevel.ERROR)) {
                logger.e(
                    config.tag,
                    "HTTP failed ${request.method} ${safeUrl(request.url)} (${durationMillis}ms)",
                    error
                )
            }
            throw error
        }
    }

    private fun buildRequestMessage(
        method: String,
        url: String,
        headers: Headers,
        body: RequestBody?
    ): String = buildString {
        append("--> $method $url")
        if (config.logRequestHeaders) appendHeaders(headers)
        if (config.logRequestBody) appendBody(body)
    }

    private fun buildResponseMessage(
        code: Int,
        method: String,
        url: String,
        durationMillis: Long,
        headers: Headers,
        response: Response
    ): String = buildString {
        append("<-- $code $method $url (${durationMillis}ms)")
        if (config.logResponseHeaders) appendHeaders(headers)
        if (config.logResponseBody && isPlainText(response.body?.contentType())) {
            val body = response.peekBody(config.maxBodyBytes).string()
            append("\nbody=")
            append(LogRedactor.DEFAULT_SENSITIVE.redact(body))
            if ((response.body?.contentLength() ?: -1) > config.maxBodyBytes) {
                append("...[truncated]")
            }
        }
    }

    private fun StringBuilder.appendHeaders(headers: Headers) {
        headers.forEach { (name, value) ->
            append("\n$name: ")
            append(if (config.isHeaderRedacted(name)) "[REDACTED]" else value)
        }
    }

    private fun StringBuilder.appendBody(body: RequestBody?) {
        if (body == null || body.isDuplex() || body.isOneShot() || !isPlainText(body.contentType())) {
            return
        }
        val capture = captureBody(body) ?: return
        append("\nbody=")
        append(LogRedactor.DEFAULT_SENSITIVE.redact(capture.text))
        if (capture.truncated) append("...[truncated]")
    }

    private fun captureBody(body: RequestBody): BodyCapture? {
        val buffer = Buffer()
        val limitedSink = object : ForwardingSink(buffer) {
            private var capturedBytes = 0L

            override fun write(source: Buffer, byteCount: Long) {
                val remaining = (config.maxBodyBytes - capturedBytes).coerceAtLeast(0L)
                val capturedNow = minOf(remaining, byteCount)
                if (capturedNow > 0L) {
                    super.write(source, capturedNow)
                    capturedBytes += capturedNow
                }
                val discarded = byteCount - capturedNow
                if (discarded > 0L) {
                    source.skip(discarded)
                    throw BodyCaptureLimitReached()
                }
            }
        }.buffer()

        var truncated = false
        try {
            body.writeTo(limitedSink)
            limitedSink.flush()
        } catch (_: BodyCaptureLimitReached) {
            truncated = true
        } catch (_: Exception) {
            return null
        }
        return BodyCapture(
            text = buffer.readString(buffer.size, StandardCharsets.UTF_8),
            truncated = truncated
        )
    }

    private fun shouldLog(level: LogLevel): Boolean {
        return logger !== FileLogger || FileLogger.isLoggable(level, config.tag)
    }

    private fun safeUrl(url: HttpUrl): String {
        val redactedPath = url.pathSegments
            .mapIndexed { index, segment ->
                val previous = url.pathSegments.getOrNull(index - 1)?.lowercase()
                if (previous in SENSITIVE_PATH_OPERATIONS) {
                    "[REDACTED]"
                } else {
                    LogRedactor.DEFAULT_SENSITIVE.redact(segment)
                }
            }
        val safeBuilder = url.newBuilder()
            .username("")
            .password("")
            .query(null)
            .fragment(null)
        redactedPath.forEachIndexed(safeBuilder::setPathSegment)
        val withoutQueryOrFragment = safeBuilder.build()
            .toString()
            .replace("%5BREDACTED%5D", "[REDACTED]", ignoreCase = true)
            .replace("%5BREDACTED_EMAIL%5D", "[REDACTED_EMAIL]", ignoreCase = true)
            .replace("%5BREDACTED_JWT%5D", "[REDACTED_JWT]", ignoreCase = true)
        val encodedQuery = url.encodedQuery ?: return withoutQueryOrFragment
        val redactedQuery = encodedQuery
            .split('&')
            .joinToString("&") { parameter ->
                "${parameter.substringBefore('=')}=[REDACTED]"
            }
        return "$withoutQueryOrFragment?$redactedQuery"
    }

    private fun isPlainText(mediaType: MediaType?): Boolean {
        if (mediaType == null) return true
        return mediaType.type == "text" || mediaType.subtype.contains("json") ||
            mediaType.subtype.contains("xml") || mediaType.subtype.contains("form")
    }

    private data class BodyCapture(val text: String, val truncated: Boolean)

    private class BodyCaptureLimitReached : IOException()

    private companion object {
        val SENSITIVE_PATH_OPERATIONS = setOf(
            "reset-password",
            "password-reset",
            "verify-email",
            "email-verification",
            "activate",
            "invitation",
            "invite",
            "magic-link",
            "token"
        )
    }
}
