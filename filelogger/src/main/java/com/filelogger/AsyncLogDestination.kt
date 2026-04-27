package com.filelogger

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

internal class AsyncLogDestination(
    private val delegate: LogDestination,
    private val workerName: String = "FileLogger-Worker"
) : LogDestination {

    private val queue = LinkedBlockingQueue<LogRecord>()
    private val started = AtomicBoolean(false)

    override fun write(record: LogRecord) {
        startIfNeeded()
        queue.offer(record)
    }

    private fun startIfNeeded() {
        if (!started.compareAndSet(false, true)) {
            return
        }

        Thread {
            while (true) {
                delegate.write(queue.take())
            }
        }.apply {
            isDaemon = true
            name = workerName
        }.start()
    }
}
