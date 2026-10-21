package com.filelogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

class PathContainmentRegressionTest {

    @Test
    fun `log folder rejects parent traversal with platform separator`() {
        val traversal = "..${File.separator}escaped"

        assertThrows(IllegalArgumentException::class.java) {
            LoggerConfig(logFolder = traversal)
        }
    }

    @Test
    fun `log folder rejects windows separator traversal`() {
        assertThrows(IllegalArgumentException::class.java) {
            LoggerConfig(logFolder = "..\\escaped")
        }
    }

    @Test
    fun `log folder rejects windows rooted paths`() {
        assertThrows(IllegalArgumentException::class.java) {
            LoggerConfig(logFolder = "\\outside")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LoggerConfig(logFolder = "C:drive-relative")
        }
    }

    @Test
    fun `log folder rejects absolute paths`() {
        val absolute = createTempDirectory("filelogger-absolute").toFile().absolutePath

        assertThrows(IllegalArgumentException::class.java) {
            LoggerConfig(logFolder = absolute)
        }
    }

    @Test
    fun `log file and session values reject traversal`() {
        assertThrows(IllegalArgumentException::class.java) {
            LoggerConfig(logFileName = "../outside.log")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LoggerConfig(sessionId = "../outside")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LoggerConfig(sessionFolderPrefix = "../session")
        }
    }

    @Test
    fun `nested relative folder and ordinary names remain valid`() {
        val filesDir = createTempDirectory("filelogger-contained").toFile()
        val config = LoggerConfig(
            logFolder = "company${File.separator}logs",
            logFileName = "uygulama log-1.0.json",
            sessionId = "oturum 1.0-beta",
            sessionFolderPrefix = "session"
        )
        val session = LogSessionFactory.create(config, currentTimeMillis = 42L)

        val resolved = LogDirectoryResolver.resolve(filesDir, config, session)

        assertEquals(
            File(filesDir, "company${File.separator}logs${File.separator}session_oturum 1.0-beta")
                .canonicalFile,
            resolved.canonicalFile
        )
    }

    @Test
    fun `sibling directory with common path prefix is rejected`() {
        val parent = createTempDirectory("filelogger-prefix").toFile()
        val filesDir = File(parent, "files").apply { mkdirs() }

        assertThrows(IllegalArgumentException::class.java) {
            LogDirectoryResolver.resolve(
                filesDir = filesDir,
                config = LoggerConfig(logFolder = "../files-sibling"),
                session = null
            )
        }
    }

    @Test
    fun `symlinked log folder cannot escape files directory`() {
        val parent = createTempDirectory("filelogger-symlink").toFile()
        val filesDir = File(parent, "files").apply { mkdirs() }
        val outside = File(parent, "outside").apply { mkdirs() }
        val link = File(filesDir, "logs-link")
        createDirectoryLink(link, outside)

        val error = if (System.getProperty("os.name").orEmpty()
                .startsWith("Windows", ignoreCase = true)
        ) {
            val realLinkTarget = link.toPath().toRealPath().toFile()
            assertThrows(IllegalArgumentException::class.java) {
                ContainedPathResolver.resolve(
                    root = filesDir,
                    relativePath = link.name,
                    fieldName = "logFolder",
                    allowNested = true,
                    canonicalize = { file ->
                        if (file.absoluteFile == link.absoluteFile) {
                            realLinkTarget
                        } else {
                            file.canonicalFile
                        }
                    }
                )
            }
        } else {
            assertThrows(IllegalArgumentException::class.java) {
                LogDirectoryResolver.resolve(
                    filesDir = filesDir,
                    config = LoggerConfig(logFolder = link.name),
                    session = null
                )
            }
        }

        assertTrue(error.message.orEmpty().contains("must stay inside"))
    }

    private fun createDirectoryLink(link: File, target: File) {
        if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
            val process = ProcessBuilder(
                "cmd.exe",
                "/c",
                "mklink",
                "/J",
                link.absolutePath,
                target.absolutePath
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals("Failed to create directory junction: $output", 0, process.waitFor())
        } else {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        }
    }
}
