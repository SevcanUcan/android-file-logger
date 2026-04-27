package com.filelogger

import android.content.Context
import android.util.Log

object FileLogger {

    internal lateinit var context: Context
    internal var config: LoggerConfig = LoggerConfig()

    fun init(
        context: Context,
        config: LoggerConfig = LoggerConfig()
    ) {
        this.context = context.applicationContext
        this.config = config

        LogWorker.start()
    }

    fun d(tag: String, msg: String) {
        LoggerEngine.log("D", tag, msg)
    }

    fun w(tag: String, msg: String) {
        LoggerEngine.log("W", tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        val message =
            msg + (tr?.let { "\n${Log.getStackTraceString(it)}" } ?: "")

        LoggerEngine.log("E", tag, message)
    }
}
