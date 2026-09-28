package com.filelogger

import org.junit.Assert.assertEquals
import org.junit.Test

class LogRedactorTest {

    @Test
    fun `default sensitive redactor masks common secrets`() {
        val line = "email=dev@example.com password:abc123 Authorization=secret Bearer eyJabc 4111 1111 1111 1111"

        val redacted = LogRedactor.DEFAULT_SENSITIVE.redact(line)

        assertEquals(
            "email=[REDACTED_EMAIL] password=[REDACTED] Authorization=[REDACTED] Bearer [REDACTED_TOKEN] [REDACTED_NUMBER]",
            redacted
        )
    }

    @Test
    fun `chain applies redactors in order`() {
        val redactor = LogRedactor.chain(
            LogRedactor { line -> line.replace("first", "second") },
            LogRedactor { line -> line.replace("second", "third") }
        )

        assertEquals("third", redactor.redact("first"))
    }
}
