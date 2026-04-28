package com.filelogger

interface LogFormatter {
    fun format(record: LogRecord): String
}
