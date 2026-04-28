package com.filelogger

import android.util.Log
import java.io.File
import java.io.FileOutputStream

internal class FileLogDestination(
    private val fileProvider: () -> File,
    private val formatter: LogFormatter
) : LogDestination {

    private val fileLock = Any()

    override fun write(record: LogRecord) {
        synchronized(fileLock) {
            val file = fileProvider().apply {
                parentFile?.mkdirs()
            }

            try {
                FileOutputStream(file, true).use { stream ->
                    stream.write(formatter.format(record).toByteArray())
                }
            } catch (e: Exception) {
                Log.e("FileLogger", "write failed", e)
            }
        }
    }
}
