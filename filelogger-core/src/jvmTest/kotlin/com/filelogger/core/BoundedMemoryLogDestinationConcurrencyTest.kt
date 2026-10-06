package com.filelogger.core

import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

class BoundedMemoryLogDestinationConcurrencyTest {

    @Test
    fun keepsItsBoundUnderConcurrentWrites() {
        val capacity = 128
        val workerCount = 8
        val recordsPerWorker = 1_000
        val destination = BoundedMemoryLogDestination(capacity)
        val start = CountDownLatch(1)

        val workers = List(workerCount) { worker ->
            thread(start = true) {
                start.await()
                repeat(recordsPerWorker) { index ->
                    destination.write(
                        LogRecord(
                            timestampMillis = index.toLong(),
                            level = LogLevel.INFO,
                            tag = "worker-$worker",
                            message = "record-$index"
                        )
                    )
                }
            }
        }

        start.countDown()
        workers.forEach(Thread::join)

        assertEquals(capacity, destination.snapshot().size)
    }
}
