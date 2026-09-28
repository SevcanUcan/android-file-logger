package com.filelogger

fun interface LogRedactor {
    fun redact(line: String): String

    companion object {
        val NONE = LogRedactor { line -> line }

        val DEFAULT_SENSITIVE = LogRedactor { line ->
            line
                .replace(Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b"), "[REDACTED_EMAIL]")
                .replace(Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+"), "Bearer [REDACTED_TOKEN]")
                .replace(
                    Regex("(?i)\\b(password|passwd|token|secret|api[_-]?key|authorization)\\s*[:=]\\s*[^\\s,;}]+")
                ) { match ->
                    "${match.groupValues[1]}=[REDACTED]"
                }
                .replace(Regex("\\b(?:\\d[ -]?){13,19}\\b"), "[REDACTED_NUMBER]")
        }

        fun chain(vararg redactors: LogRedactor): LogRedactor {
            return LogRedactor { line ->
                redactors.fold(line) { current, redactor -> redactor.redact(current) }
            }
        }
    }
}
