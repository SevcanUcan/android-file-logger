package com.filelogger.kmp

import com.filelogger.FileLogger
import com.filelogger.LogLevel
import com.filelogger.core.LogDestination
import com.filelogger.core.LogRecord

/** Routes records produced in common Kotlin code through the Android FileLogger pipeline. */
class FileLoggerDestination : LogDestination {

    override fun write(record: LogRecord) {
        FileLogger.log(
            level = record.level.toAndroidLevel(),
            tag = record.tag,
            message = record.message,
            throwable = record.throwableText?.let(::MultiplatformLogException),
            attributes = record.attributes + mapOf(
                "core.execution.name" to record.executionName,
                "core.process.name" to record.processName
            )
        )
    }

    private fun com.filelogger.core.LogLevel.toAndroidLevel(): LogLevel = when (this) {
        com.filelogger.core.LogLevel.DEBUG -> LogLevel.DEBUG
        com.filelogger.core.LogLevel.INFO -> LogLevel.INFO
        com.filelogger.core.LogLevel.WARN -> LogLevel.WARN
        com.filelogger.core.LogLevel.ERROR -> LogLevel.ERROR
    }

    private class MultiplatformLogException(message: String) : RuntimeException(message) {
        override fun fillInStackTrace(): Throwable = this
    }
}
