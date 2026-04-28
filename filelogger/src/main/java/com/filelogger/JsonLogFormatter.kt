package com.filelogger

import com.google.gson.GsonBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class JsonLogFormatter : LogFormatter {

    override fun format(record: LogRecord): String {
        return gson.toJson(
            mapOf(
                "time" to timestampFormatter.format(Date(record.timestampMillis)),
                "level" to record.level.shortName,
                "tag" to record.tag,
                "message" to record.message,
                "throwable" to record.throwable?.stackTraceString(),
                "thread" to record.threadName,
                "process" to record.processName
            )
        ) + "\n"
    }

    private companion object {
        private val gson = GsonBuilder()
            .disableHtmlEscaping()
            .create()

        private val timestampFormatter =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
}
