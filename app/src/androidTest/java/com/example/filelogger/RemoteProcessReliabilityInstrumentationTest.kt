package com.example.filelogger

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filelogger.FileLogger
import com.filelogger.LogRedactor
import com.filelogger.LoggerConfig
import com.filelogger.remote.IdentifiedRemoteLogUploader
import com.filelogger.remote.RemoteLogConfig
import com.filelogger.remote.RemoteLogDestination
import com.filelogger.remote.RemoteLogUploader
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class RemoteProcessReliabilityInstrumentationTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private var activeFolder: String? = null
    private val destinations = mutableListOf<RemoteLogDestination>()

    @After
    fun tearDown() {
        destinations.forEach { runCatching { it.close(1_000) } }
        destinations.clear()
        runCatching { FileLogger.shutdown(1_000) }
        activeFolder?.let { folder ->
            runCatching { context.stopService(serviceIntent(RemoteProcessTestService.ACTION_REMOTE_STOP, folder)) }
            File(context.filesDir, folder).deleteRecursively()
        }
        activeFolder = null
    }

    @Test
    fun oneBatchHasOnlyOneUploaderOwnerAcrossAndroidProcesses() {
        val root = newRoot("remote_owner")
        val remotePid = startRemote(RemoteProcessTestService.ACTION_REMOTE_BLOCKING_UPLOAD, root)
        assertTrue(remotePid > 0)
        assertTrue(waitUntil { uploadEvents(root).size == 1 && claimFiles(root).size == 1 })
        val mainUploads = AtomicInteger()
        val main = destination(root, maxPending = 8) { mainUploads.incrementAndGet(); true }

        assertFalse("A foreign active claim must make delivery flush fail", main.flush(750))
        assertEquals(0, mainUploads.get())
        assertEquals(1, uploadEvents(root).size)

        File(root, RemoteProcessTestService.RELEASE_FILE).writeText("release")
        assertTrue(waitUntil { claimFiles(root).isEmpty() && committedFiles(root).isEmpty() })
        assertEquals("The batch must have exactly one upload owner", 1, uploadEvents(root).size)
    }

    @Test
    fun consentRevokeStopsRetryAndNewClaimInEveryProcess() {
        val root = newRoot("remote_consent")
        startRemote(RemoteProcessTestService.ACTION_REMOTE_FAIL_WITH_BACKOFF, root)
        assertTrue(waitUntil { uploadEvents(root).size == 1 })
        val controller = destination(root, maxPending = 8) { true }

        assertTrue(controller.setCollectionEnabled(false, erasePendingData = false, timeoutMillis = 2_000))
        Thread.sleep(6_000)

        assertEquals("The foreign process must not start a retry after durable revoke", 1, uploadEvents(root).size)
        assertTrue(File(root, ".filelogger-remote-paused").exists())
        assertTrue("The unacknowledged batch must remain durable", committedFiles(root).size == 1)
        assertTrue(claimFiles(root).isEmpty())
    }

    @Test
    fun destinationCreatedWhilePausedResumesAfterAnotherProcessReenablesConsent() {
        val root = newRoot("remote_consent_resume")
        val controller = destination(root, maxPending = 8) { true }
        assertTrue(controller.setCollectionEnabled(false, erasePendingData = false, timeoutMillis = 2_000))
        startRemote(RemoteProcessTestService.ACTION_REMOTE_WAIT_FOR_RESUME, root)

        assertTrue(File(root, ".filelogger-remote-paused").exists())
        assertTrue("Paused secondary process must not upload", uploadEvents(root).isEmpty())
        assertTrue(controller.setCollectionEnabled(true, erasePendingData = false, timeoutMillis = 2_000))
        File(root, RemoteProcessTestService.START_FILE).writeText("resume")

        assertTrue(waitUntil { File(root, RemoteProcessTestService.DONE_FILE).exists() })
        assertEquals("true", File(root, RemoteProcessTestService.DONE_FILE).readText())
        assertEquals("Re-enabled consent must permit exactly one upload", 1, uploadEvents(root).size)
        assertFalse(File(root, ".filelogger-remote-paused").exists())
        assertTrue(committedFiles(root).isEmpty())
        assertTrue(claimFiles(root).isEmpty())
    }

    @Test
    fun eraseDoesNotReportSuccessWhileAnotherProcessOwnsClaim() {
        val root = newRoot("remote_erase")
        startRemote(RemoteProcessTestService.ACTION_REMOTE_BLOCKING_UPLOAD, root)
        assertTrue(waitUntil { claimFiles(root).size == 1 })
        val controller = destination(root, maxPending = 8) { true }

        val erased = controller.setCollectionEnabled(false, erasePendingData = true, timeoutMillis = 300)

        assertFalse("Erase must not succeed while a foreign process owns the claim", erased)
        assertEquals(1, claimFiles(root).size)
        File(root, RemoteProcessTestService.RELEASE_FILE).writeText("release")
        assertTrue(waitUntil { claimFiles(root).isEmpty() })
    }

    @Test
    fun processDeathRecoversStaleClaimWithStableBatchId() {
        val root = newRoot("remote_stale")
        val pid = startRemote(RemoteProcessTestService.ACTION_REMOTE_BLOCKING_UPLOAD, root)
        assertTrue(waitUntil { uploadEvents(root).size == 1 && claimFiles(root).size == 1 })
        val originalBatchId = uploadEvents(root).single().readText()

        context.startService(serviceIntent(RemoteProcessTestService.ACTION_PROCESS_KILL, root.name))
        assertTrue("Secondary Android process did not die", waitUntil { !isProcessAlive(pid) })
        val recoveredBatchId = AtomicReference<String>()
        val recovered = identifiedDestination(root, maxPending = 8) { context ->
            recoveredBatchId.set(context.batchId)
            true
        }

        assertTrue(
            "The stale claim must become recoverable after the kernel releases the dead process lock",
            waitUntil(5_000) { recovered.flush(750) }
        )
        assertEquals(originalBatchId, recoveredBatchId.get())
        assertTrue(claimFiles(root).isEmpty())
        assertTrue(committedFiles(root).isEmpty())
    }

    @Test
    fun sharedQuotaIsAtomicAcrossAndroidProcessesWithoutDeadlock() {
        val root = newRoot("remote_quota_process")
        startRemote(RemoteProcessTestService.ACTION_REMOTE_QUOTA_RACE, root)
        val main = destination(root, maxPending = 1) { false }
        val start = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            start.await()
            main.write(testRecord("main-quota"))
            main.flush(5_000)
        }

        File(root, RemoteProcessTestService.START_FILE).writeText("start")
        start.countDown()
        executor.shutdown()
        assertTrue("Cross-process quota operations deadlocked", executor.awaitTermination(10, TimeUnit.SECONDS))
        assertTrue(waitUntil { File(root, RemoteProcessTestService.DONE_FILE).exists() })

        assertEquals(
            "Two Android processes must not exceed the one-batch durable quota",
            1,
            committedFiles(root).size + claimFiles(root).size
        )
    }

    @Test
    fun processKillDuringZipWriteLeavesNoPartialFinalAndRecoveryCleansTemps() {
        val root = newRoot("export_kill_write")
        val output = File(File(root, "exports").apply { mkdirs() }, "support.zip")
        writeKnownArchive(output, "old.jsonl", "{\"old\":true}\n")
        val pid = startExport(RemoteProcessTestService.ACTION_EXPORT_KILL_DURING_WRITE, root, output.name)

        assertTrue(waitUntil(45_000) { File(root, RemoteProcessTestService.KILL_TRIGGERED_FILE).exists() })
        assertTrue(waitUntil { !isProcessAlive(pid) })
        assertValidArchive(output)
        assertTrue("The test must prove a crashed in-progress temp existed", exportTemps(output).isNotEmpty())

        recoverExport(root, output)

        assertValidArchive(output, requireNdjson = true)
        assertTrue("Recovery must remove interrupted export temps", exportTemps(output).isEmpty())
    }

    @Test
    fun processKillAtCommitBoundaryKeepsOldOrNewArchiveComplete() {
        val root = newRoot("export_kill_commit")
        val output = File(File(root, "exports").apply { mkdirs() }, "support.zip")
        writeKnownArchive(output, "old.jsonl", "{\"old\":true}\n")
        val pid = startExport(RemoteProcessTestService.ACTION_EXPORT_KILL_AT_COMMIT, root, output.name)

        assertTrue(waitUntil(45_000) { File(root, RemoteProcessTestService.KILL_TRIGGERED_FILE).exists() })
        assertTrue(waitUntil { !isProcessAlive(pid) })
        assertTrue("Final ZIP must be complete or absent, never partial", !output.exists() || isValidArchive(output))

        recoverExport(root, output)

        assertValidArchive(output, requireNdjson = true)
        assertTrue(exportTemps(output).isEmpty())
    }

    private fun startRemote(action: String, root: File): Int {
        context.startService(serviceIntent(action, root.name))
        val ready = File(root, RemoteProcessTestService.READY_FILE)
        assertTrue("Secondary process did not become ready for $action", waitUntil { ready.exists() })
        return ready.readText().trim().toInt()
    }

    private fun startExport(action: String, root: File, outputName: String): Int {
        context.startService(serviceIntent(action, root.name).putExtra(RemoteProcessTestService.EXTRA_OUTPUT_NAME, outputName))
        val ready = File(root, RemoteProcessTestService.READY_FILE)
        assertTrue("Export process did not become ready", waitUntil(45_000) { ready.exists() })
        return ready.readText().trim().toInt()
    }

    private fun serviceIntent(action: String, folder: String) =
        Intent(context, RemoteProcessTestService::class.java)
            .setAction(action)
            .putExtra(RemoteProcessTestService.EXTRA_FOLDER, folder)

    private fun destination(root: File, maxPending: Int, uploader: RemoteLogUploader): RemoteLogDestination =
        RemoteLogDestination(
            uploader = uploader,
            config = remoteConfig(root, maxPending)
        ).also(destinations::add)

    private fun identifiedDestination(
        root: File,
        maxPending: Int,
        upload: (com.filelogger.remote.RemoteUploadContext) -> Boolean
    ): RemoteLogDestination = RemoteLogDestination.withIdentifiedUploader(
        IdentifiedRemoteLogUploader { _, context -> upload(context) },
        remoteConfig(root, maxPending)
    ).also(destinations::add)

    private fun remoteConfig(root: File, maxPending: Int) = RemoteLogConfig(
        spoolDirectory = root,
        batchSize = 1,
        queueCapacity = 8,
        maxPendingBatches = maxPending,
        maxRetries = 0,
        gzipEnabled = false
    )

    private fun recoverExport(root: File, output: File) {
        runCatching { FileLogger.shutdown(1_000) }
        FileLogger.init(
            context,
            LoggerConfig.dev().copy(
                logFolder = "${root.name}/logs",
                logFileName = "process.log",
                sessionLoggingEnabled = false,
                crashCaptureEnabled = false,
                autoFlushOnAppBackground = false
            )
        )
        FileLogger.exportLogs(output, LogRedactor.NONE)
        FileLogger.shutdown(5_000)
    }

    private fun assertValidArchive(file: File, requireNdjson: Boolean = false) {
        assertTrue("Expected final ZIP: ${file.absolutePath}", file.exists())
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().toList()
            assertTrue(entries.isNotEmpty())
            entries.forEach { entry ->
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                assertEquals(entry.crc, CRC32().apply { update(bytes) }.value)
                assertEquals(entry.size, bytes.size.toLong())
                if (requireNdjson && entry.name.endsWith(".log")) {
                    bytes.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).forEach(::JSONObject)
                }
            }
        }
    }

    private fun isValidArchive(file: File): Boolean = runCatching {
        assertValidArchive(file)
        true
    }.getOrDefault(false)

    private fun writeKnownArchive(file: File, entryName: String, content: String) {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(content.toByteArray())
            zip.closeEntry()
        }
    }

    private fun uploadEvents(root: File) = root.listFiles().orEmpty()
        .filter { it.name.startsWith(RemoteProcessTestService.UPLOAD_EVENT_PREFIX) }

    private fun claimFiles(root: File) = root.listFiles().orEmpty()
        .filter { it.name.startsWith(".filelogger-uploading-") }

    private fun committedFiles(root: File) = root.listFiles().orEmpty()
        .filter { it.name.startsWith("batch_") && !it.name.endsWith(".tmp") }

    private fun exportTemps(output: File) = output.parentFile?.listFiles().orEmpty()
        .filter { it.name.startsWith(".${output.name.take(32)}-") && it.name.endsWith(".tmp") }

    private fun newRoot(prefix: String): File {
        val folder = "${prefix}_${System.nanoTime()}"
        activeFolder = folder
        return File(context.filesDir, folder).apply { mkdirs() }
    }

    private fun waitUntil(timeoutMillis: Long = 15_000, predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            Thread.sleep(25)
        }
        return predicate()
    }

    private fun isProcessAlive(pid: Int): Boolean = File("/proc/$pid/stat").exists()

    private fun testRecord(message: String) = com.filelogger.LogRecord(
        timestampMillis = System.currentTimeMillis(),
        level = com.filelogger.LogLevel.INFO,
        tag = "MainProcess",
        message = message,
        threadName = Thread.currentThread().name,
        processName = context.packageName
    )
}
