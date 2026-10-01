package com.filelogger

interface Logger {

    fun d(tag: String, msg: String)

    fun i(tag: String, msg: String)

    fun w(tag: String, msg: String)

    fun e(tag: String, msg: String, tr: Throwable? = null)
}

internal interface StructuredLogger : Logger {
    fun log(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable? = null,
        attributes: Map<String, String> = emptyMap()
    )
}
