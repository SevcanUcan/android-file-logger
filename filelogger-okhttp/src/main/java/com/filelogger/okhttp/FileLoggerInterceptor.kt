package com.filelogger.okhttp

import com.filelogger.FileLogger
import com.filelogger.Logger
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class FileLoggerInterceptor(
    private val logger: Logger = FileLogger,
    private val config: NetworkLogConfig = NetworkLogConfig()
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        logger.d(config.tag, buildRequestMessage(request.method, request.url.toString(), request.headers, request.body))
        val startedAt = System.nanoTime()

        return try {
            val response = chain.proceed(request)
            val durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            logger.d(
                config.tag,
                buildResponseMessage(
                    code = response.code,
                    method = request.method,
                    url = request.url.toString(),
                    durationMillis = durationMillis,
                    headers = response.headers,
                    response = response
                )
            )
            response
        } catch (error: IOException) {
            val durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            logger.e(
                config.tag,
                "HTTP failed ${request.method} ${request.url} (${durationMillis}ms)",
                error
            )
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
            append(body)
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
        val buffer = Buffer()
        runCatching { body.writeTo(buffer) }.getOrElse { return }
        val byteCount = minOf(buffer.size, config.maxBodyBytes)
        append("\nbody=")
        append(buffer.readString(byteCount, StandardCharsets.UTF_8))
        if (!buffer.exhausted()) append("...[truncated]")
    }

    private fun isPlainText(mediaType: MediaType?): Boolean {
        if (mediaType == null) return true
        return mediaType.type == "text" || mediaType.subtype.contains("json") ||
            mediaType.subtype.contains("xml") || mediaType.subtype.contains("form")
    }
}
