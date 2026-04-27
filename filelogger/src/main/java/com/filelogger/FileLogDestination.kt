package com.filelogger

import android.util.Log
import com.google.gson.GsonBuilder
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class FileLogDestination(
    private val fileProvider: () -> File,
    private val lineFormatter: (LogRecord) -> String = ::formatJsonLine
) : LogDestination {

    private val fileLock = Any()

    override fun write(record: LogRecord) {
        synchronized(fileLock) {
            val file = fileProvider().apply {
                parentFile?.mkdirs()
            }

            try {
                FileOutputStream(file, true).use { stream ->
                    stream.write(lineFormatter(record).toByteArray())
                }
            } catch (e: Exception) {
                Log.e("FileLogger", "write failed", e)
            }
        }
    }

    companion object {
        private val gson = GsonBuilder()
            .disableHtmlEscaping()
            .create()

        private val timestampFormatter =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

        private fun formatJsonLine(record: LogRecord): String {
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
    }
}
