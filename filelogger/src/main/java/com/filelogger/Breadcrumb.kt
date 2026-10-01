package com.filelogger

import com.google.gson.annotations.SerializedName
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque

data class Breadcrumb(
    @SerializedName("timestampMillis")
    val timestampMillis: Long,
    @SerializedName("category")
    val category: String,
    @SerializedName("message")
    val message: String,
    @SerializedName("attributes")
    val attributes: Map<String, String> = emptyMap()
)

internal class BreadcrumbBuffer(
    private val capacity: Int
) {
    private val lock = Any()
    private val breadcrumbs = ArrayDeque<Breadcrumb>()

    fun add(breadcrumb: Breadcrumb) {
        if (capacity <= 0) return
        synchronized(lock) {
            while (breadcrumbs.size >= capacity) {
                breadcrumbs.removeFirst()
            }
            breadcrumbs.addLast(breadcrumb)
        }
    }

    fun snapshot(): List<Breadcrumb> = synchronized(lock) {
        breadcrumbs.toList()
    }

    fun clear() = synchronized(lock) {
        breadcrumbs.clear()
    }
}

internal object BreadcrumbFactory {
    private val validIdentifier = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    fun create(
        category: String,
        message: String,
        attributes: Map<String, String>,
        timestampMillis: Long,
        redactor: LogRedactor,
        maxAttributes: Int,
        maxBytes: Int
    ): Breadcrumb {
        require(validIdentifier.matches(category)) {
            "breadcrumb category must match ${validIdentifier.pattern}"
        }
        require(message.isNotBlank()) { "breadcrumb message must not be blank" }
        require(attributes.size <= maxAttributes) {
            "breadcrumb attributes exceed maxBreadcrumbAttributes"
        }
        attributes.keys.forEach { key ->
            require(validIdentifier.matches(key)) {
                "breadcrumb attribute key must match ${validIdentifier.pattern}"
            }
        }

        val breadcrumb = Breadcrumb(
            timestampMillis = timestampMillis,
            category = category,
            message = redactor.redact(message),
            attributes = attributes.mapValues { (_, value) -> redactor.redact(value) }
        )
        val size = breadcrumb.message.toByteArray(StandardCharsets.UTF_8).size +
            breadcrumb.attributes.entries.sumOf { (key, value) ->
                key.toByteArray(StandardCharsets.UTF_8).size +
                    value.toByteArray(StandardCharsets.UTF_8).size
            }
        require(size <= maxBytes) { "breadcrumb exceeds maxBreadcrumbBytes" }
        return breadcrumb
    }
}
