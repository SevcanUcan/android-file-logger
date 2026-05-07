package com.filelogger

internal object ProcessLogFileName {

    fun forProcess(
        baseFileName: String,
        packageName: String,
        processName: String
    ): String {
        if (processName == packageName) {
            return baseFileName
        }

        val extensionStart = baseFileName.lastIndexOf('.')
        val safeProcessName = processName
            .removePrefix("$packageName:")
            .ifBlank { "unknown" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")

        return if (extensionStart > 0) {
            val name = baseFileName.substring(0, extensionStart)
            val extension = baseFileName.substring(extensionStart)
            "${name}_$safeProcessName$extension"
        } else {
            "${baseFileName}_$safeProcessName"
        }
    }
}
