package com.filelogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory

class GlobalSessionRetentionRegressionTest {

    @Test
    fun `global size limit removes old session data`() {
        val root = createTempDirectory("filelogger-global-size").toFile()
        val active = session(root, "session_active", 3_000, "active")
        val oldOne = session(root, "session_old_1", 1_000, "a".repeat(1_500))
        val oldTwo = session(root, "session_old_2", 2_000, "b".repeat(1_500))
        val destination = destination(active, maxTotalSize = 1_000)

        destination.write(testRecord(message = "trigger retention"))
        assertTrue((destination as FlushableLogDestination).flush(2_000))

        assertTrue("The active session must be retained", active.exists())
        assertFalse("The oldest session should be removed first", oldOne.exists())
        assertFalse("Global size retention must include sibling sessions", oldTwo.exists())
        close(destination)
    }

    @Test
    fun `age retention removes expired files across sessions`() {
        val root = createTempDirectory("filelogger-global-age").toFile()
        val now = System.currentTimeMillis()
        val active = session(root, "session_active", now, "active")
        val expired = session(root, "session_expired", now - 5_000, "expired")
        val recent = session(root, "session_recent", now - 500, "recent")
        val destination = destination(
            activeDirectory = active,
            maxTotalSize = Long.MAX_VALUE,
            maxAgeMillis = 2_000
        )

        destination.write(testRecord(message = "trigger retention"))
        assertTrue((destination as FlushableLogDestination).flush(2_000))

        assertFalse("Expired sibling session data must be removed", expired.exists())
        assertTrue("Recent sibling session data must be preserved", recent.exists())
        assertTrue("Active session must never be deleted", active.exists())
        close(destination)
    }

    @Test
    fun `global retention uses deterministic oldest first ordering`() {
        val root = createTempDirectory("filelogger-global-order").toFile()
        val active = session(root, "session_active", 4_000, "active")
        val oldest = session(root, "session_oldest", 1_000, "a".repeat(900))
        val newer = session(root, "session_newer", 2_000, "b".repeat(900))
        val destination = destination(active, maxTotalSize = 1_200)

        destination.write(testRecord(message = "x"))
        assertTrue((destination as FlushableLogDestination).flush(2_000))

        assertFalse("Oldest eligible session should be removed before newer data", oldest.exists())
        assertTrue("Newer session should remain once the limit is satisfied", newer.exists())
        assertTrue(active.exists())
        close(destination)
    }

    @Test
    fun `retention cannot delete a sibling outside the configured root`() {
        val parent = createTempDirectory("filelogger-containment").toFile()
        val root = File(parent, "logs").apply { mkdirs() }
        val outside = session(parent, "session_outside", 1_000, "private")
        val active = session(root, "session_active", 3_000, "active")
        session(root, "session_old", 1_000, "old".repeat(500))
        val destination = destination(active, maxTotalSize = 32)

        destination.write(testRecord(message = "trigger"))
        (destination as FlushableLogDestination).flush(2_000)

        assertTrue("Retention must remain contained by the configured root", outside.exists())
        assertEquals("private", File(outside, "app.log").readText())
        close(destination)
    }

    @Test
    fun `retention tolerates invalid entries without touching active data`() {
        val root = createTempDirectory("filelogger-invalid-entry").toFile()
        val active = session(root, "session_active", 3_000, "active")
        File(root, "session_not_a_directory").writeText("invalid")
        val destination = destination(active, maxTotalSize = Long.MAX_VALUE)

        destination.write(testRecord(message = "trigger"))
        assertTrue((destination as FlushableLogDestination).flush(2_000))

        assertTrue(active.exists())
        assertTrue(File(root, "session_not_a_directory").isFile)
        close(destination)
    }

    @Test
    fun `active session over the global limit is reported instead of deleted`() {
        val root = createTempDirectory("filelogger-active-over-limit").toFile()
        val active = session(root, "session_active", 3_000, "active-data")

        val failure = runCatching { destination(active, maxTotalSize = 1) }.exceptionOrNull()

        assertTrue(failure is java.io.IOException)
        assertTrue(failure?.message.orEmpty().contains("cannot be satisfied"))
        assertTrue(active.exists())
        assertEquals("active-data", File(active, "app.log").readText())
    }

    @Test
    fun `global retention never treats remote spool files as log candidates`() {
        val root = createTempDirectory("filelogger-retention-remote").toFile()
        val active = session(root, "session_active", 3_000, "active")
        val oldSession = session(root, "session_old", 1_000, "old".repeat(500))
        val spool = File(oldSession, "remote-spool").apply { mkdirs() }
        val batch = File(spool, "batch_legacy.jsonl.gz").apply { writeBytes(ByteArray(256) { 7 }) }
        val destination = destination(active, maxTotalSize = 500)

        destination.write(testRecord(message = "trigger"))
        assertTrue((destination as FlushableLogDestination).flush(2_000))

        assertTrue("Remote spool directory must remain outside retention ownership", spool.exists())
        assertTrue("Remote spool batch must never be deleted by log retention", batch.exists())
        close(destination)
    }

