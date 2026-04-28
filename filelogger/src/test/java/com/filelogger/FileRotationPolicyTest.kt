package com.filelogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class FileRotationPolicyTest {

    @Test
    fun `rotation shifts existing backups`() {
        val directory = createTempDirectory("filelogger-rotation").toFile()
        val mainFile = File(directory, "app.log").apply { writeText("current") }
        File(directory, "app.log.1").writeText("older-1")
        File(directory, "app.log.2").writeText("older-2")
        val policy = FileRotationPolicy(
            maxFileSize = 1,
            maxBackupFiles = 2
        )

        policy.rotateIfNeeded(mainFile, ByteArray(10))

        assertFalse(mainFile.exists())
        assertEquals("current", File(directory, "app.log.1").readText())
        assertEquals("older-1", File(directory, "app.log.2").readText())
    }

    @Test
    fun `rotation deletes active file when backup count is zero`() {
        val directory = createTempDirectory("filelogger-rotation").toFile()
        val mainFile = File(directory, "app.log").apply { writeText("current") }
        val policy = FileRotationPolicy(
            maxFileSize = 1,
            maxBackupFiles = 0
        )

        policy.rotateIfNeeded(mainFile, ByteArray(10))

        assertFalse(mainFile.exists())
        assertFalse(File(directory, "app.log.1").exists())
    }

    @Test
    fun `rotation is skipped when projected size fits`() {
        val directory = createTempDirectory("filelogger-rotation").toFile()
        val mainFile = File(directory, "app.log").apply { writeText("1234") }
        val policy = FileRotationPolicy(
            maxFileSize = 10,
            maxBackupFiles = 2
        )

        policy.rotateIfNeeded(mainFile, "12".toByteArray())

        assertTrue(mainFile.exists())
        assertEquals("1234", mainFile.readText())
        assertFalse(File(directory, "app.log.1").exists())
    }
}
