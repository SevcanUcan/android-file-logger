package com.filelogger

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object LogExporter {

    fun export(
        logDirectory: File,
        outputFile: File,
        baseLogFileName: String,
        redactor: LogRedactor = LogRedactor.NONE,
        metadataEntries: Map<String, String> = emptyMap()
    ): File {
        outputFile.parentFile?.mkdirs()

        ZipOutputStream(outputFile.outputStream()).use { zip ->
            logDirectory
                .listFiles()
                .orEmpty()
                .filter { file -> file.isFile && file != outputFile }
                .filter { file -> isLogFile(file.name, baseLogFileName) }
                .sortedBy { file -> file.name }
                .forEach { file ->
                    zip.putNextEntry(ZipEntry(file.name))
                    writeRedactedFile(file, zip, redactor)
                    zip.closeEntry()
                }

            metadataEntries
                .toSortedMap()
                .forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
        }

        return outputFile
    }

    private fun isLogFile(
        fileName: String,
        baseLogFileName: String
    ): Boolean {
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
