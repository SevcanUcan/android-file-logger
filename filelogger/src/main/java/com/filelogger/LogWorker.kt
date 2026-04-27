package com.filelogger

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

internal object LogWorker {

    private val queue = LinkedBlockingQueue<LogRecord>()
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) {
            return
        }

        Thread {
            while (true) {
                val task = queue.take()
                LoggerEngine.writeToFile(task)
            }
        }.apply {
            isDaemon = true
            name = "FileLogger-Worker"
        }.start()
    }

    fun enqueue(task: LogRecord) {
        queue.offer(task)
    }
}
