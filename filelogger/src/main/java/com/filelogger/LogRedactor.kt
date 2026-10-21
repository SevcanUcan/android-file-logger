package com.filelogger

fun interface LogRedactor {
    fun redact(line: String): String

    companion object {
        val NONE = LogRedactor { line -> line }

        private const val SENSITIVE_FIELD_PATTERN =
            "password|passwd|token|access[_-]?token|refresh[_-]?token|id[_-]?token|secret|api[_-]?key|authorization|cookie|set[_-]?cookie|phone|address|jwt"

        private val jsonSensitiveValue = Regex(
            "(?i)(\\\"(?:$SENSITIVE_FIELD_PATTERN)\\\"\\s*:\\s*\\\")((?:\\\\.|[^\\\"\\\\])*)(\\\")"
        )
        private val jwt = Regex(
            "\\beyJ[A-Za-z0-9_-]+\\.eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b"
        )

        val DEFAULT_SENSITIVE: LogRedactor = SensitiveKeyAwareLogRedactor { line ->
            line
                .replace(jsonSensitiveValue) { match ->
                    "${match.groupValues[1]}[REDACTED]${match.groupValues[3]}"
                }
                .replace(Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b"), "[REDACTED_EMAIL]")
                .replace(Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+"), "Bearer [REDACTED_TOKEN]")
                .replace(jwt, "[REDACTED_JWT]")
                .replace(
                    Regex("(?i)\\b($SENSITIVE_FIELD_PATTERN)\\s*[:=]\\s*[^\\s,;}]+")
                ) { match ->
                    "${match.groupValues[1]}=[REDACTED]"
                }
                .replace(Regex("\\b(?:\\d[ -]?){13,19}\\b"), "[REDACTED_NUMBER]")
        }

        fun chain(vararg redactors: LogRedactor): LogRedactor {
            val redact: (String) -> String = { line ->
                redactors.fold(line) { current, redactor -> redactor.redact(current) }
            }
            return if (redactors.any { it is SensitiveKeyAware }) {
                SensitiveKeyAwareLogRedactor(redact)
            } else {
                LogRedactor(redact)
            }
        }
    }
}

private interface SensitiveKeyAware

private class SensitiveKeyAwareLogRedactor(
    private val delegate: (String) -> String
) : LogRedactor, SensitiveKeyAware {
    override fun redact(line: String): String = delegate(line)
}

internal object SensitiveFieldPolicy {
    private val sensitiveNames = setOf(
        "password",
        "passwd",
        "token",
        "access_token",
        "access-token",
        "refresh_token",
        "refresh-token",
        "id_token",
        "id-token",
        "secret",
        "api_key",
        "api-key",
        "authorization",
        "cookie",
        "set_cookie",
        "set-cookie",
        "phone",
        "address",
        "jwt"
    )

    fun redactAttribute(key: String, value: String, redactor: LogRedactor): String {
        return if (redactor is SensitiveKeyAware && key.lowercase() in sensitiveNames) {
            "[REDACTED]"
        } else {
            redactor.redact(value)
        }
    }
}
