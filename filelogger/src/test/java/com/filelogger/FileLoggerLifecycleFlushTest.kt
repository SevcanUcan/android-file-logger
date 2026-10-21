package com.filelogger

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

class FileLoggerLifecycleFlushTest {

    @Test
    fun `repeated lifecycle signals keep at most one flush task pending`() {
        val executor = QueueingExecutor()
        val submit = FileLogger::class.java.getDeclaredMethod(
            "submitLifecycleFlush",
            java.util.concurrent.ExecutorService::class.java
        ).apply { isAccessible = true }

        repeat(100) { submit.invoke(FileLogger, executor) }

        assertEquals("Lifecycle callbacks must not create an unbounded flush backlog", 1, executor.tasks.size)
        executor.tasks.removeFirst().run()
        submit.invoke(FileLogger, executor)
        assertEquals("A completed flush must permit one later lifecycle flush", 1, executor.tasks.size)
        executor.tasks.removeFirst().run()
        assertEquals(0, executor.tasks.size)
    }

    @Test
    fun `signal received during a running flush triggers one trailing flush`() {
        val executor = QueueingExecutor()
        val submit = FileLogger::class.java.getDeclaredMethod(
            "submitLifecycleFlush",
            java.util.concurrent.ExecutorService::class.java
        ).apply { isAccessible = true }
        val destinationField = FileLogger::class.java.getDeclaredField("destination")
            .apply { isAccessible = true }
        val previousDestination = destinationField.get(FileLogger)
        var flushes = 0
        val destination = object : FlushableLogDestination {
            override fun write(record: LogRecord) = Unit

            override fun flush(timeoutMillis: Long): Boolean {
                flushes++
                if (flushes == 1) {
                    submit.invoke(FileLogger, executor)
                }
                return true
            }
        }

        try {
            destinationField.set(FileLogger, destination)
            submit.invoke(FileLogger, executor)
            assertEquals(1, executor.tasks.size)

            executor.tasks.removeFirst().run()

            assertEquals(
                "A lifecycle signal observed during flush must request one trailing barrier",
                2,
                flushes
            )
            assertEquals("Coalescing must still use one executor task", 0, executor.tasks.size)
        } finally {
            destinationField.set(FileLogger, previousDestination)
        }
    }

    private class QueueingExecutor : AbstractExecutorService() {
        val tasks = ArrayDeque<Runnable>()
        private var shutdown = false

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        override fun shutdown() {
            shutdown = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            shutdown = true
            return mutableListOf<Runnable>().apply {
                while (tasks.isNotEmpty()) add(tasks.removeFirst())
            }
        }

        override fun isShutdown(): Boolean = shutdown

        override fun isTerminated(): Boolean = shutdown && tasks.isEmpty()

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated
    }
}
