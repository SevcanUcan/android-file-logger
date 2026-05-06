package com.filelogger

import java.io.File

internal class FileRotationPolicy(
    private val maxFileSize: Long,
    private val maxBackupFiles: Int,
    private val maxTotalLogSize: Long = Long.MAX_VALUE,
    private val maxLogAgeMillis: Long = Long.MAX_VALUE,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis
) {

    fun rotateIfNeeded(file: File, incomingBytes: ByteArray) {
        enforceRetention(file)

        if (maxFileSize <= 0L) {
            return
        }

        if (!file.exists()) {
            return
        }

        val projectedSize = file.length() + incomingBytes.size
        if (projectedSize <= maxFileSize) {
            return
        }

        rotate(file)
        enforceRetention(file)
    }

    private fun rotate(file: File) {
        if (maxBackupFiles <= 0) {
            file.delete()
            return
        }

        val oldestBackup = backupFile(file, maxBackupFiles)
        if (oldestBackup.exists()) {
            oldestBackup.delete()
        }

        for (index in maxBackupFiles - 1 downTo 1) {
            val source = backupFile(file, index)
            if (source.exists()) {
                source.renameTo(backupFile(file, index + 1))
            }
        }

        file.renameTo(backupFile(file, 1))
    }

    private fun backupFile(file: File, index: Int): File {
        return File(file.parentFile, "${file.name}.$index")
    }

    private fun enforceRetention(file: File) {
        val logFiles = logFiles(file)
        deleteExpiredBackups(file, logFiles)
        trimTotalSize(file, logFiles(file))
    }

    private fun deleteExpiredBackups(activeFile: File, logFiles: List<File>) {
        if (maxLogAgeMillis == Long.MAX_VALUE) {
            return
        }

        val threshold = currentTimeMillis() - maxLogAgeMillis
        logFiles
            .filter { it != activeFile }
            .filter { it.lastModified() < threshold }
            .forEach { it.delete() }
    }

    private fun trimTotalSize(activeFile: File, logFiles: List<File>) {
        if (maxTotalLogSize == Long.MAX_VALUE) {
            return
        }

        var totalSize = logFiles.sumOf { it.length() }
        if (totalSize <= maxTotalLogSize) {
            return
        }

        logFiles
            .filter { it != activeFile }
            .sortedBy { it.lastModified() }
            .forEach { candidate ->
                if (totalSize <= maxTotalLogSize) {
                    return
                }

                val size = candidate.length()
                if (candidate.delete()) {
                    totalSize -= size
                }
            }
    }

    private fun logFiles(file: File): List<File> {
        return file.parentFile
            ?.listFiles { candidate ->
                candidate.isFile &&
                    (candidate.name == file.name || candidate.name.startsWith("${file.name}."))
            }
            ?.toList()
            .orEmpty()
    }
}
