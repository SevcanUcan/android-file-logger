package com.filelogger

import java.io.File
import java.io.FileOutputStream

internal interface LogFileSystem {
    fun createDirectories(directory: File): Boolean
    fun append(file: File, bytes: ByteArray)
    fun sync(file: File)
    fun exists(file: File): Boolean
    fun length(file: File): Long
    fun lastModified(file: File): Long
    fun delete(file: File): Boolean
    fun rename(source: File, target: File): Boolean
    fun listFiles(directory: File): List<File>
}

internal object SystemLogFileSystem : LogFileSystem {
    override fun createDirectories(directory: File): Boolean =
        directory.exists() || directory.mkdirs()

    override fun append(file: File, bytes: ByteArray) {
        FileOutputStream(file, true).use { stream -> stream.write(bytes) }
    }

    override fun sync(file: File) {
        FileOutputStream(file, true).use { stream -> stream.fd.sync() }
    }

    override fun exists(file: File): Boolean = file.exists()

    override fun length(file: File): Long = file.length()

    override fun lastModified(file: File): Long = file.lastModified()

    override fun delete(file: File): Boolean = file.delete()

    override fun rename(source: File, target: File): Boolean = source.renameTo(target)

    override fun listFiles(directory: File): List<File> =
        directory.listFiles()?.toList().orEmpty()
}
