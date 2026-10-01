package com.filelogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BreadcrumbTest {

    @Test
    fun `buffer keeps latest breadcrumbs and can clear`() {
        val buffer = BreadcrumbBuffer(capacity = 2)
        val first = breadcrumb("first")
        val second = breadcrumb("second")
        val third = breadcrumb("third")

        buffer.add(first)
        buffer.add(second)
        buffer.add(third)

        assertEquals(listOf(second, third), buffer.snapshot())
        buffer.clear()
        assertTrue(buffer.snapshot().isEmpty())
    }

    @Test
    fun `factory redacts message and attribute values`() {
        val breadcrumb = BreadcrumbFactory.create(
            category = "navigation",
            message = "Opened account for user@example.com",
            attributes = mapOf("authorization" to "Bearer secret-token"),
            timestampMillis = 10,
            redactor = LogRedactor.DEFAULT_SENSITIVE,
            maxAttributes = 2,
            maxBytes = 1024
        )

        assertFalse(breadcrumb.message.contains("user@example.com"))
        assertFalse(breadcrumb.attributes.getValue("authorization").contains("secret-token"))
    }

    @Test
    fun `factory rejects unsafe identifiers and bounds`() {
        val invalidCategory = createFailure(category = "../navigation")
        val tooManyAttributes = createFailure(
            attributes = mapOf("one" to "1", "two" to "2"),
            maxAttributes = 1
        )
        val tooLarge = createFailure(message = "12345", maxBytes = 4)

        assertTrue(invalidCategory is IllegalArgumentException)
        assertEquals(
            "breadcrumb attributes exceed maxBreadcrumbAttributes",
            tooManyAttributes?.message
        )
        assertEquals("breadcrumb exceeds maxBreadcrumbBytes", tooLarge?.message)
    }

    private fun breadcrumb(message: String) = Breadcrumb(
        timestampMillis = 1,
        category = "user",
        message = message
    )

    private fun createFailure(
        category: String = "user",
        message: String = "message",
        attributes: Map<String, String> = emptyMap(),
        maxAttributes: Int = 16,
        maxBytes: Int = 1024
    ): Throwable? = runCatching {
        BreadcrumbFactory.create(
            category = category,
            message = message,
            attributes = attributes,
            timestampMillis = 1,
            redactor = LogRedactor.NONE,
            maxAttributes = maxAttributes,
            maxBytes = maxBytes
        )
    }.exceptionOrNull()
}
