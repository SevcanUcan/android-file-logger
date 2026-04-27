package com.filelogger

interface Logger {

    fun d(tag: String, msg: String)

    fun w(tag: String, msg: String)

    fun e(tag: String, msg: String, tr: Throwable? = null)
}
