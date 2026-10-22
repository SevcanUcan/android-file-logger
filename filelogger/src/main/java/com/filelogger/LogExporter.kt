package com.filelogger

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal object LogExporter {

    fun export(
        logDirectory: File,
        outputFile: File,
        baseLogFileName: String,
        redactor: LogRedactor = LogRedactor.DEFAULT_SENSITIVE,
        metadataEntries: Map<String, String> = emptyMap(),
        includeLogs: Boolean = true,
        storageCoordinator: LogStorageCoordinator? = null
    ): File {
        return storageCoordinator?.withExclusiveAccess {
            exportLocked(
                logDirectory,
                outputFile,
                baseLogFileName,
                redactor,
                metadataEntries,
                includeLogs,
                cleanInterruptedTemps = true
            )
        } ?: exportLocked(
            logDirectory,
            outputFile,
            baseLogFileName,
            redactor,
            metadataEntries,
            includeLogs,
            cleanInterruptedTemps = false
        )
    }

    private fun exportLocked(
        logDirectory: File,
        outputFile: File,
        baseLogFileName: String,
        redactor: LogRedactor,
        metadataEntries: Map<String, String>,
        includeLogs: Boolean,
        cleanInterruptedTemps: Boolean
    ): File {
        val parent = outputFile.parentFile ?: throw IOException("Export output requires a parent directory")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create export directory: ${parent.absolutePath}")
        }
        if (cleanInterruptedTemps) removeInterruptedTemps(parent, outputFile.name)
        val backup = File(parent, ".${outputFile.name}.filelogger-backup")
        recoverInterruptedReplacement(outputFile, backup)
        val temp = File.createTempFile(".${outputFile.name.take(32)}-", ".tmp", parent)
        try {
            writeArchive(temp, logDirectory, outputFile, baseLogFileName, redactor, metadataEntries, includeLogs)
            validateArchive(temp)
            commitArchive(temp, outputFile, backup)
            return outputFile
        } finally {
            temp.delete()
        }
    }

    private fun removeInterruptedTemps(parent: File, outputName: String) {
        val prefix = ".${outputName.take(32)}-"
        parent.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".tmp") }
            .forEach { temp ->
                if (!temp.delete()) {
                    throw IOException("Failed to remove interrupted export temp: ${temp.absolutePath}")
                }
            }
    }

    private fun writeArchive(
        temp: File,
        logDirectory: File,
        outputFile: File,
        baseLogFileName: String,
        redactor: LogRedactor,
        metadataEntries: Map<String, String>,
        includeLogs: Boolean
    ) {
        FileOutputStream(temp).use { fileOutput ->
            val zip = ZipOutputStream(fileOutput)
            try {
            if (includeLogs) {
                logDirectory
                    .listFiles()
                    .orEmpty()
                    .filter { file -> file.isFile && file != outputFile }
                    .filter { file -> LogFileNames.isLogFile(file.name, baseLogFileName) }
                    .sortedBy { file -> file.name }
                    .forEach { file ->
                        zip.putNextEntry(ZipEntry(file.name))
                        writeRedactedFile(file, zip, redactor)
                        zip.closeEntry()
                    }
            }

            metadataEntries
                .toSortedMap()
                .forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
                zip.finish()
                zip.flush()
                fileOutput.fd.sync()
            } finally {
                zip.close()
            }
        }
    }

    private fun validateArchive(file: File) {
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val crc = CRC32()
                val size = zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        crc.update(buffer, 0, read)
                        total += read
                    }
                    total
                }
                if (entry.crc != crc.value || entry.size != size) {
                    throw IOException("Export ZIP validation failed for entry: ${entry.name}")
                }
            }
        }
    }

    private fun commitArchive(temp: File, output: File, backup: File) {
        if (backup.exists() && !backup.delete()) {
            throw IOException("Failed to clear stale export backup: ${backup.absolutePath}")
        }
        val hadPrevious = output.exists()
        if (hadPrevious && !output.renameTo(backup)) {
            throw IOException("Failed to preserve existing export before replacement")
        }
        if (!temp.renameTo(output)) {
            if (hadPrevious && !backup.renameTo(output)) {
                throw IOException("Export commit failed and the previous export could not be restored")
            }
            throw IOException("Failed to commit completed export: ${output.absolutePath}")
        }
        backup.delete()
    }

    private fun recoverInterruptedReplacement(output: File, backup: File) {
        if (!backup.exists()) return
        if (output.exists()) {
            backup.delete()
        } else if (!backup.renameTo(output)) {
            throw IOException("Failed to recover the previous export")
        }
    }

    private fun writeRedactedFile(
        file: File,
        zip: ZipOutputStream,
        redactor: LogRedactor
    ) {
        file.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                zip.write(redactor.redact(line).toByteArray())
                zip.write("\n".toByteArray())
            }
        }
    }
}
