package com.filelogger

import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

internal class FileRotationPolicy(
    private val maxFileSize: Long,
    private val maxBackupFiles: Int,
    private val maxTotalLogSize: Long = Long.MAX_VALUE,
    private val maxLogAgeMillis: Long = Long.MAX_VALUE,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val fileSystem: LogFileSystem = SystemLogFileSystem
) {
    private val rotations = AtomicLong()

    fun rotateIfNeeded(file: File, incomingBytes: ByteArray) {
        enforceRetention(file)

        if (maxFileSize <= 0L) {
            return
        }

        if (!fileSystem.exists(file)) {
            return
        }

        val projectedSize = fileSystem.length(file) + incomingBytes.size
        if (projectedSize <= maxFileSize) {
            return
        }

        rotate(file)
        rotations.incrementAndGet()
        enforceRetention(file)
    }

    fun rotationCount(): Long = rotations.get()

    private fun rotate(file: File) {
        if (maxBackupFiles <= 0) {
            deleteOrThrow(file)
            return
        }

        val oldestBackup = backupFile(file, maxBackupFiles)
        if (fileSystem.exists(oldestBackup)) {
            deleteOrThrow(oldestBackup)
        }

        for (index in maxBackupFiles - 1 downTo 1) {
            val source = backupFile(file, index)
            if (fileSystem.exists(source)) {
                renameOrThrow(source, backupFile(file, index + 1))
            }
        }

        renameOrThrow(file, backupFile(file, 1))
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
            .filter { fileSystem.lastModified(it) < threshold }
            .forEach { deleteOrThrow(it) }
    }

    private fun trimTotalSize(activeFile: File, logFiles: List<File>) {
        if (maxTotalLogSize == Long.MAX_VALUE) {
            return
        }

        var totalSize = logFiles.sumOf { fileSystem.length(it) }
        if (totalSize <= maxTotalLogSize) {
            return
        }

        logFiles
            .filter { it != activeFile }
            .sortedBy { fileSystem.lastModified(it) }
            .forEach { candidate ->
                if (totalSize <= maxTotalLogSize) {
                    return
                }

                val size = fileSystem.length(candidate)
                deleteOrThrow(candidate)
                totalSize -= size
            }
    }

    private fun deleteOrThrow(file: File) {
        if (fileSystem.exists(file) && !fileSystem.delete(file)) {
            throw IOException("Failed to delete log file: ${file.absolutePath}")
        }
    }

    private fun renameOrThrow(
        source: File,
        target: File
    ) {
        if (!fileSystem.rename(source, target)) {
            throw IOException("Failed to rename log file from ${source.absolutePath} to ${target.absolutePath}")
        }
    }

    private fun logFiles(file: File): List<File> {
        return file.parentFile
            ?.let(fileSystem::listFiles)
            .orEmpty()
            .filter { candidate ->
                candidate.isFile &&
                    (candidate.name == file.name || candidate.name.startsWith("${file.name}."))
            }
    }
}
