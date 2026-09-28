package com.filelogger

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogSession(
    val id: String,
    val directoryName: String,
    val startedAtMillis: Long
)

internal object LogSessionFactory {
    private val formatter = ThreadLocal.withInitial {
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
    }

    fun create(
        config: LoggerConfig,
        currentTimeMillis: Long = System.currentTimeMillis()
    ): LogSession {
        val id = config.sessionId ?: formatter.get()!!.format(Date(currentTimeMillis))
        return LogSession(
            id = id,
            directoryName = "${config.sessionFolderPrefix}_$id",
            startedAtMillis = currentTimeMillis
        )
    }
}

internal object LogDirectoryResolver {
    fun resolve(
        filesDir: File,
        config: LoggerConfig,
        session: LogSession?
    ): File {
        val root = File(filesDir, config.logFolder)
        return if (config.sessionLoggingEnabled && session != null) {
            File(root, session.directoryName)
        } else {
            root
        }
    }
}
