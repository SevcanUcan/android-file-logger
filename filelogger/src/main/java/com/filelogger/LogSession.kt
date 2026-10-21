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
        val root = ContainedPathResolver.resolve(
            root = filesDir,
            relativePath = config.logFolder,
            fieldName = "logFolder",
            allowNested = true
        )
        return if (config.sessionLoggingEnabled && session != null) {
            ContainedPathResolver.resolve(
                root = root,
                relativePath = session.directoryName,
                fieldName = "session directory",
                allowNested = false
            )
        } else {
            root
        }
    }

    fun resolveSession(root: File, config: LoggerConfig, sessionId: String): File {
        ContainedPathResolver.validateRelativePath(sessionId, "sessionId", allowNested = false)
        return ContainedPathResolver.resolve(
            root = root,
            relativePath = "${config.sessionFolderPrefix}_$sessionId",
            fieldName = "session directory",
            allowNested = false
        )
    }
}
