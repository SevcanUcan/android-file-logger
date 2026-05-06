package com.filelogger

import android.util.Log
import java.io.File
import java.io.FileOutputStream

internal class FileLogDestination(
    private val fileProvider: () -> File,
    private val formatter: LogFormatter,
    private val rotationPolicy: FileRotationPolicy? = null
) : FlushableLogDestination {

    private val fileLock = Any()
    private var lastWrittenFile: File? = null

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
                lastWrittenFile = file
            } catch (e: Exception) {
                Log.e("FileLogger", "write failed", e)
            }
        }
    }

    override fun flush(timeoutMillis: Long): Boolean {
        synchronized(fileLock) {
            val file = lastWrittenFile ?: return true
            if (!file.exists()) {
                return true
            }

            return try {
                FileOutputStream(file, true).use { stream ->
                    stream.fd.sync()
                }
                true
            } catch (e: Exception) {
                Log.e("FileLogger", "flush failed", e)
                false
            }
        }
    }
}
