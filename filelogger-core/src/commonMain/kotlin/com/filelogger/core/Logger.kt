package com.filelogger.core

interface Logger {
    fun log(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable? = null,
        attributes: Map<String, String> = emptyMap()
    )

    fun d(tag: String, message: String, attributes: Map<String, String> = emptyMap()) =
        log(LogLevel.DEBUG, tag, message, attributes = attributes)

    fun i(tag: String, message: String, attributes: Map<String, String> = emptyMap()) =
        log(LogLevel.INFO, tag, message, attributes = attributes)

    fun w(tag: String, message: String, attributes: Map<String, String> = emptyMap()) =
        log(LogLevel.WARN, tag, message, attributes = attributes)

    fun e(
        tag: String,
        message: String,
        throwable: Throwable? = null,
        attributes: Map<String, String> = emptyMap()
    ) = log(LogLevel.ERROR, tag, message, throwable, attributes)
}
