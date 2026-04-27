package com.filelogger

import android.util.Log
import com.google.gson.GsonBuilder
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object LoggerEngine {

    private val fileLock = Any()

    private val gson = GsonBuilder()
        .disableHtmlEscaping()
        .create()

    private val fmt =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun log(level: String, tag: String, message: String) {
        val size = 4000
        var index = 0

        while (index < message.length) {
            val end = (index + size).coerceAtMost(message.length)
            val part = message.substring(index, end)

            when (level) {
                "D" -> Log.d(tag, part)
                "W" -> Log.w(tag, part)
                "E" -> Log.e(tag, part)
                else -> Log.i(tag, part)
            }

            LogWorker.enqueue(
                LogTask(level, tag, part)
            )

            index = end
        }
    }

    internal fun writeToFile(
        level: String,
        tag: String,
        message: String
    ) {
        synchronized(fileLock) {
            val file = getLogFile()

            val json = gson.toJson(
                mapOf(
                    "time" to fmt.format(Date()),
                    "level" to level,
                    "tag" to tag,
                    "thread" to Thread.currentThread().name,
                    "message" to message
                )
            ) + "\n"

            try {
                FileOutputStream(file, true).use {
                    it.write(json.toByteArray())
                }
            } catch (e: Exception) {
                Log.e("FileLogger", "write failed", e)
            }
        }
    }

    private fun getLogFile(): File {
        val root = File(
            FileLogger.context.filesDir,
            FileLogger.config.logFolder
        )

        val file = File(
            root,
            FileLogger.config.logFileName
        )

        file.parentFile?.mkdirs()

        return file
    }
}
