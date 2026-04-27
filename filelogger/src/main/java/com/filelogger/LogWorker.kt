package com.filelogger

import java.util.concurrent.LinkedBlockingQueue

internal object LogWorker {

    private val queue = LinkedBlockingQueue<LogTask>()

    fun start() {
        Thread {
            while (true) {
                val task = queue.take()

                LoggerEngine.writeToFile(
                    task.level,
                    task.tag,
                    task.message
                )
            }
        }.apply {
            isDaemon = true
            name = "FileLogger-Worker"
        }.start()
    }

    fun enqueue(task: LogTask) {
        queue.offer(task)
    }
}

internal data class LogTask(
    val level: String,
    val tag: String,
    val message: String
)
