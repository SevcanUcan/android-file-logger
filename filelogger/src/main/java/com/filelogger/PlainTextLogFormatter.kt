package com.filelogger

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
            append("] ")
            append(record.message)
        }

        val throwableSection = record.throwable
            ?.stackTraceString()
            ?.let { "\n$it" }
            .orEmpty()

        return header + throwableSection + "\n"
    }

    private companion object {
        private val timestampFormatter =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
}
