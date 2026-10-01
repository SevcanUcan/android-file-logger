package com.filelogger

import java.io.PrintWriter
import java.io.StringWriter

internal fun Throwable.stackTraceString(): String {
    val writer = StringWriter()
    printStackTrace(PrintWriter(writer))
    return writer.toString()
}

internal fun LogRecord.toLogcatMessage(): String {
    val attributeMessage = attributes
        .takeIf { it.isNotEmpty() }
        ?.entries
        ?.joinToString(prefix = " ", separator = ", ") { (key, value) -> "$key=$value" }
        .orEmpty()
    val throwableMessage = (throwableText ?: throwable?.stackTraceString())
        ?.let { "\n$it" }
        .orEmpty()

    return message + attributeMessage + throwableMessage
}
