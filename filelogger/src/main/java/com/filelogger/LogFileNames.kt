package com.filelogger

internal object LogFileNames {
    fun isLogFile(fileName: String, baseLogFileName: String): Boolean {
        if (fileName == baseLogFileName || fileName.startsWith("$baseLogFileName.")) {
            return true
        }

        val extensionStart = baseLogFileName.lastIndexOf('.')
        if (extensionStart <= 0) {
            return fileName.startsWith("${baseLogFileName}_")
        }

        val prefix = baseLogFileName.substring(0, extensionStart)
        val extension = baseLogFileName.substring(extensionStart)
        return fileName.startsWith("${prefix}_") &&
            (fileName.endsWith(extension) || fileName.contains("$extension."))
    }
}
