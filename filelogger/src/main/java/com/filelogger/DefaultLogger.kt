package com.filelogger

class DefaultLogger internal constructor(
    private val logWriter: LogWriter,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val threadNameProvider: () -> String = { Thread.currentThread().name },
    private val processNameProvider: () -> String
) : Logger {

    override fun d(tag: String, msg: String) {
        log(LogLevel.DEBUG, tag, msg)
    }

    override fun w(tag: String, msg: String) {
        log(LogLevel.WARN, tag, msg)
    }

    override fun e(tag: String, msg: String, tr: Throwable?) {
        log(LogLevel.ERROR, tag, msg, tr)
    }

    private fun log(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable? = null
    ) {
        logWriter.write(
            LogRecord(
                timestampMillis = currentTimeMillis(),
                level = level,
                tag = tag,
                message = message,
                throwable = throwable,
                threadName = threadNameProvider(),
                processName = processNameProvider()
            )
        )
    }
}
