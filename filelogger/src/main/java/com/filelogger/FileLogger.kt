package com.filelogger

import android.content.Context

object FileLogger : Logger {

    internal lateinit var context: Context
    internal var config: LoggerConfig = LoggerConfig()
    private lateinit var destination: LogDestination
    private lateinit var delegate: Logger

    fun init(
        context: Context,
        config: LoggerConfig = LoggerConfig()
    ) {
        this.context = context.applicationContext
        this.config = config

        destination = LoggerEngine.createDefaultDestination(
            filesDirProvider = { this.context.filesDir },
            configProvider = { this.config }
        )
        delegate = DefaultLogger(
            destination = destination,
            processNameProvider = { this.context.packageName }
        )
    }

    override fun d(tag: String, msg: String) {
        delegate.d(tag, msg)
    }

    override fun w(tag: String, msg: String) {
        delegate.w(tag, msg)
    }

    override fun e(tag: String, msg: String, tr: Throwable?) {
        delegate.e(tag, msg, tr)
    }

    fun flush(
        timeoutMillis: Long = FlushableLogDestination.DEFAULT_FLUSH_TIMEOUT_MILLIS
    ): Boolean {
        return (destination as? FlushableLogDestination)
            ?.flush(timeoutMillis)
            ?: true
    }
}
