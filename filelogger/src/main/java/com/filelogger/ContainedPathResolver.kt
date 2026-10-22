package com.filelogger

import java.io.File

internal object ContainedPathResolver {
    private val windowsDrivePath = Regex("^[A-Za-z]:.*")

    fun validateRelativePath(
        value: String,
        fieldName: String,
        allowNested: Boolean
    ) {
        require(value.isNotBlank()) { "$fieldName must not be blank" }
        val normalized = value.replace('\\', '/')
        require(!File(value).isAbsolute && !windowsDrivePath.matches(value) && !normalized.startsWith('/')) {
            "$fieldName must be a relative path"
        }
        val segments = normalized.split('/')
        require(segments.none { segment -> segment == ".." }) {
            "$fieldName must not contain parent traversal"
        }
        require(segments.none { segment -> segment == "." }) {
            "$fieldName must not contain current-directory segments"
        }
        if (!allowNested) {
            require(segments.size == 1) { "$fieldName must not contain path separators" }
        }
    }

    fun resolve(
        root: File,
        relativePath: String,
        fieldName: String,
        allowNested: Boolean,
        canonicalize: (File) -> File = { file -> file.canonicalFile }
    ): File {
        validateRelativePath(relativePath, fieldName, allowNested)
        val resolved = File(root, relativePath)
        val canonicalRoot = canonicalize(root)
        val candidate = canonicalize(resolved)
        val rootPath = canonicalRoot.path.trimEnd(File.separatorChar)
        val candidatePath = candidate.path
        require(candidatePath == rootPath || candidatePath.startsWith("$rootPath${File.separator}")) {
            "$fieldName must stay inside ${canonicalRoot.path}"
        }
        return resolved
    }
}
