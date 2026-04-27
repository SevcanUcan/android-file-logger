package com.filelogger

import android.util.Log

internal class LogcatDestination(
    private val chunkSize: Int = 4000,
    private val printer: LogcatPrinter = AndroidLogcatPrinter
) : LogDestination {

    override fun write(record: LogRecord) {
        val message = record.toLogcatMessage()
        var index = 0

        while (index < message.length) {
            val end = (index + chunkSize).coerceAtMost(message.length)
            val part = message.substring(index, end)

            when (record.level) {
                LogLevel.DEBUG -> printer.d(record.tag, part)
                LogLevel.WARN -> printer.w(record.tag, part)
                LogLevel.ERROR -> printer.e(record.tag, part)
            }

            index = end
        }
    }
}

internal interface LogcatPrinter {
    fun d(tag: String, message: String)

    fun w(tag: String, message: String)

    fun e(tag: String, message: String)
}

internal object AndroidLogcatPrinter : LogcatPrinter {
    override fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun w(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun e(tag: String, message: String) {
        Log.e(tag, message)
    }
}