    @Test
    fun `retention skips a session link that resolves outside the log root`() {
        val parent = createTempDirectory("filelogger-retention-link").toFile()
        val root = File(parent, "logs").apply { mkdirs() }
        val outside = session(parent, "outside", 1_000, "outside-data")
        val link = File(root, "session_link")
        createDirectoryLink(link, outside)
        val active = session(root, "session_active", 3_000, "active")
        val destination = destination(active, maxTotalSize = 500)

        destination.write(testRecord(message = "trigger"))
        assertTrue((destination as FlushableLogDestination).flush(2_000))

        assertTrue(outside.exists())
        assertEquals("outside-data", File(outside, "app.log").readText())
        close(destination)
    }

    @Test
    fun `retention remains contained while writes and exports overlap`() {
        val root = createTempDirectory("filelogger-retention-export").toFile()
        val active = session(root, "session_active", 3_000, "active")
        val expired = session(root, "session_expired", 1_000, "expired".repeat(300))
        val coordinator = LogStorageCoordinator(root)
        val destination = destination(
            active,
            maxTotalSize = 2_000,
            coordinator = coordinator,
            maxFileSize = 256,
            maxBackupFiles = 3
        )
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        executor.execute {
            runCatching {
                start.await()
                repeat(100) { destination.write(testRecord(message = "write-$it")) }
                (destination as FlushableLogDestination).flush(5_000)
            }.onFailure(failures::add)
        }
        executor.execute {
            runCatching {
                start.await()
                repeat(20) { index ->
                    LogExporter.export(
                        logDirectory = active,
                        outputFile = File(root, "export-$index.zip"),
                        baseLogFileName = "app.log",
                        storageCoordinator = coordinator
                    )
                }
            }.onFailure(failures::add)
        }

        start.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        assertTrue("Concurrent retention/export must not throw: $failures", failures.isEmpty())
        repeat(20) { index ->
            ZipFile(File(root, "export-$index.zip")).use { zip ->
                val entries = zip.entries().asSequence().toList()
                assertTrue("Every concurrent export must contain a complete log entry", entries.isNotEmpty())
                entries.forEach { entry ->
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    val crc = CRC32().apply { update(bytes) }.value
                    assertEquals("Concurrent export CRC must match", entry.crc, crc)
                    assertEquals(entry.size, bytes.size.toLong())
                }
            }
        }
        assertFalse("Expired sibling session must still be globally retained", expired.exists())
        assertTrue(active.exists())
        assertTrue(active.listFiles().orEmpty().count { LogFileNames.isLogFile(it.name, "app.log") } <= 4)
        close(destination)
    }

    @Test
    fun `retention does not delete a different active writer session`() {
        val root = createTempDirectory("filelogger-two-active-sessions").toFile()
        val foreignActive = session(root, "session_process_b", 1_000, "process-b-active")
        val localActive = session(root, "session_process_a", 2_000, "process-a-active")
        val inactive = session(root, "session_inactive", 500, "x".repeat(2_000))
        val coordinator = LogStorageCoordinator(root)
        val foreignWriter = destination(
            activeDirectory = foreignActive,
            maxTotalSize = Long.MAX_VALUE,
            coordinator = coordinator
        )

        foreignWriter.write(testRecord(message = "process-b-still-writing"))
        assertTrue((foreignWriter as FlushableLogDestination).flush(2_000))

        val localWriter = destination(
            activeDirectory = localActive,
            maxTotalSize = 1_000,
            coordinator = coordinator
        )

        try {
            assertTrue(
                "Retention from process A must not delete process B's live session",
                File(foreignActive, "app.log").exists()
            )
            assertTrue("The local active session must also remain", File(localActive, "app.log").exists())
            assertFalse("Retention should reclaim inactive data first", inactive.exists())
        } finally {
            close(localWriter)
            close(foreignWriter)
        }
    }

    @Test
    fun `stale lease left by a dead process does not preserve an inactive session forever`() {
        val root = createTempDirectory("filelogger-stale-session-lease").toFile()
        val stale = session(root, "session_dead_process", 500, "stale".repeat(500))
        File(stale, ".filelogger-active-dead-process.lease").writeText("stale")
        val active = session(root, "session_active", 2_000, "active")

        val destination = destination(active, maxTotalSize = 1_000)

        assertFalse("An unlockable stale lease must be reclaimed", stale.exists())
        close(destination)
    }

    private fun destination(
        activeDirectory: File,
        maxTotalSize: Long,
        maxAgeMillis: Long = Long.MAX_VALUE,
        coordinator: LogStorageCoordinator? = null,
        maxFileSize: Long = Long.MAX_VALUE,
        maxBackupFiles: Int = 0
    ): LogDestination {
        val config = LoggerConfig(
            maxFileSize = maxFileSize,
            maxBackupFiles = maxBackupFiles,
            maxTotalLogSize = maxTotalSize,
            maxLogAgeMillis = maxAgeMillis,
            logFileName = "app.log",
            useProcessSpecificLogFiles = false,
            sessionLoggingEnabled = true,
            crashCaptureEnabled = false,
            customDestinations = emptyList()
        )
        return LoggerEngine.createDefaultDestination(
            logDirectoryProvider = { activeDirectory },
            configProvider = { config },
            packageNameProvider = { "com.filelogger.test" },
            processNameProvider = { "com.filelogger.test" },
            storageCoordinator = coordinator
        )
    }

    private fun session(root: File, name: String, modified: Long, content: String): File {
        return File(root, name).apply {
            mkdirs()
            File(this, "app.log").apply {
                writeText(content)
                setLastModified(modified)
            }
            setLastModified(modified)
        }
    }

    private fun close(destination: LogDestination) {
        (destination as? CloseableLogDestination)?.close(2_000)
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
