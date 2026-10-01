package com.filelogger.remote

import com.filelogger.CloseableLogDestination
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream

class RemoteLogDestination internal constructor(
    private val uploader: RemoteLogUploader,
    private val config: RemoteLogConfig,
    private val sleeper: (Long) -> Unit
) : CloseableLogDestination, DiagnosticLogDestination, ErasableLogDestination {

    constructor(
        uploader: RemoteLogUploader,
        config: RemoteLogConfig
    ) : this(uploader, config, Thread::sleep)

    private val formatter = JsonLogFormatter()
    private val queue = LinkedBlockingQueue<QueueItem>(config.queueCapacity)
    private val running = AtomicBoolean(true)
    private val dropped = AtomicLong()
    private val uploaded = AtomicLong()
    private val failed = AtomicLong()
    private val sequence = AtomicLong()
    private val worker = Thread(::runWorker, "FileLogger-Remote").apply {
        isDaemon = true
        start()
    }

    override fun write(record: LogRecord) {
        if (!running.get() || !queue.offer(QueueItem.Record(record))) {
            dropped.incrementAndGet()
        }
    }

    override fun flush(timeoutMillis: Long): Boolean {
        require(timeoutMillis > 0) { "timeoutMillis must be greater than zero" }
        if (!running.get()) return pendingFiles().isEmpty()
        val latch = CountDownLatch(1)
        val result = AtomicReference(false)
        if (!offerControl(QueueItem.Flush(latch, result), timeoutMillis)) return false
        return latch.await(timeoutMillis, TimeUnit.MILLISECONDS) && result.get()
    }

    override fun close(timeoutMillis: Long): Boolean {
        require(timeoutMillis > 0) { "timeoutMillis must be greater than zero" }
        if (!running.compareAndSet(true, false)) return !worker.isAlive
        val latch = CountDownLatch(1)
        val result = AtomicReference(false)
        if (!offerControl(QueueItem.Close(latch, result), timeoutMillis)) return false
        val completed = latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        worker.join(timeoutMillis)
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
        require(timeoutMillis > 0) { "timeoutMillis must be greater than zero" }
        if (!running.get()) return eraseSpoolFiles()
        val latch = CountDownLatch(1)
        val result = AtomicReference(false)
        if (!offerControl(QueueItem.Clear(latch, result), timeoutMillis)) return false
        return latch.await(timeoutMillis, TimeUnit.MILLISECONDS) && result.get()
    }

    private fun runWorker() {
        val records = mutableListOf<LogRecord>()
        while (true) {
            when (val item = queue.take()) {
                is QueueItem.Record -> {
                    records += item.record
                    if (records.size >= config.batchSize) {
                        persist(records)
                        records.clear()
                        uploadPending()
                    }
                }
                is QueueItem.Flush -> {
                    if (records.isNotEmpty()) {
                        persist(records)
                        records.clear()
                    }
                    item.result.set(uploadPending())
                    item.latch.countDown()
                }
                is QueueItem.Clear -> {
                    records.clear()
                    item.result.set(eraseSpoolFiles())
                    item.latch.countDown()
                }
                is QueueItem.Close -> {
                    if (records.isNotEmpty()) persist(records)
                    item.result.set(uploadPending())
                    item.latch.countDown()
                    return
                }
            }
        }
    }

    private fun persist(records: List<LogRecord>) {
        if (!config.spoolDirectory.exists() && !config.spoolDirectory.mkdirs()) {
            failed.incrementAndGet()
            return
        }
        trimPendingFiles()
        val bytes = records.joinToString(separator = "") { formatter.format(it) }.toByteArray()
        val payload = if (config.gzipEnabled) gzip(bytes) else bytes
        val suffix = if (config.gzipEnabled) ".jsonl.gz" else ".jsonl"
        val id = "${System.currentTimeMillis()}_${sequence.incrementAndGet()}"
        val temp = File(config.spoolDirectory, "batch_$id.tmp")
        val target = File(config.spoolDirectory, "batch_$id$suffix")
        runCatching {
            temp.writeBytes(payload)
            check(temp.renameTo(target)) { "Failed to commit remote log batch" }
        }.onFailure {
            temp.delete()
            failed.incrementAndGet()
        }
    }

    private fun uploadPending(): Boolean {
        var allUploaded = true
        pendingFiles().forEach { file ->
            val batch = RemoteLogBatch(
                payload = runCatching { file.readBytes() }.getOrElse {
                    failed.incrementAndGet()
                    allUploaded = false
                    return@forEach
                },
                contentEncoding = if (file.name.endsWith(".gz")) "gzip" else null
            )
            if (uploadWithRetry(batch)) {
                if (file.delete()) uploaded.incrementAndGet() else allUploaded = false
            } else {
                failed.incrementAndGet()
                allUploaded = false
            }
        }
        return allUploaded && pendingFiles().isEmpty()
    }

    private fun uploadWithRetry(batch: RemoteLogBatch): Boolean {
        repeat(config.maxRetries + 1) { attempt ->
            val uploaded = runCatching { uploader.upload(batch) }.getOrDefault(false)
            if (uploaded) return true
            if (attempt < config.maxRetries) {
                val delay = config.initialBackoffMillis * (1L shl attempt.coerceAtMost(20))
                runCatching { sleeper(delay) }
            }
        }
        return false
    }

    private fun trimPendingFiles() {
        val files = pendingFiles()
        val removeCount = (files.size - config.maxPendingBatches + 1).coerceAtLeast(0)
        files.take(removeCount).forEach { file ->
            if (!file.delete()) failed.incrementAndGet()
        }
    }

    private fun pendingFiles(): List<File> =
        config.spoolDirectory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith("batch_") && !it.name.endsWith(".tmp") }
            .sortedBy { it.name }

    private fun eraseSpoolFiles(): Boolean {
        val files = config.spoolDirectory.listFiles()
            .orEmpty()
            .filter { file -> file.isFile && (file.name.startsWith("batch_") || file.name.endsWith(".tmp")) }
        return files.map { file -> file.delete() || !file.exists() }.all { it }
    }

    private fun offerControl(item: QueueItem, timeoutMillis: Long): Boolean =
        queue.offer(item, timeoutMillis, TimeUnit.MILLISECONDS)

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
        data class Clear(
            val latch: CountDownLatch,
            val result: AtomicReference<Boolean>
        ) : QueueItem
        data class Close(
            val latch: CountDownLatch,
            val result: AtomicReference<Boolean>
        ) : QueueItem
    }
}
