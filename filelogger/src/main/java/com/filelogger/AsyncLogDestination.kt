package com.filelogger

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class AsyncLogDestination(
    private val delegate: LogDestination,
    private val workerName: String = "FileLogger-Worker"
) : FlushableLogDestination {

    private val queue = LinkedBlockingQueue<QueueItem>()
    private val started = AtomicBoolean(false)

    override fun write(record: LogRecord) {
        startIfNeeded()
        queue.offer(QueueItem.Record(record))
    }

    override fun flush(timeoutMillis: Long): Boolean {
        if (!started.get() && queue.isEmpty()) {
            return true
        }

        startIfNeeded()
        val latch = CountDownLatch(1)
        queue.offer(QueueItem.Flush(latch))

        return try {
            latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun startIfNeeded() {
        if (!started.compareAndSet(false, true)) {
            return
        }

        Thread {
            while (true) {
                when (val item = queue.take()) {
                    is QueueItem.Record -> delegate.write(item.record)
                    is QueueItem.Flush -> item.latch.countDown()
                }
            }
        }.apply {
            isDaemon = true
            name = workerName
        }.start()
    }

    private sealed interface QueueItem {
        data class Record(val record: LogRecord) : QueueItem

        data class Flush(val latch: CountDownLatch) : QueueItem
    }
}
