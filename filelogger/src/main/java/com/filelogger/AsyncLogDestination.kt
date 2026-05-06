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
) : FlushableLogDestination {

    private val queue = LinkedBlockingDeque<QueueItem>(queueCapacity)
    private val started = AtomicBoolean(false)
    private val droppedRecordCount = AtomicLong(0)

    override fun write(record: LogRecord) {
        startIfNeeded()
        enqueueRecord(QueueItem.Record(record))
    }

    override fun flush(timeoutMillis: Long): Boolean {
        if (!started.get() && queue.isEmpty()) {
            return true
        }

        startIfNeeded()
        val latch = CountDownLatch(1)
        val result = AtomicReference(true)
        val queued = try {
            queue.offer(QueueItem.Flush(latch, result, timeoutMillis), timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        if (!queued) {
            return false
        }

        return try {
            latch.await(timeoutMillis, TimeUnit.MILLISECONDS) && result.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    internal fun droppedRecords(): Long {
        return droppedRecordCount.get()
    }

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

        Thread {
            while (true) {
                when (val item = queue.take()) {
                    is QueueItem.Record -> delegate.write(item.record)
                    is QueueItem.Flush -> {
                        item.result.set(flushDelegate(item.timeoutMillis))
                        item.latch.countDown()
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = workerName
        }.start()
    }

    private fun flushDelegate(timeoutMillis: Long): Boolean {
        return (delegate as? FlushableLogDestination)
            ?.flush(timeoutMillis)
            ?: true
    }

    private sealed interface QueueItem {
        data class Record(val record: LogRecord) : QueueItem

        data class Flush(
            val latch: CountDownLatch,
            val result: AtomicReference<Boolean>,
            val timeoutMillis: Long
        ) : QueueItem
    }

    private companion object {
        const val DEFAULT_QUEUE_CAPACITY = 1024
    }
}
