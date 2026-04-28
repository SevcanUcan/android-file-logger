package com.filelogger

import android.util.Log
import java.io.File
import java.io.FileOutputStream

internal class FileLogDestination(
    private val fileProvider: () -> File,
    private val formatter: LogFormatter,
    private val rotationPolicy: FileRotationPolicy? = null
) : LogDestination {

    private val fileLock = Any()

    override fun write(record: LogRecord) {
        synchronized(fileLock) {
            val file = fileProvider().apply {
                parentFile?.mkdirs()
            }
            val encodedLine = formatter.format(record).toByteArray()

            try {
                rotationPolicy?.rotateIfNeeded(file, encodedLine)
                FileOutputStream(file, true).use { stream ->
                    stream.write(encodedLine)
                }
            } catch (e: Exception) {
                Log.e("FileLogger", "write failed", e)
            }
        }
    }
}
