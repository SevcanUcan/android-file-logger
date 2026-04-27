package com.filelogger

import android.util.Log
import com.google.gson.GsonBuilder
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal interface LogWriter {
    fun write(record: LogRecord)
}

internal object LoggerEngine : LogWriter {

    private val fileLock = Any()

    private val gson = GsonBuilder()
        .disableHtmlEscaping()
        .create()

    private val fmt =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    override fun write(record: LogRecord) {
        val logcatMessage = buildLogcatMessage(record)
        val size = 4000
        var index = 0

        while (index < logcatMessage.length) {
            val end = (index + size).coerceAtMost(logcatMessage.length)
            val part = logcatMessage.substring(index, end)

            when (record.level) {
                LogLevel.DEBUG -> Log.d(record.tag, part)
                LogLevel.WARN -> Log.w(record.tag, part)
                LogLevel.ERROR -> Log.e(record.tag, part)
            }

            index = end
        }

        LogWorker.enqueue(record)
    }

    internal fun writeToFile(record: LogRecord) {
        synchronized(fileLock) {
            val file = getLogFile()

            val json = gson.toJson(
                mapOf(
                    "time" to fmt.format(Date(record.timestampMillis)),
                    "level" to record.level.shortName,
                    "tag" to record.tag,
                    "message" to record.message,
                    "throwable" to record.throwable?.stackTraceString(),
                    "thread" to record.threadName,
                    "process" to record.processName
                )
            ) + "\n"

            try {
                FileOutputStream(file, true).use {
                    it.write(json.toByteArray())
                }
            } catch (e: Exception) {
                Log.e("FileLogger", "write failed", e)
            }
        }
    }

    private fun buildLogcatMessage(record: LogRecord): String {
        val throwableMessage = record.throwable?.stackTraceString()
            ?.let { "\n$it" }
            .orEmpty()

        return record.message + throwableMessage
    }

    private fun getLogFile(): File {
        val root = File(
            FileLogger.context.filesDir,
            FileLogger.config.logFolder
        )

        val file = File(
            root,
            FileLogger.config.logFileName
        )

        file.parentFile?.mkdirs()

        return file
    }
}

private fun Throwable.stackTraceString(): String {
    val writer = StringWriter()
    printStackTrace(PrintWriter(writer))
    return writer.toString()
}
