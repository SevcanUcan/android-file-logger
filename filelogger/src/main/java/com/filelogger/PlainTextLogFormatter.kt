package com.filelogger

import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PlainTextLogFormatter : LogFormatter {

    override fun format(record: LogRecord): String {
        val header = buildString {
            append(timestampFormatter.format(Date(record.timestampMillis)))
            append(" ")
            append(record.level.shortName)
            append("/")
            append(record.tag)
            append(" ")
            append("[thread=")
            append(record.threadName)
            append(", process=")
            append(record.processName)
            append("]")
            if (record.attributes.isNotEmpty()) {
                append("[attributes=")
                append(gson.toJson(record.attributes))
                append("]")
            }
            append(" ")
            append(record.message)
        }

        val throwableSection = (record.throwableText ?: record.throwable?.stackTraceString())
            ?.let { "\n$it" }
            .orEmpty()

        return header + throwableSection + "\n"
    }

    private companion object {
        private val timestampFormatter =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        private val gson = Gson()
    }
}
