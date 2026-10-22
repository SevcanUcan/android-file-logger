package com.filelogger.remote

import com.filelogger.LogLevel
import com.filelogger.LogRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.BlockingQueue
import java.util.zip.GZIPInputStream
import kotlin.io.path.createTempDirectory

class RemoteReliabilityRegressionTest {

    @Test
    fun `flush fails when spool path cannot contain files`() {
        val spoolPath = createTempDirectory("filelogger-spool-file").resolve("not-a-directory").toFile()
            .apply { writeText("occupied") }
        var uploads = 0
        val destination = destination(spoolPath) { uploads += 1; true }

        destination.write(record("must-not-disappear"))

        assertFalse("Local persistence failure must make the flush barrier fail", destination.flush(2_000))
        assertEquals(0, uploads)
        assertEquals("The worker attempt and flush retry must both be observable", 2, destination.diagnostics().failedRemoteBatches)
        destination.close(2_000)
    }

    @Test
    fun `persistence failure is not mistaken for remote acknowledgement`() {
        val spoolPath = createTempDirectory("filelogger-spool-ack").resolve("not-a-directory").toFile()
            .apply { writeText("occupied") }
        val destination = destination(spoolPath) { true }

        destination.write(record("not-persisted"))
        val flushed = destination.flush(2_000)

        val diagnostics = destination.diagnostics()
        assertEquals(
            "A missing spool file is neither local persistence nor remote acknowledgement",
            listOf(false, 0L, 0, 2L),
            listOf(
                flushed,
                diagnostics.uploadedRemoteBatches,
                diagnostics.pendingRemoteBatches,
                diagnostics.failedRemoteBatches
            )
        )
        destination.close(2_000)
    }

    @Test
    fun `persist refuses a cross-process pause without reporting false durability`() {
        val spool = createTempDirectory("filelogger-persist-after-pause").toFile()
        assertTrue(RemoteSpoolClaimCoordinator(spool).setDeliveryEnabled(false))

        val persisted = FileRemoteSpoolStore(spool).persist(
            payload = "must-not-be-accepted\n".toByteArray(),
            suffix = ".jsonl",
            batchId = "v2-123e4567-e89b-42d3-a456-426614174000"
        )

        assertFalse("A paused spool must not report an unwritten batch as durable", persisted)
        assertTrue(completeSpoolFiles(spool).isEmpty())
    }

    @Test
    fun `offline upload reports failed delivery but preserves local batch`() {
        val spool = createTempDirectory("filelogger-offline").toFile()
        val destination = destination(spool) { false }

        destination.write(record("offline"))

        assertFalse(destination.flush(2_000))
        assertEquals(1, destination.diagnostics().pendingRemoteBatches)
        assertEquals(0, destination.diagnostics().uploadedRemoteBatches)
        destination.close(2_000)
    }

    @Test
    fun `recoverable spool failure retains the original batch for retry`() {
        val parent = createTempDirectory("filelogger-spool-recovery").toFile()
        val spool = File(parent, "spool").apply { writeText("temporarily blocked") }
        val uploaded = Collections.synchronizedList(mutableListOf<String>())
        val destination = destination(spool) { batch ->
            uploaded += decode(batch)
            true
        }

        destination.write(record("first-survives"))
        val failedFlush = destination.flush(2_000)
        assertTrue(spool.delete())
        assertTrue(spool.mkdirs())
        destination.write(record("second"))
        val recoveredFlush = destination.flush(2_000)

        assertEquals(
            "Recovery must report the failed barrier and retain both the original and later batch",
            listOf(false, true, true, true),
            listOf(
                failedFlush,
                recoveredFlush,
                uploaded.any { "first-survives" in it },
                uploaded.any { "second" in it }
            )
        )
        destination.close(2_000)
    }

