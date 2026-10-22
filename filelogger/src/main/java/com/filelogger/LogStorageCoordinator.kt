package com.filelogger

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

internal class LogStorageCoordinator(root: File) {
    val root: File = root.canonicalFile
    private val processLock = processLocks.computeIfAbsent(this.root.path) { ReentrantLock() }

    fun <T> withExclusiveAccess(block: () -> T): T {
        processLock.lock()
        try {
            if (!root.exists() && !root.mkdirs()) {
                throw IOException("Failed to create log root: ${root.absolutePath}")
            }
            if (!root.isDirectory) {
                throw IOException("Log root is not a directory: ${root.absolutePath}")
            }

            val lockDirectory = ContainedPathResolver.resolve(
                root = root,
                relativePath = LOCK_DIRECTORY_NAME,
                fieldName = "storage metadata directory",
                allowNested = false
            )
            if (!lockDirectory.exists() && !lockDirectory.mkdirs()) {
                throw IOException("Failed to create storage metadata directory: ${lockDirectory.absolutePath}")
            }
            val lockFile = ContainedPathResolver.resolve(
                root = lockDirectory,
                relativePath = LOCK_FILE_NAME,
                fieldName = "storage lock",
                allowNested = false
            )
            return try {
                RandomAccessFile(lockFile, "rw").use { randomAccess ->
                    randomAccess.channel.use { channel ->
                        channel.lock().use { block() }
                    }
                }
            } catch (error: OverlappingFileLockException) {
                throw IOException("Log storage lock is already held in this process", error)
            }
        } finally {
            processLock.unlock()
        }
    }

    private companion object {
        const val LOCK_DIRECTORY_NAME = ".filelogger"
        const val LOCK_FILE_NAME = "storage.lock"
        val processLocks = ConcurrentHashMap<String, ReentrantLock>()
    }
}
