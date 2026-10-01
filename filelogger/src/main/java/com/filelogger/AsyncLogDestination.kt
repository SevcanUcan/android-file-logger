package com.filelogger

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class AsyncLogDestination(
    private val delegate: LogDestination,
    private val workerName: String = "FileLogger-Worker",
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val overflowStrategy: AsyncOverflowStrategy = AsyncOverflowStrategy.DROP_OLDEST
) : CloseableLogDestination {

    private val queue = LinkedBlockingDeque<QueueItem>(queueCapacity)
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val droppedRecordCount = AtomicLong(0)
    private val terminationLatch = CountDownLatch(1)
    private val terminationResult = AtomicReference(true)
    private val lifecycleLock = Any()
    @Volatile
    private var workerThread: Thread? = null

    override fun write(record: LogRecord) {
        synchronized(lifecycleLock) {
            if (closed.get()) {
                droppedRecordCount.incrementAndGet()
                return
            }
            startIfNeeded()
            enqueueRecord(QueueItem.Record(record))
        }
    }

    override fun flush(timeoutMillis: Long): Boolean {
        if (closed.get()) {
            return awaitTermination(timeoutMillis)
        }

        if (!started.get() && queue.isEmpty()) {
            return true
        }

        val latch = CountDownLatch(1)
        val result = AtomicReference(true)
        val queued = synchronized(lifecycleLock) {
            if (closed.get()) {
                return@synchronized false
            }
            startIfNeeded()
            offer(QueueItem.Flush(latch, result, timeoutMillis), timeoutMillis)
        }

        if (!queued) {
            return if (closed.get()) awaitTermination(timeoutMillis) else false
        }

        return try {
            latch.await(timeoutMillis, TimeUnit.MILLISECONDS) && result.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    override fun close(timeoutMillis: Long): Boolean {
        val closeItem = synchronized(lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return@synchronized null
            }

            if (!started.get()) {
                terminationResult.set(closeDelegate(timeoutMillis))
                terminationLatch.countDown()
                return@synchronized null
            }

            QueueItem.Close(timeoutMillis)
        }

        if (closeItem != null && !offer(closeItem, timeoutMillis)) {
            workerThread?.interrupt()
        }

        return awaitTermination(timeoutMillis)
    }

    internal fun droppedRecords(): Long = droppedRecordCount.get()

    internal fun queuedRecords(): Int = queue.count { item -> item is QueueItem.Record }

    internal fun queueCapacity(): Int = queue.remainingCapacity() + queue.size

    internal fun destinationDiagnostics(): LogDiagnostics =
        (delegate as? DiagnosticLogDestination)?.diagnostics() ?: LogDiagnostics()

    private fun enqueueRecord(item: QueueItem.Record) {
        when (overflowStrategy) {
            AsyncOverflowStrategy.DROP_NEWEST -> {
                if (!queue.offer(item)) {
                    droppedRecordCount.incrementAndGet()
                }
            }

            AsyncOverflowStrategy.DROP_OLDEST -> {
                if (queue.offer(item)) {
                    return
                }

                if (queue.offer(item)) {
                    return
                }

                if (removeOldestRecord()) {
                    droppedRecordCount.incrementAndGet()
                }

                if (!queue.offer(item)) {
                    droppedRecordCount.incrementAndGet()
                }
            }

            AsyncOverflowStrategy.BLOCK -> {
                try {
                    queue.put(item)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    droppedRecordCount.incrementAndGet()
                }
            }
        }
    }

    private fun removeOldestRecord(): Boolean {
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            if (iterator.next() is QueueItem.Record) {
                iterator.remove()
                return true
            }
        }
        return false
    }

    private fun startIfNeeded() {
        if (!started.compareAndSet(false, true)) {
            return
        }

        workerThread = Thread {
            var result = true
            try {
                while (true) {
                    when (val item = queue.take()) {
                        is QueueItem.Record -> delegate.write(item.record)
                        is QueueItem.Flush -> {
                            item.result.set(flushDelegate(item.timeoutMillis))
                            item.latch.countDown()
                        }

                        is QueueItem.Close -> {
                            result = closeDelegate(item.timeoutMillis)
                            return@Thread
                        }
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                result = closeDelegate(FlushableLogDestination.DEFAULT_FLUSH_TIMEOUT_MILLIS)
            } catch (_: Exception) {
                closed.set(true)
                result = false
                closeDelegate(FlushableLogDestination.DEFAULT_FLUSH_TIMEOUT_MILLIS)
            } finally {
                terminationResult.set(result)
                terminationLatch.countDown()
            }
        }.apply {
            isDaemon = true
            name = workerName
            start()
        }
    }

    private fun flushDelegate(timeoutMillis: Long): Boolean {
        return (delegate as? FlushableLogDestination)
            ?.flush(timeoutMillis)
            ?: true
    }

    private fun closeDelegate(timeoutMillis: Long): Boolean {
        return try {
            when (delegate) {
                is CloseableLogDestination -> delegate.close(timeoutMillis)
                is FlushableLogDestination -> delegate.flush(timeoutMillis)
                else -> true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun offer(item: QueueItem, timeoutMillis: Long): Boolean {
        return try {
            queue.offer(item, timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun awaitTermination(timeoutMillis: Long): Boolean {
        return try {
            terminationLatch.await(timeoutMillis, TimeUnit.MILLISECONDS) && terminationResult.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private sealed interface QueueItem {
        data class Record(val record: LogRecord) : QueueItem

        data class Flush(
            val latch: CountDownLatch,
            val result: AtomicReference<Boolean>,
            val timeoutMillis: Long
        ) : QueueItem

        data class Close(val timeoutMillis: Long) : QueueItem
    }

    private companion object {
        const val DEFAULT_QUEUE_CAPACITY = 1024
    }
}

internal class AsyncLogDiagnosticsDestination(
    private val asyncDestination: AsyncLogDestination
) : CloseableLogDestination, DiagnosticLogDestination {

    override fun write(record: LogRecord) = asyncDestination.write(record)

    override fun flush(timeoutMillis: Long): Boolean = asyncDestination.flush(timeoutMillis)

    override fun close(timeoutMillis: Long): Boolean = asyncDestination.close(timeoutMillis)

    override fun diagnostics(): LogDiagnostics {
        return LogDiagnostics(
            droppedAsyncRecords = asyncDestination.droppedRecords(),
            queuedAsyncRecords = asyncDestination.queuedRecords(),
            asyncQueueCapacity = asyncDestination.queueCapacity()
        ) + asyncDestination.destinationDiagnostics()
    }
}
