package com.filelogger

import java.io.File
import java.io.IOException

internal class GlobalLogRetentionPolicy(
    private val root: File,
    private val activeDirectory: File,
    private val baseLogFileName: String,
    private val sessionLoggingEnabled: Boolean,
    private val sessionFolderPrefix: String,
    private val maxTotalLogSize: Long,
    private val maxLogAgeMillis: Long,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis
) {
    private val canonicalRoot = root.canonicalFile
    private val canonicalActiveDirectory = activeDirectory.canonicalFile

    fun enforce(activeFile: File) {
        val canonicalActiveFile = requireContained(activeFile)
        require(canonicalActiveFile.parentFile == canonicalActiveDirectory) {
            "Active log file must belong to the active log directory"
        }

        val files = eligibleFiles()
        if (maxLogAgeMillis != Long.MAX_VALUE) {
            val threshold = currentTimeMillis() - maxLogAgeMillis
            files.asSequence()
                .filterNot(::isActiveSessionFile)
                .filter { it.lastModified() < threshold }
                .sortedWith(retentionOrder())
                .forEach(::deleteOrThrow)
        }

        val retained = eligibleFiles()
        var totalSize = retained.sumOf(File::length)
        if (maxTotalLogSize != Long.MAX_VALUE && totalSize > maxTotalLogSize) {
            retained.asSequence()
                .filterNot(::isActiveSessionFile)
                .sortedWith(retentionOrder())
                .forEach { candidate ->
                    if (totalSize > maxTotalLogSize) {
                        val size = candidate.length()
                        deleteOrThrow(candidate)
                        totalSize -= size
                    }
                }
        }

        removeEmptyInactiveSessionDirectories()
        if (maxTotalLogSize != Long.MAX_VALUE && totalSize > maxTotalLogSize) {
            throw IOException(
                "Global log retention limit cannot be satisfied without deleting active session data: " +
                    "$totalSize bytes retained, limit is $maxTotalLogSize bytes"
            )
        }
    }

    private fun eligibleFiles(): List<File> {
        val directories = if (sessionLoggingEnabled) eligibleSessionDirectories() else listOf(canonicalRoot)
        return directories.flatMap { directory ->
            listChildren(directory)
                .filter(File::isFile)
                .mapNotNull { candidate ->
                    val canonical = runCatching { requireContained(candidate) }.getOrNull() ?: return@mapNotNull null
                    canonical.takeIf { LogFileNames.isLogFile(it.name, baseLogFileName) }
                }
        }
    }

    private fun eligibleSessionDirectories(): List<File> = listChildren(canonicalRoot)
        .filter(File::isDirectory)
        .filter { it.name.startsWith("${sessionFolderPrefix}_") }
        .mapNotNull { candidate ->
            val canonical = runCatching { requireContained(candidate) }.getOrNull() ?: return@mapNotNull null
            canonical.takeIf { it == candidate.absoluteFile.normalize() }
        }

    private fun isActiveSessionFile(file: File): Boolean =
        file.parentFile?.canonicalFile == canonicalActiveDirectory ||
            file.parentFile?.let(SessionLease::hasLiveLease) == true

    private fun retentionOrder(): Comparator<File> =
        compareBy<File> { it.lastModified() }
            .thenBy { it.relativeTo(canonicalRoot).invariantSeparatorsPath }

    private fun removeEmptyInactiveSessionDirectories() {
        if (!sessionLoggingEnabled) return
        eligibleSessionDirectories()
            .filter { it != canonicalActiveDirectory }
            .filterNot(SessionLease::hasLiveLease)
            .sortedByDescending { it.path.length }
            .forEach { directory ->
                val children = listChildren(directory)
                if (children.isEmpty() && !directory.delete() && directory.exists()) {
                    throw IOException("Failed to delete empty session directory: ${directory.absolutePath}")
                }
            }
    }

    private fun deleteOrThrow(file: File) {
        requireContained(file)
        if (!file.delete() && file.exists()) {
            throw IOException("Failed to delete retained log file: ${file.absolutePath}")
        }
    }

    private fun listChildren(directory: File): List<File> {
        val files = directory.listFiles()
        if (files == null && directory.exists()) {
            throw IOException("Failed to list log directory: ${directory.absolutePath}")
        }
        return files?.toList().orEmpty()
    }

    private fun requireContained(file: File): File {
        val canonical = file.canonicalFile
        val rootPath = canonicalRoot.path.trimEnd(File.separatorChar)
        val candidatePath = canonical.path
        if (candidatePath != rootPath && !candidatePath.startsWith("$rootPath${File.separator}")) {
            throw IOException("Log retention candidate escapes configured root: ${file.absolutePath}")
        }
        return canonical
    }
}
