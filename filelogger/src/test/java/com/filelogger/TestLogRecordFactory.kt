package com.filelogger

internal fun testRecord(
    timestampMillis: Long = 123L,
    level: LogLevel = LogLevel.DEBUG,
    tag: String = "FileLogger",
    message: String = "Hello",
    throwable: Throwable? = null,
    threadName: String = "main",
    processName: String = "com.test.app",
    attributes: Map<String, String> = emptyMap()
): LogRecord {
    return LogRecord(
        timestampMillis = timestampMillis,
        level = level,
        tag = tag,
        message = message,
        throwable = throwable,
        threadName = threadName,
        processName = processName,
        attributes = attributes
    )
}
