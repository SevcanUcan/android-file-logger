package com.filelogger

import java.io.File

internal class FileRotationPolicy(
    private val maxFileSize: Long,
    private val maxBackupFiles: Int
) {

    fun rotateIfNeeded(file: File, incomingBytes: ByteArray) {
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
}
