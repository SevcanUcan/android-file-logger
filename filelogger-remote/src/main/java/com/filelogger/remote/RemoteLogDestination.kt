package com.filelogger.remote

import com.filelogger.CloseableLogDestination
import com.filelogger.CollectionControlledLogDestination
import com.filelogger.DiagnosticLogDestination
import com.filelogger.ErasableLogDestination
import com.filelogger.JsonLogFormatter
import com.filelogger.LogDiagnostics
import com.filelogger.LogRecord
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import java.util.zip.GZIPOutputStream

class RemoteLogDestination internal constructor(
    private val uploader: RemoteLogUploader?,
    private val config: RemoteLogConfig,
    private val sleeper: (Long) -> Unit,
    private val spoolStore: RemoteSpoolStore = FileRemoteSpoolStore(
        config.spoolDirectory,
        config.maxPendingBatches
    ),
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val destinationId: String = UUID.randomUUID().toString(),
    private val identifiedUploader: IdentifiedRemoteLogUploader? = null,
    private val beforeUploadRegistration: () -> Unit = {}
) : CloseableLogDestination,
    DiagnosticLogDestination,
    ErasableLogDestination,
    CollectionControlledLogDestination {

    constructor(
        uploader: RemoteLogUploader,
        config: RemoteLogConfig
    ) : this(uploader, config, Thread::sleep)

    private val formatter = JsonLogFormatter()
    private val queue = LinkedBlockingQueue<QueueItem>(config.queueCapacity)
    private val state = AtomicReference(WorkerState.RUNNING)
    private val dropped = AtomicLong()
    private val uploaded = AtomicLong()
    private val failed = AtomicLong()
    private val sequence = AtomicLong()
    private val unpersistedRecords = AtomicLong()
    private val maxUnpersistedRecords = config.queueCapacity.toLong() + config.batchSize
    private val controlLock = Any()
    private val claimCoordinator = RemoteSpoolClaimCoordinator(config.spoolDirectory)
    private val identityStore = RemoteBatchIdentityStore(config.spoolDirectory)
    private val activeRevocations = AtomicInteger()
    private val activeUpload = AtomicReference<RemoteUploadContext?>(null)
    private val activeCancellation = AtomicReference<CancellationRequest?>(null)
    private val worker = Thread(::runWorker, "FileLogger-Remote").apply {
        isDaemon = true
        start()
    }

    override fun write(record: LogRecord) {
        synchronized(controlLock) {
            if (state.get() != WorkerState.RUNNING || !deliveryEnabled() || !reserveUnpersistedRecord()) {
                dropped.incrementAndGet()
                return
            }
            if (!queue.offer(QueueItem.Record(record))) {
                unpersistedRecords.decrementAndGet()
                dropped.incrementAndGet()
            }
        }
    }

    override fun flush(timeoutMillis: Long): Boolean {
        require(timeoutMillis > 0) { "timeoutMillis must be greater than zero" }
        val deadlineNanos = deadlineNanos(timeoutMillis)
        val latch = CountDownLatch(1)
        val result = AtomicReference(false)
        val queued = synchronized(controlLock) {
            if (state.get() != WorkerState.RUNNING) return@synchronized false
            offerControl(QueueItem.Flush(latch, result), remainingMillis(deadlineNanos))
        }
        if (!queued) return false
        return latch.await(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS) && result.get()
    }

    override fun close(timeoutMillis: Long): Boolean {
        require(timeoutMillis > 0) { "timeoutMillis must be greater than zero" }
        val deadlineNanos = deadlineNanos(timeoutMillis)
        val latch = CountDownLatch(1)
        val result = AtomicReference(false)
        val queued = synchronized(controlLock) {
            when (state.get()) {
                WorkerState.CLOSED -> return true
                WorkerState.FAILED, WorkerState.CLOSING -> return false
                WorkerState.RUNNING -> state.set(WorkerState.CLOSING)
            }
            offerControl(QueueItem.Close(latch, result), remainingMillis(deadlineNanos))
        }
        if (!queued) {
            worker.interrupt()
            return false
        }
        val completed = latch.await(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS)
        val joinMillis = remainingMillis(deadlineNanos)
        if (joinMillis > 0) worker.join(joinMillis)
        return completed && result.get() && !worker.isAlive
    }

    override fun diagnostics(): LogDiagnostics = LogDiagnostics(
        droppedAsyncRecords = dropped.get(),
        queuedAsyncRecords = queue.count { it is QueueItem.Record },
        asyncQueueCapacity = config.queueCapacity,
        uploadedRemoteBatches = uploaded.get(),
        failedRemoteBatches = failed.get(),
        pendingRemoteBatches = pendingFiles().size
    )

    override fun erasePendingData(timeoutMillis: Long): Boolean {
        return setCollectionEnabled(
            enabled = false,
            erasePendingData = true,
            timeoutMillis = timeoutMillis
        )
    }

    override fun setCollectionEnabled(
        enabled: Boolean,
        erasePendingData: Boolean,
        timeoutMillis: Long
    ): Boolean {
        require(timeoutMillis > 0) { "timeoutMillis must be greater than zero" }
        val deadlineNanos = deadlineNanos(timeoutMillis)
        if (enabled) {
            return synchronized(controlLock) {
                claimCoordinator.setDeliveryEnabled(true)
            }
        }

        synchronized(controlLock) { activeRevocations.incrementAndGet() }
        try {
            val cancellationCompleted = requestActiveUploadCancellation(deadlineNanos)

            if (!erasePendingData) {
                val paused = persistAcceptedBeforePause(deadlineNanos)
                val markerUpdated = synchronized(controlLock) {
                    claimCoordinator.setDeliveryEnabled(false)
                }
                return paused && markerUpdated && (cancellationCompleted || activeUpload.get() == null)
            }

            val markerUpdated = synchronized(controlLock) {
                claimCoordinator.setDeliveryEnabled(false)
            }
            if (!markerUpdated) return false

            val latch = CountDownLatch(1)
            val result = AtomicReference(false)
            val queued = synchronized(controlLock) {
                if (state.get() != WorkerState.RUNNING) return@synchronized false
                offerControl(QueueItem.Clear(latch, result, deadlineNanos), remainingMillis(deadlineNanos))
            }
            if (queued) {
                val erased = latch.await(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS) && result.get()
                return erased && (cancellationCompleted || activeUpload.get() == null)
            }
            discardQueuedRecords()
            val erased = claimCoordinator.erasePendingData(deadlineNanos)
            return erased && (cancellationCompleted || activeUpload.get() == null)
        } finally {
            activeRevocations.decrementAndGet()
        }
    }

    private fun persistAcceptedBeforePause(deadlineNanos: Long): Boolean {
        if (state.get() != WorkerState.RUNNING) return unpersistedRecords.get() == 0L
        val latch = CountDownLatch(1)
        val result = AtomicReference(false)
        val queued = synchronized(controlLock) {
            offerControl(QueueItem.Pause(latch, result), remainingMillis(deadlineNanos))
        }
        if (!queued) return false
        return latch.await(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS) && result.get()
    }

    private fun runWorker() {
        val records = mutableListOf<LogRecord>()
        var closedNormally = false
        try {
            while (true) {
                when (val item = queue.take()) {
                    is QueueItem.Record -> {
                        records += item.record
                        if (records.size >= config.batchSize) {
                            if (persist(records)) {
                                markPersisted(records.size)
                                records.clear()
                                uploadPending()
                            } else {
                                uploadPending()
                            }
                        }
                    }
                    is QueueItem.Flush -> {
                        val persisted = records.isEmpty() || (deliveryEnabled() && persist(records))
                        if (persisted && records.isNotEmpty()) {
                            markPersisted(records.size)
                            records.clear()
                        }
                        val uploaded = uploadPending()
                        item.result.set(persisted && uploaded)
                        item.latch.countDown()
                    }
                    is QueueItem.Pause -> {
                        val persisted = records.isEmpty() || persist(records, allowWhilePausing = true)
                        if (persisted && records.isNotEmpty()) {
                            markPersisted(records.size)
                            records.clear()
                        }
                        item.result.set(persisted)
                        item.latch.countDown()
                    }
                    is QueueItem.Clear -> {
                        markPersisted(records.size)
                        records.clear()
                        discardQueuedRecords()
                        item.result.set(claimCoordinator.erasePendingData(item.deadlineNanos))
                        item.latch.countDown()
                    }
                    is QueueItem.Close -> {
                        val persisted = records.isEmpty() || (deliveryEnabled() && persist(records))
                        if (persisted && records.isNotEmpty()) {
                            markPersisted(records.size)
                            records.clear()
                        }
                        item.result.set(persisted && uploadPending())
                        item.latch.countDown()
                        closedNormally = true
                        return
                    }
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Throwable) {
            failed.incrementAndGet()
        } finally {
            if (closedNormally) {
                state.set(WorkerState.CLOSED)
            } else {
                state.set(WorkerState.FAILED)
                discardUnconsumableRecords(records)
                failQueuedItems()
            }
        }
    }

    private fun persist(records: List<LogRecord>, allowWhilePausing: Boolean = false): Boolean {
        if (!allowWhilePausing && !deliveryEnabled()) return false
        if (pendingFiles().size >= config.maxPendingBatches) {
            failed.incrementAndGet()
            return false
        }
        return runCatching {
            val bytes = records.joinToString(separator = "") { formatter.format(it) }.toByteArray()
            val payload = if (config.gzipEnabled) gzip(bytes) else bytes
            val suffix = if (config.gzipEnabled) ".jsonl.gz" else ".jsonl"
            val id = "v2-${UUID.randomUUID()}"
            spoolStore.persist(payload, suffix, id)
        }.getOrDefault(false).also { persisted ->
            if (!persisted) {
                failed.incrementAndGet()
            }
        }
    }

    private fun uploadPending(): Boolean {
        if (!deliveryEnabled()) return pendingFiles().isEmpty()
        if (pendingFiles().isEmpty()) return true
        var allUploaded = true
        val unreadableBatches = mutableSetOf<String>()
        while (true) {
            val claim = runCatching {
                synchronized(controlLock) {
                    if (!deliveryEnabled()) null else claimCoordinator.claimNext(unreadableBatches)
                }
            }.getOrElse {
                failed.incrementAndGet()
                return false
            } ?: break
            val file = claim.file
            val batchId = runCatching { identityStore.batchIdFor(claim.originalName) }.getOrElse {
                failed.incrementAndGet()
                claim.complete(uploaded = false)
                return false
            }
            val payload = runCatching { claim.readPayload() }.getOrNull()
            if (payload == null) {
                failed.incrementAndGet()
                allUploaded = false
                if (!claim.complete(uploaded = false)) return false
                unreadableBatches += claim.originalName
                continue
            }
            val batch = RemoteLogBatch(
                payload = payload,
                contentEncoding = if (file.name.endsWith(".gz")) "gzip" else null
            )
            if (uploadWithRetry(batch, batchId)) {
                if (claim.complete(uploaded = true)) {
                    uploaded.incrementAndGet()
                } else {
                    allUploaded = false
                    break
                }
            } else {
                failed.incrementAndGet()
                allUploaded = false
                claim.complete(uploaded = false)
                break
            }
        }
        return allUploaded && pendingFiles().isEmpty()
    }

    private fun uploadWithRetry(batch: RemoteLogBatch, batchId: String): Boolean {
        repeat(config.maxRetries + 1) { attempt ->
            if (!deliveryEnabled()) return false
            beforeUploadRegistration()
            val context = RemoteUploadContext(
                batchId = batchId,
                attemptId = UUID.randomUUID().toString(),
                attemptNumber = attempt + 1
            )
            val registered = synchronized(controlLock) {
                if (!deliveryEnabled()) {
                    false
                } else {
                    activeUpload.set(context)
                    true
                }
            }
            if (!registered) return false
            val uploaded = try {
                runCatching {
                    identifiedUploader?.upload(batch, context) ?: uploader!!.upload(batch)
                }.getOrDefault(false)
            } finally {
                activeUpload.compareAndSet(context, null)
            }
            if (uploaded) return true
            if (attempt < config.maxRetries && deliveryEnabled()) {
                val delay = config.initialBackoffMillis * (1L shl attempt.coerceAtMost(20))
                runCatching { sleeper(delay) }
            }
        }
        return false
    }

    private fun pendingFiles(): List<File> =
        claimCoordinator.pendingFiles()

    private fun offerControl(item: QueueItem, timeoutMillis: Long): Boolean =
        queue.offer(item, timeoutMillis, TimeUnit.MILLISECONDS)

    private fun reserveUnpersistedRecord(): Boolean {
        while (true) {
            val current = unpersistedRecords.get()
            if (current >= maxUnpersistedRecords) return false
            if (unpersistedRecords.compareAndSet(current, current + 1)) return true
        }
    }

    private fun markPersisted(count: Int) {
        if (count > 0) unpersistedRecords.addAndGet(-count.toLong())
    }

    private fun deliveryEnabled(): Boolean =
        activeRevocations.get() == 0 && claimCoordinator.isDeliveryEnabled()

    private fun requestActiveUploadCancellation(deadlineNanos: Long): Boolean {
        val context = activeUpload.get() ?: return true
        val cancellable = identifiedUploader as? CancellableIdentifiedRemoteLogUploader ?: return false
        while (true) {
            val existing = activeCancellation.get()
            if (existing != null) {
                if (existing.context.attemptId != context.attemptId) return false
                return existing.completed.await(
                    remainingMillis(deadlineNanos),
                    TimeUnit.MILLISECONDS
                ) && existing.accepted.get()
            }
            val request = CancellationRequest(context)
            if (!activeCancellation.compareAndSet(null, request)) continue
            Thread(
                {
                    try {
                        request.accepted.set(
                            runCatching { cancellable.cancelUpload(context) }.getOrDefault(false)
                        )
                    } finally {
                        activeCancellation.compareAndSet(request, null)
                        request.completed.countDown()
                    }
                },
                "FileLogger-Remote-Cancel"
            ).apply {
                isDaemon = true
                start()
            }
            return request.completed.await(
                remainingMillis(deadlineNanos),
                TimeUnit.MILLISECONDS
            ) && request.accepted.get()
        }
    }

    private fun discardQueuedRecords() {
        val retainedControls = mutableListOf<QueueItem>()
        while (true) {
            when (val item = queue.poll() ?: break) {
                is QueueItem.Record -> {
                    dropped.incrementAndGet()
                    markPersisted(1)
                }
                else -> retainedControls += item
            }
        }
        retainedControls.forEach(queue::offer)
    }

    private fun discardUnconsumableRecords(records: MutableList<LogRecord>) {
        if (records.isEmpty()) return
        dropped.addAndGet(records.size.toLong())
        markPersisted(records.size)
        records.clear()
    }

    private fun failQueuedItems() {
        while (true) {
            when (val item = queue.poll() ?: return) {
                is QueueItem.Record -> {
                    dropped.incrementAndGet()
                    markPersisted(1)
                }
                is QueueItem.Flush -> {
                    item.result.set(false)
                    item.latch.countDown()
                }
                is QueueItem.Pause -> {
                    item.result.set(false)
                    item.latch.countDown()
                }
                is QueueItem.Clear -> {
                    item.result.set(false)
                    item.latch.countDown()
                }
                is QueueItem.Close -> {
                    item.result.set(false)
                    item.latch.countDown()
                }
            }
        }
    }

    private fun deadlineNanos(timeoutMillis: Long): Long =
        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)

    private fun remainingMillis(deadlineNanos: Long): Long {
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0) return 0
        return TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1)
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(bytes) }
        return output.toByteArray()
    }

    private sealed interface QueueItem {
        data class Record(val record: LogRecord) : QueueItem
        data class Flush(
            val latch: CountDownLatch,
            val result: AtomicReference<Boolean>
        ) : QueueItem
        data class Pause(
            val latch: CountDownLatch,
            val result: AtomicReference<Boolean>
        ) : QueueItem
        data class Clear(
            val latch: CountDownLatch,
            val result: AtomicReference<Boolean>,
            val deadlineNanos: Long
        ) : QueueItem
        data class Close(
            val latch: CountDownLatch,
            val result: AtomicReference<Boolean>
        ) : QueueItem
    }

    private enum class WorkerState {
        RUNNING,
        CLOSING,
        CLOSED,
        FAILED
    }

    private class CancellationRequest(
        val context: RemoteUploadContext,
        val completed: CountDownLatch = CountDownLatch(1),
        val accepted: AtomicReference<Boolean> = AtomicReference(false)
    )

    companion object {
        @JvmStatic
        fun withIdentifiedUploader(
            uploader: IdentifiedRemoteLogUploader,
            config: RemoteLogConfig
        ): RemoteLogDestination = RemoteLogDestination(
            uploader = null,
            config = config,
            sleeper = Thread::sleep,
            identifiedUploader = uploader
        )
    }
}