    @Test
    fun `concurrent destinations create one durable spool file per batch`() {
        val spool = createTempDirectory("filelogger-collision-32").toFile()
        val count = 32
        val destinations = List(count) { destination(spool) { false } }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(count)

        destinations.forEachIndexed { index, remote ->
            executor.execute {
                start.await()
                remote.write(record("batch-$index"))
                remote.flush(10_000)
            }
        }
        start.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))

        val files = completeSpoolFiles(spool)
        assertEquals("Concurrent destinations must not collide or overwrite", count, files.size)
        assertEquals(count, files.map { it.name }.toSet().size)
        destinations.forEach { it.close(2_000) }
    }

    @Test
    fun `destinations committing in the same millisecond still use unique names`() {
        val spool = createTempDirectory("filelogger-same-time").toFile()
        val count = 32
        val destinations = List(count) {
            destination(spool, currentTimeMillis = { 42L }) { false }
        }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(count)

        destinations.forEachIndexed { index, remote ->
            executor.execute {
                start.await()
                remote.write(record("same-time-$index"))
                remote.flush(10_000)
            }
        }
        start.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))

        val files = completeSpoolFiles(spool)
        assertEquals(count, files.size)
        assertEquals(count, files.map(File::getName).toSet().size)
        destinations.forEach { it.close(2_000) }
    }

    @Test
    fun `partial spool write fails flush and retries the retained in-memory batch`() {
        val spool = createTempDirectory("filelogger-partial-write").toFile()
        val failWrites = AtomicBoolean(true)
        val store = FileRemoteSpoolStore(spool) { file, payload ->
            file.outputStream().use { stream ->
                stream.write(payload, 0, payload.size / 2)
            }
            if (failWrites.get()) throw java.io.IOException("injected partial write")
            file.writeBytes(payload)
        }
        val uploaded = mutableListOf<String>()
        val destination = destination(spool, spoolStore = store) { batch ->
            uploaded += decode(batch)
            true
        }

        destination.write(record("survives-partial-write"))
        assertFalse(destination.flush(2_000))
        assertTrue(completeSpoolFiles(spool).isEmpty())
        assertTrue(spool.listFiles().orEmpty().none { it.name.endsWith(".tmp") })

        failWrites.set(false)
        assertTrue(destination.flush(2_000))
        assertTrue(uploaded.single().contains("survives-partial-write"))
        destination.close(2_000)
    }

    @Test
    fun `repeated persistence failure keeps accepted in-memory records bounded`() {
        val spool = createTempDirectory("filelogger-bounded-failure").toFile()
        val destination = RemoteLogDestination(
            uploader = RemoteLogUploader { true },
            config = RemoteLogConfig(spool, batchSize = 1, queueCapacity = 4, maxRetries = 0),
            sleeper = {},
            spoolStore = object : RemoteSpoolStore {
                override fun persist(payload: ByteArray, suffix: String, batchId: String): Boolean = false
            }
        )

        repeat(100) { destination.write(record("bounded-$it")) }

        assertFalse(destination.flush(2_000))
        assertTrue("Records above the bounded in-memory allowance must be rejected", destination.diagnostics().droppedAsyncRecords >= 95)
        assertFalse("Shutdown must expose the still-unpersisted batch", destination.close(2_000))
    }

    @Test
    fun `high concurrency preserves hundreds of distinct batches`() {
        val spool = createTempDirectory("filelogger-collision-stress").toFile()
        val destinationCount = 32
        val batchesPerDestination = 16
        val expectedBatchCount = destinationCount * batchesPerDestination
        val persistenceBarrier = CountDownLatch(expectedBatchCount)
        val destinations = List(destinationCount) {
            val delegate = FileRemoteSpoolStore(spool)
            val observedStore = object : RemoteSpoolStore {
                override fun persist(payload: ByteArray, suffix: String, batchId: String): Boolean {
                    val persisted = delegate.persist(payload, suffix, batchId)
                    if (persisted) persistenceBarrier.countDown()
                    return persisted
                }
            }
            destination(spool, gzip = false, spoolStore = observedStore) { false }
        }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(destinationCount)

        destinations.forEachIndexed { destinationIndex, remote ->
            executor.execute {
                start.await()
                repeat(batchesPerDestination) { batchIndex ->
                    remote.write(record("$destinationIndex-$batchIndex"))
                }
            }
        }
        start.countDown()
        executor.shutdown()
        assertTrue("All producers must finish", executor.awaitTermination(30, TimeUnit.SECONDS))
        assertTrue(
            "All accepted batches must cross the successful-persist barrier",
            persistenceBarrier.await(120, TimeUnit.SECONDS)
        )
        assertFalse(
            "The intentionally rejecting uploader must make remote delivery flush fail",
            destinations.first().flush(30_000)
        )
        destinations.forEach { destination ->
            assertFalse(
                "Closing a rejecting uploader must report undelivered durable batches",
                destination.close(5_000)
            )
        }

        val files = completeSpoolFiles(spool)
        assertEquals("Every accepted batch requires a distinct committed spool file", expectedBatchCount, files.size)
        assertEquals("Committed spool names must be unique", expectedBatchCount, files.map(File::getName).toSet().size)
        val identityStore = RemoteBatchIdentityStore(spool)
        assertEquals(
            "Every committed file must retain a unique stable batch ID",
            expectedBatchCount,
            files.map { identityStore.batchIdFor(it.name) }.toSet().size
        )
        val messagePattern = Regex("\\\"message\\\":\\\"([^\\\"]+)\\\"")
        val actualMessages = files.mapNotNull { file ->
            messagePattern.find(file.readText())?.groupValues?.get(1)
        }
        val expectedMessages = buildSet {
            repeat(destinationCount) { destinationIndex ->
                repeat(batchesPerDestination) { batchIndex -> add("$destinationIndex-$batchIndex") }
            }
        }
        assertEquals("Every committed payload must be complete and distinct", expectedMessages, actualMessages.toSet())
        assertEquals(expectedBatchCount, actualMessages.size)
        assertTrue("No interrupted temp file may remain", spool.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `recovery uploads complete files and ignores interrupted temp files`() {
        val spool = createTempDirectory("filelogger-interrupted-spool").toFile()
        val complete = File(spool, "batch_existing_1.jsonl").apply { writeText("complete\n") }
        val interrupted = File(spool, "batch_existing_2.tmp").apply { writeText("partial") }
        val payloads = mutableListOf<String>()
        val destination = destination(spool, gzip = false) { batch ->
            payloads += batch.payload.toString(Charsets.UTF_8)
            true
        }

        assertTrue(destination.flush(2_000))

        assertEquals(listOf("complete\n"), payloads)
        assertFalse(complete.exists())
        assertTrue("An uncommitted temp file must never be uploaded", interrupted.exists())
        destination.close(2_000)
    }

    @Test
    fun `oversized spool batch is rejected without blocking a healthy batch`() {
        val spool = createTempDirectory("filelogger-oversized-spool").toFile()
        val oversized = File(spool, "batch_000_oversized.jsonl")
        RandomAccessFile(oversized, "rw").use { file ->
            file.setLength(16L * 1024 * 1024 + 1)
        }
        File(spool, "batch_999_healthy.jsonl").writeText("healthy\n")
        val payloads = Collections.synchronizedList(mutableListOf<String>())
        val destination = destination(spool, gzip = false) { batch ->
            payloads += batch.payload.toString(Charsets.UTF_8)
            true
        }

        assertFalse("The rejected oversized batch must keep flush from reporting full delivery", destination.flush(3_000))
        assertEquals(listOf("healthy\n"), payloads)
        assertTrue("Rejected durable data must not be silently deleted", oversized.exists())
        destination.close(500)
    }

    @Test
    fun `empty spool batch is rejected without blocking a healthy batch`() {
        val spool = createTempDirectory("filelogger-empty-spool").toFile()
        val empty = File(spool, "batch_000_empty.jsonl").apply { createNewFile() }
        File(spool, "batch_999_healthy.jsonl").writeText("healthy\n")
        val payloads = Collections.synchronizedList(mutableListOf<String>())
        val destination = destination(spool, gzip = false) { batch ->
            payloads += batch.payload.toString(Charsets.UTF_8)
            true
        }

        assertFalse("The rejected empty batch must keep flush from reporting full delivery", destination.flush(3_000))
        assertEquals(listOf("healthy\n"), payloads)
        assertTrue("Rejected durable data must not be silently deleted", empty.exists())
        destination.close(500)
    }

    @Test
    fun `spool persist rejects payloads above the read safety limit`() {
        val spool = createTempDirectory("filelogger-spool-write-limit").toFile()
        val stored = FileRemoteSpoolStore(spool).persist(
            payload = ByteArray((16L * 1024 * 1024 + 1).toInt()),
            suffix = ".jsonl",
            batchId = "v2-123e4567-e89b-42d3-a456-426614174000"
        )

        assertFalse(stored)
        assertTrue(completeSpoolFiles(spool).isEmpty())
    }

    @Test
    fun `failed replacement persist does not evict an existing durable batch`() {
        val spool = createTempDirectory("filelogger-spool-quota-failure").toFile()
        val existing = File(spool, "batch_existing.jsonl").apply { writeText("durable-before-failure\n") }
        val destination = RemoteLogDestination(
            uploader = RemoteLogUploader { false },
            config = RemoteLogConfig(
                spoolDirectory = spool,
                batchSize = 1,
                queueCapacity = 4,
                maxPendingBatches = 1,
                maxRetries = 0
            ),
            sleeper = {},
            spoolStore = object : RemoteSpoolStore {
                override fun persist(payload: ByteArray, suffix: String, batchId: String): Boolean = false
            }
        )

        destination.write(record("replacement-cannot-be-persisted"))

        assertFalse(destination.flush(2_000))
        assertTrue(
            "A committed batch must survive until its replacement is durably committed",
            existing.exists()
        )
        destination.close(2_000)
    }

    @Test
    fun `shared spool quota is atomic across concurrent destinations`() {
        val spool = createTempDirectory("filelogger-atomic-shared-quota").toFile()
        val uploadStarted = CountDownLatch(1)
        val releaseUpload = CountDownLatch(1)
        val destinations = List(32) {
            RemoteLogDestination(
                uploader = RemoteLogUploader {
                    uploadStarted.countDown()
                    releaseUpload.await(5, TimeUnit.SECONDS)
                    false
                },
                config = RemoteLogConfig(
                    spoolDirectory = spool,
                    batchSize = 1,
                    queueCapacity = 4,
                    maxPendingBatches = 1,
                    maxRetries = 0,
                    gzipEnabled = false
                )
            )
        }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(destinations.size)
        destinations.forEachIndexed { index, destination ->
            executor.execute {
                start.await()
                destination.write(record("quota-$index"))
            }
        }

        start.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertTrue(uploadStarted.await(5, TimeUnit.SECONDS))
        Thread.sleep(500)

        val durableBatches = spool.listFiles().orEmpty().count { file ->
            file.name.startsWith("batch_") ||
                file.name.startsWith(RemoteSpoolClaimCoordinator.CLAIM_PREFIX)
        }
        assertEquals(
            "Quota check and persist must be one cross-destination atomic operation",
            1,
            durableBatches
        )

        releaseUpload.countDown()
        destinations.forEach { it.close(2_000) }
    }

    @Test
    fun `two workers claim a committed batch for upload only once`() {
        val spool = createTempDirectory("filelogger-upload-ownership").toFile()
        File(spool, "batch_shared.jsonl").writeText("shared-batch\n")
        val probe = runCatching { RemoteSpoolClaimCoordinator(spool).claimNext() }
            .getOrElse { throw AssertionError("Claim setup failed", it) }
        assertTrue("A committed batch must be claimable", probe != null)
        assertTrue("A released claim must return to committed state", probe!!.complete(uploaded = false))
        val uploadCalls = AtomicInteger()
        val releaseUpload = CountDownLatch(1)
        val uploader = RemoteLogUploader {
            uploadCalls.incrementAndGet()
            releaseUpload.await(2, TimeUnit.SECONDS)
            true
        }
        val first = destination(spool, gzip = false, uploader = uploader)
        val second = destination(spool, gzip = false, uploader = uploader)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val flushResults = Collections.synchronizedList(mutableListOf<Boolean>())
        executor.execute { start.await(); flushResults += first.flush(5_000) }
        executor.execute { start.await(); flushResults += second.flush(5_000) }

        start.countDown()
        Thread.sleep(300)
        releaseUpload.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))

        assertEquals(
            "A committed batch needs exclusive local upload ownership; " +
                "flush=$flushResults first=${first.diagnostics()} second=${second.diagnostics()}",
            1,
            uploadCalls.get()
        )
        first.close(2_000)
        second.close(2_000)
    }

    @Test
    fun `restart recovers an unlocked stale upload claim`() {
        val spool = createTempDirectory("filelogger-stale-upload-claim").toFile()
        val stale = File(
            spool,
            "${RemoteSpoolClaimCoordinator.CLAIM_PREFIX}batch_legacy.jsonl"
        ).apply { writeText("legacy-batch\n") }
        val payloads = mutableListOf<String>()
        val destination = destination(spool, gzip = false) { batch ->
            payloads += batch.payload.toString(Charsets.UTF_8)
            true
        }

        assertTrue(destination.flush(2_000))

        assertEquals(listOf("legacy-batch\n"), payloads)
        assertFalse(stale.exists())
        assertTrue(completeSpoolFiles(spool).isEmpty())
        destination.close(2_000)
    }

    @Test
    fun `dead worker rejects later records instead of leaving them queued`() {
        val spool = createTempDirectory("filelogger-dead-worker").toFile()
        val destination = destination(spool) { true }
        val workerField = RemoteLogDestination::class.java.getDeclaredField("worker").apply {
            isAccessible = true
        }
        val worker = workerField.get(destination) as Thread

        worker.interrupt()
        worker.join(2_000)
        assertFalse("The injected worker interruption must terminate the worker", worker.isAlive)

        destination.write(record("must-be-rejected"))
        val diagnostics = destination.diagnostics()
        assertEquals(
            "A destination without a live worker must not accept an unconsumable record",
            0,
            diagnostics.queuedAsyncRecords
        )
        assertEquals(1, diagnostics.droppedAsyncRecords)
        assertFalse(destination.flush(100))
        assertFalse(destination.close(100))
    }

    @Test
    fun `erase requested during an upload prevents a later retry from starting`() {
        val spool = createTempDirectory("filelogger-erase-during-upload").toFile()
        val firstUploadStarted = CountDownLatch(1)
        val releaseFirstUpload = CountDownLatch(1)
        val uploadCalls = AtomicInteger()
        val destination = RemoteLogDestination(
            uploader = RemoteLogUploader {
                val call = uploadCalls.incrementAndGet()
                if (call == 1) {
                    firstUploadStarted.countDown()
                    releaseFirstUpload.await(5, TimeUnit.SECONDS)
                }
                false
            },
            config = RemoteLogConfig(
                spoolDirectory = spool,
                batchSize = 1,
                queueCapacity = 16,
                maxRetries = 1,
                initialBackoffMillis = 0,
                gzipEnabled = false
            )
        )
        destination.write(record("must-not-retry-after-revocation"))
        assertTrue(firstUploadStarted.await(2, TimeUnit.SECONDS))

        val executor = Executors.newSingleThreadExecutor()
        val eraseResult = executor.submit<Boolean> { destination.erasePendingData(5_000) }
        waitForQueuedControl(destination, "Clear")
        releaseFirstUpload.countDown()

        assertTrue(eraseResult.get(5, TimeUnit.SECONDS))
        assertEquals(
            "No retry may begin after an erase/revocation request is accepted",
            1,
            uploadCalls.get()
        )
        executor.shutdownNow()
        destination.close(2_000)
    }

    @Test
    fun `erase cannot report success while another destination owns an upload claim`() {
        val spool = createTempDirectory("filelogger-erase-active-claim").toFile()
        File(spool, "batch_existing.jsonl").writeText("durable\n")
        val uploadStarted = CountDownLatch(1)
        val releaseUpload = CountDownLatch(1)
        val owner = destination(spool, gzip = false) {
            uploadStarted.countDown()
            releaseUpload.await(5, TimeUnit.SECONDS)
            true
        }
        val eraser = destination(spool, gzip = false) { true }
        val executor = Executors.newSingleThreadExecutor()
        val ownerFlush = executor.submit<Boolean> { owner.flush(5_000) }
        assertTrue(uploadStarted.await(2, TimeUnit.SECONDS))

        val erased = eraser.erasePendingData(2_000)

        assertFalse(
            "Erase must not report success while a relevant claimed batch still exists",
            erased
        )
        releaseUpload.countDown()
        ownerFlush.get(5, TimeUnit.SECONDS)
        executor.shutdownNow()
        owner.close(2_000)
        eraser.close(2_000)
    }

    @Test
    fun `erase after worker failure removes durable pending data`() {
        val spool = createTempDirectory("filelogger-erase-failed-worker").toFile()
        val pending = File(spool, "batch_existing.jsonl").apply { writeText("durable\n") }
        val destination = destination(spool, gzip = false) { true }
        val workerField = RemoteLogDestination::class.java.getDeclaredField("worker").apply {
            isAccessible = true
        }
        val worker = workerField.get(destination) as Thread
        worker.interrupt()
        worker.join(2_000)
        assertFalse(worker.isAlive)

        assertTrue(
            "A dead worker must not make consent-driven durable erasure impossible",
            destination.erasePendingData(2_000)
        )
        assertFalse(pending.exists())
    }

    @Test
    fun `identified uploader keeps batch identity stable and scopes every retry attempt`() {
        val spool = createTempDirectory("filelogger-identified-retry").toFile()
        val contexts = Collections.synchronizedList(mutableListOf<RemoteUploadContext>())
        val destination = RemoteLogDestination.withIdentifiedUploader(
            uploader = IdentifiedRemoteLogUploader { _, context ->
                contexts += context
                contexts.size >= 3
            },
            config = RemoteLogConfig(
                spoolDirectory = spool,
                batchSize = 1,
                maxRetries = 2,
                initialBackoffMillis = 0,
                gzipEnabled = false
            )
        )

        destination.write(record("stable-identity"))
        assertTrue(destination.flush(5_000))

        assertEquals(listOf(1, 2, 3), contexts.map { it.attemptNumber })
        assertEquals(1, contexts.map { it.batchId }.toSet().size)
        assertTrue(contexts.first().batchId.startsWith("v2-"))
        assertEquals(3, contexts.map { it.attemptId }.toSet().size)
        destination.close(2_000)
    }

    @Test
    fun `late cancellation is tied to the exact active attempt`() {
        val spool = createTempDirectory("filelogger-attempt-cancel").toFile()
        val uploadStarted = CountDownLatch(1)
        val releaseUpload = CountDownLatch(1)
        val uploadedContext = AtomicReference<RemoteUploadContext>()
        val cancelledContext = AtomicReference<RemoteUploadContext>()
        val uploader = object : CancellableIdentifiedRemoteLogUploader {
            override fun upload(batch: RemoteLogBatch, context: RemoteUploadContext): Boolean {
                uploadedContext.set(context)
                uploadStarted.countDown()
                releaseUpload.await(5, TimeUnit.SECONDS)
                return false
            }

            override fun cancelUpload(context: RemoteUploadContext): Boolean {
                cancelledContext.set(context)
                releaseUpload.countDown()
                return true
            }
        }
        val destination = RemoteLogDestination.withIdentifiedUploader(
            uploader,
            RemoteLogConfig(spool, batchSize = 1, maxRetries = 2, initialBackoffMillis = 0, gzipEnabled = false)
        )
        destination.write(record("cancel-one-attempt"))
        assertTrue(uploadStarted.await(2, TimeUnit.SECONDS))

        assertTrue(destination.setCollectionEnabled(false, erasePendingData = true, timeoutMillis = 5_000))

        assertEquals(uploadedContext.get().attemptId, cancelledContext.get().attemptId)
        assertEquals(uploadedContext.get().batchId, cancelledContext.get().batchId)
        assertEquals(1, uploadedContext.get().attemptNumber)
        assertTrue(completeSpoolFiles(spool).isEmpty())
        destination.close(2_000)
    }

    @Test
    fun `uncooperative cancellation cannot exceed the consent deadline or report erase success`() {
        val spool = createTempDirectory("filelogger-cancel-timeout").toFile()
        val uploadStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val uploader = object : CancellableIdentifiedRemoteLogUploader {
            override fun upload(batch: RemoteLogBatch, context: RemoteUploadContext): Boolean {
                uploadStarted.countDown()
                release.await(5, TimeUnit.SECONDS)
                return false
            }

            override fun cancelUpload(context: RemoteUploadContext): Boolean {
                release.await(5, TimeUnit.SECONDS)
                return false
            }
        }
        val destination = RemoteLogDestination.withIdentifiedUploader(
            uploader,
            RemoteLogConfig(spool, batchSize = 1, maxRetries = 0, gzipEnabled = false)
        )
        destination.write(record("blocked-cancellation"))
        assertTrue(uploadStarted.await(2, TimeUnit.SECONDS))

        val started = System.nanoTime()
        val erased = destination.setCollectionEnabled(false, erasePendingData = true, timeoutMillis = 100)
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertFalse(erased)
        assertTrue("Consent timeout must bound an uncooperative callback: ${elapsedMillis}ms", elapsedMillis < 1_000)
        assertTrue("Unresolved claimed data must remain observable", destination.diagnostics().pendingRemoteBatches > 0)
        release.countDown()
        destination.close(2_000)
    }

    @Test
    fun `concurrent revoke calls share one cancellation attempt`() {
        val spool = createTempDirectory("filelogger-bounded-cancellation").toFile()
        val uploadStarted = CountDownLatch(1)
        val releaseCallbacks = CountDownLatch(1)
        val cancellationCalls = AtomicInteger()
        val uploader = object : CancellableIdentifiedRemoteLogUploader {
            override fun upload(batch: RemoteLogBatch, context: RemoteUploadContext): Boolean {
                uploadStarted.countDown()
                releaseCallbacks.await(5, TimeUnit.SECONDS)
                return false
            }

            override fun cancelUpload(context: RemoteUploadContext): Boolean {
                cancellationCalls.incrementAndGet()
                releaseCallbacks.await(5, TimeUnit.SECONDS)
                return false
            }
        }
        val destination = RemoteLogDestination.withIdentifiedUploader(
            uploader,
            RemoteLogConfig(spool, batchSize = 1, maxRetries = 0, gzipEnabled = false)
        )
        destination.write(record("cancel-once"))
        assertTrue(uploadStarted.await(2, TimeUnit.SECONDS))

        val callers = 16
        val ready = CountDownLatch(callers)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(callers)
        repeat(callers) {
            executor.execute {
                ready.countDown()
                start.await()
                destination.setCollectionEnabled(false, erasePendingData = true, timeoutMillis = 100)
            }
        }
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        start.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))

        assertEquals(
            "One active upload attempt must own at most one cancellation callback",
            1,
            cancellationCalls.get()
        )
        releaseCallbacks.countDown()
        destination.close(2_000)
    }

    @Test
    fun `blocked cancellation cannot create another thread after the upload attempt ends`() {
        val spool = createTempDirectory("filelogger-cancellation-lifecycle").toFile()
        val firstUploadStarted = CountDownLatch(1)
        val secondUploadStarted = CountDownLatch(1)
        val releaseFirstUpload = CountDownLatch(1)
        val releaseSecondUpload = CountDownLatch(1)
        val releaseCancellation = CountDownLatch(1)
        val firstUploadReturned = CountDownLatch(1)
        val uploadCalls = AtomicInteger()
        val cancellationCalls = AtomicInteger()
        val uploader = object : CancellableIdentifiedRemoteLogUploader {
            override fun upload(batch: RemoteLogBatch, context: RemoteUploadContext): Boolean {
                when (uploadCalls.incrementAndGet()) {
                    1 -> {
                        firstUploadStarted.countDown()
                        releaseFirstUpload.await(5, TimeUnit.SECONDS)
                        firstUploadReturned.countDown()
                    }
                    else -> {
                        secondUploadStarted.countDown()
                        releaseSecondUpload.await(5, TimeUnit.SECONDS)
                    }
                }
                return false
            }

            override fun cancelUpload(context: RemoteUploadContext): Boolean {
                cancellationCalls.incrementAndGet()
                releaseCancellation.await(5, TimeUnit.SECONDS)
                return false
            }
        }
        val destination = RemoteLogDestination.withIdentifiedUploader(
            uploader,
            RemoteLogConfig(spool, batchSize = 1, maxRetries = 0, gzipEnabled = false)
        )

        destination.write(record("first-attempt"))
        assertTrue(firstUploadStarted.await(2, TimeUnit.SECONDS))
        assertFalse(destination.setCollectionEnabled(false, erasePendingData = false, timeoutMillis = 100))
        releaseFirstUpload.countDown()
        assertTrue(firstUploadReturned.await(2, TimeUnit.SECONDS))

        assertTrue(destination.setCollectionEnabled(true, erasePendingData = false, timeoutMillis = 2_000))
        destination.write(record("second-attempt"))
        assertTrue(secondUploadStarted.await(2, TimeUnit.SECONDS))
        assertFalse(destination.setCollectionEnabled(false, erasePendingData = false, timeoutMillis = 100))

        assertEquals(
            "A permanently blocked consumer callback must occupy the one cancellation slot",
            1,
            cancellationCalls.get()
        )
        releaseSecondUpload.countDown()
        releaseCancellation.countDown()
        destination.close(2_000)
    }

    @Test
    fun `durable pause prevents a restarted destination from uploading until explicit reenable`() {
        val spool = createTempDirectory("filelogger-durable-pause").toFile()
        File(spool, "batch_legacy.jsonl").writeText("pending\n")
        val first = destination(spool, gzip = false) { false }
        assertTrue(first.setCollectionEnabled(false, erasePendingData = false, timeoutMillis = 2_000))
        first.close(500)

        val uploads = AtomicInteger()
        val restarted = destination(spool, gzip = false) { uploads.incrementAndGet(); true }
        restarted.write(record("must-be-rejected-while-paused"))
        assertFalse(restarted.flush(500))
        assertEquals(0, uploads.get())
        assertEquals(1, restarted.diagnostics().droppedAsyncRecords)

        assertTrue(restarted.setCollectionEnabled(true, erasePendingData = false, timeoutMillis = 2_000))
        assertTrue(restarted.flush(2_000))
        assertEquals(1, uploads.get())
        restarted.close(2_000)
    }

    @Test
    fun `destination created during shared pause resumes when another destination reenables delivery`() {
        val spool = createTempDirectory("filelogger-cross-destination-resume").toFile()
        val controller = destination(spool, gzip = false) { true }
        assertTrue(controller.setCollectionEnabled(false, erasePendingData = false, timeoutMillis = 2_000))

        val uploads = AtomicInteger()
        val startedWhilePaused = destination(spool, gzip = false) {
            uploads.incrementAndGet()
            true
        }
        startedWhilePaused.write(record("rejected-while-paused"))
        assertEquals(1, startedWhilePaused.diagnostics().droppedAsyncRecords)

        assertTrue(controller.setCollectionEnabled(true, erasePendingData = false, timeoutMillis = 2_000))
        startedWhilePaused.write(record("accepted-after-shared-resume"))

        assertTrue(startedWhilePaused.flush(2_000))
        assertEquals("Shared re-enable must wake destinations created while paused", 1, uploads.get())
        assertTrue(completeSpoolFiles(spool).isEmpty())
        controller.close(2_000)
        startedWhilePaused.close(2_000)
    }

    @Test
    fun `a different destination can reenable a destination that revoked shared consent`() {
        val spool = createTempDirectory("filelogger-cross-destination-controller").toFile()
        val uploads = AtomicInteger()
        val revoked = destination(spool, gzip = false) {
            uploads.incrementAndGet()
            true
        }
        val enabler = destination(spool, gzip = false) { true }
        assertTrue(revoked.setCollectionEnabled(false, erasePendingData = false, timeoutMillis = 2_000))
        revoked.write(record("rejected-after-revoke"))
        assertEquals(1, revoked.diagnostics().droppedAsyncRecords)

        assertTrue(enabler.setCollectionEnabled(true, erasePendingData = false, timeoutMillis = 2_000))
        revoked.write(record("accepted-after-external-resume"))

        assertTrue(revoked.flush(2_000))
        assertEquals(1, uploads.get())
        revoked.close(2_000)
        enabler.close(2_000)
    }

    @Test
    fun `revoke prevents an upload from registering after cancellation inspection`() {
        val spool = createTempDirectory("filelogger-consent-registration-race").toFile()
        val registrationWindowReached = CountDownLatch(1)
        val releaseRegistration = CountDownLatch(1)
        val uploads = AtomicInteger()
        val destination = RemoteLogDestination(
            uploader = RemoteLogUploader { uploads.incrementAndGet(); true },
            config = RemoteLogConfig(
                spoolDirectory = spool,
                batchSize = 1,
                queueCapacity = 10,
                maxRetries = 0,
                gzipEnabled = false
            ),
            sleeper = {},
            beforeUploadRegistration = {
                registrationWindowReached.countDown()
                assertTrue(releaseRegistration.await(2, TimeUnit.SECONDS))
            }
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            destination.write(record("must-not-start-after-revoke-linearizes"))
            assertTrue(registrationWindowReached.await(2, TimeUnit.SECONDS))

            val revoke = executor.submit<Boolean> {
                destination.setCollectionEnabled(
                    enabled = false,
                    erasePendingData = false,
                    timeoutMillis = 2_000
                )
            }
            waitForQueuedControl(destination, "Pause")
            releaseRegistration.countDown()

            assertTrue(revoke.get(2, TimeUnit.SECONDS))
            assertEquals(
                "An upload must not register after revoke has inspected active cancellation state",
                0,
                uploads.get()
            )
        } finally {
            releaseRegistration.countDown()
            executor.shutdownNow()
            destination.close(500)
        }
    }

    @Test
    fun `pause preserves records accepted before revocation without uploading them`() {
        val spool = createTempDirectory("filelogger-pause-preserves-accepted").toFile()
        val uploads = AtomicInteger()
        val destination = RemoteLogDestination(
            uploader = RemoteLogUploader { uploads.incrementAndGet(); true },
            config = RemoteLogConfig(
                spoolDirectory = spool,
                batchSize = 50,
                queueCapacity = 50,
                maxRetries = 0,
                gzipEnabled = false
            )
        )
        destination.write(record("accepted-before-pause"))

        assertTrue(destination.setCollectionEnabled(false, erasePendingData = false, timeoutMillis = 2_000))

        assertEquals(0, uploads.get())
        assertEquals(1, completeSpoolFiles(spool).size)
        assertTrue(File(spool, ".filelogger-remote-paused").exists())
        destination.close(500)
    }

    @Test
    fun `legacy identity is stable across stores and never includes claim prefix`() {
        val spool = createTempDirectory("filelogger-legacy-id").toFile()
        val name = "batch_1700000000000_old_1.jsonl"
        val first = RemoteBatchIdentityStore(spool) { "123e4567-e89b-42d3-a456-426614174000" }
            .batchIdFor(name)
        val afterRestart = RemoteBatchIdentityStore(spool) { error("namespace must be reused") }
            .batchIdFor(name)

        assertEquals(first, afterRestart)
        assertTrue(first.startsWith("legacy-v1-"))
        assertFalse(first.contains(RemoteSpoolClaimCoordinator.CLAIM_PREFIX))
    }

    @Test
    fun `concurrent legacy namespace creation yields one stable identity`() {
        val spool = createTempDirectory("filelogger-namespace-race").toFile()
        val executor = Executors.newFixedThreadPool(32)
        val start = CountDownLatch(1)
        val ids = Collections.synchronizedSet(mutableSetOf<String>())
        repeat(32) {
            executor.execute {
                start.await()
                ids += RemoteBatchIdentityStore(spool).batchIdFor("batch_legacy.jsonl")
            }
        }
        start.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(1, ids.size)
    }

    @Test
    fun `namespace loss after initialization refuses to invent a replacement identity`() {
        val spool = createTempDirectory("filelogger-namespace-loss").toFile()
        val store = RemoteBatchIdentityStore(spool) { "123e4567-e89b-42d3-a456-426614174000" }
        store.batchIdFor("batch_legacy.jsonl")
        assertTrue(File(spool, ".filelogger-spool-namespace").delete())

        val failed = runCatching {
            RemoteBatchIdentityStore(spool).batchIdFor("batch_legacy.jsonl")
        }.isFailure

        assertTrue("Namespace loss must stop legacy upload instead of changing idempotency identity", failed)
    }

    @Test
    fun `legacy namespace repairs an interrupted initialization marker`() {
        val spool = createTempDirectory("filelogger-namespace-interrupted-init").toFile()
        val namespace = File(spool, ".filelogger-spool-namespace")
        namespace.writeText("v1:123e4567-e89b-42d3-a456-426614174000\n")

        RemoteBatchIdentityStore(spool) { error("existing namespace must be reused") }
            .batchIdFor("batch_legacy.jsonl")

        assertTrue(
            "Reading a committed namespace must repair the missing initialized marker",
            File(spool, ".filelogger-spool-namespace-initialized").exists()
        )
    }

    private fun destination(
        spool: File,
        gzip: Boolean = true,
        spoolStore: RemoteSpoolStore = FileRemoteSpoolStore(spool),
        currentTimeMillis: () -> Long = System::currentTimeMillis,
        uploader: RemoteLogUploader
    ): RemoteLogDestination = RemoteLogDestination(
        uploader = uploader,
        config = RemoteLogConfig(
            spoolDirectory = spool,
            batchSize = 1,
            queueCapacity = 2_048,
            maxPendingBatches = 10_000,
            maxRetries = 0,
            initialBackoffMillis = 0,
            gzipEnabled = gzip
        ),
        sleeper = {},
        spoolStore = spoolStore,
        currentTimeMillis = currentTimeMillis
    )

    private fun completeSpoolFiles(spool: File): List<File> = spool.listFiles()
        .orEmpty()
        .filter { it.isFile && it.name.startsWith("batch_") && !it.name.endsWith(".tmp") }

    private fun waitForQueuedControl(destination: RemoteLogDestination, simpleName: String) {
        val queueField = RemoteLogDestination::class.java.getDeclaredField("queue").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val queue = queueField.get(destination) as BlockingQueue<Any>
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (queue.any { it.javaClass.simpleName == simpleName }) return
            Thread.yield()
        }
        throw AssertionError("$simpleName control item was not queued")
    }

    private fun decode(batch: RemoteLogBatch): String {
        val input = if (batch.contentEncoding == "gzip") {
            GZIPInputStream(ByteArrayInputStream(batch.payload))
        } else {
            ByteArrayInputStream(batch.payload)
        }
        return input.bufferedReader().use { it.readText() }
    }

    private fun record(message: String) = LogRecord(
        timestampMillis = 1,
        level = LogLevel.DEBUG,
        tag = "RemoteRegression",
        message = message,
        threadName = "test",
        processName = "test"
    )
}
