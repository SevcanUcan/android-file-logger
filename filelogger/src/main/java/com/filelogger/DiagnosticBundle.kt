package com.filelogger

internal object DiagnosticBundle {
    fun toJson(
        appPackageName: String,
        processName: String,
        session: LogSession?,
        generatedAtMillis: Long,
        diagnostics: LogDiagnostics,
        config: LoggerConfig
    ): String {
        return buildString {
            append("{\n")
            appendJsonField("appPackageName", appPackageName, comma = true)
            appendJsonField("processName", processName, comma = true)
            appendJsonField("sessionId", session?.id, comma = true)
            appendJsonField("sessionDirectory", session?.directoryName, comma = true)
            appendNumberField("sessionStartedAtMillis", session?.startedAtMillis, comma = true)
            appendNumberField("generatedAtMillis", generatedAtMillis, comma = true)
            append("  \"diagnostics\": {\n")
            appendNumberField("droppedAsyncRecords", diagnostics.droppedAsyncRecords, comma = true, indent = "    ")
            appendNumberField("queuedAsyncRecords", diagnostics.queuedAsyncRecords, comma = true, indent = "    ")
            appendNumberField("asyncQueueCapacity", diagnostics.asyncQueueCapacity, comma = true, indent = "    ")
            appendStringArrayField("currentLogFiles", diagnostics.currentLogFiles, comma = false, indent = "    ")
            append("  },\n")
            append("  \"config\": {\n")
            appendJsonField("minimumLogLevel", config.minimumLogLevel.name, comma = true, indent = "    ")
            appendJsonField("formatter", config.logFormatter.javaClass.simpleName, comma = true, indent = "    ")
            appendNumberField("maxFileSize", config.maxFileSize, comma = true, indent = "    ")
            appendNumberField("maxTotalLogSize", config.maxTotalLogSize, comma = true, indent = "    ")
            appendNumberField("maxLogAgeMillis", config.maxLogAgeMillis, comma = true, indent = "    ")
            appendJsonField("asyncOverflowStrategy", config.asyncOverflowStrategy.name, comma = true, indent = "    ")
            appendBooleanField("errorSyncFallbackEnabled", config.errorSyncFallbackEnabled, comma = true, indent = "    ")
            appendBooleanField("sessionLoggingEnabled", config.sessionLoggingEnabled, comma = false, indent = "    ")
            append("  }\n")
            append("}\n")
        }
    }

    private fun StringBuilder.appendJsonField(
        name: String,
        value: String?,
        comma: Boolean,
        indent: String = "  "
    ) {
        append(indent)
        append('"').append(name).append("\": ")
        if (value == null) {
            append("null")
        } else {
            append('"').append(escape(value)).append('"')
        }
        appendComma(comma)
    }

    private fun StringBuilder.appendNumberField(
        name: String,
        value: Number?,
        comma: Boolean,
        indent: String = "  "
    ) {
        append(indent)
        append('"').append(name).append("\": ")
        append(value ?: "null")
        appendComma(comma)
    }

    private fun StringBuilder.appendBooleanField(
        name: String,
        value: Boolean,
        comma: Boolean,
        indent: String = "  "
    ) {
        append(indent)
        append('"').append(name).append("\": ").append(value)
        appendComma(comma)
    }

    private fun StringBuilder.appendStringArrayField(
        name: String,
        values: List<String>,
        comma: Boolean,
        indent: String = "  "
    ) {
        append(indent)
        append('"').append(name).append("\": [")
        append(values.joinToString(", ") { value -> "\"${escape(value)}\"" })
        append(']')
        appendComma(comma)
    }

    private fun StringBuilder.appendComma(comma: Boolean) {
        if (comma) {
            append(',')
        }
        append('\n')
    }

    private fun escape(value: String): String {
        return buildString {
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
        }
    }
}
