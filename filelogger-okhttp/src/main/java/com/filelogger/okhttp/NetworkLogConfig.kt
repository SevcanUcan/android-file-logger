package com.filelogger.okhttp

data class NetworkLogConfig(
    val tag: String = "OkHttp",
    val logRequestHeaders: Boolean = false,
    val logResponseHeaders: Boolean = false,
    val logRequestBody: Boolean = false,
    val logResponseBody: Boolean = false,
    val maxBodyBytes: Long = 16 * 1024,
    val redactedHeaders: Set<String> = DEFAULT_REDACTED_HEADERS
) {
    init {
        require(tag.isNotBlank()) { "tag must not be blank" }
        require(maxBodyBytes > 0) { "maxBodyBytes must be greater than zero" }
    }

    internal fun isHeaderRedacted(name: String): Boolean =
        redactedHeaders.any { it.equals(name, ignoreCase = true) }

    companion object {
        val DEFAULT_REDACTED_HEADERS: Set<String> = setOf(
            "Authorization",
            "Cookie",
            "Set-Cookie",
            "Proxy-Authorization",
            "X-Api-Key"
        )
    }
}
