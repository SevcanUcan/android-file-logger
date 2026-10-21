package com.filelogger

interface LogDestination {
    fun write(record: LogRecord)
}

interface FlushableLogDestination : LogDestination {
    fun flush(timeoutMillis: Long = DEFAULT_FLUSH_TIMEOUT_MILLIS): Boolean

    companion object {
        const val DEFAULT_FLUSH_TIMEOUT_MILLIS: Long = 5_000
    }
}

interface CloseableLogDestination : FlushableLogDestination {
    fun close(
        timeoutMillis: Long = FlushableLogDestination.DEFAULT_FLUSH_TIMEOUT_MILLIS
    ): Boolean
}

interface ErasableLogDestination {
    fun erasePendingData(
        timeoutMillis: Long = FlushableLogDestination.DEFAULT_FLUSH_TIMEOUT_MILLIS
    ): Boolean
}

interface CollectionControlledLogDestination {
    fun setCollectionEnabled(
        enabled: Boolean,
        erasePendingData: Boolean = false,
        timeoutMillis: Long = FlushableLogDestination.DEFAULT_FLUSH_TIMEOUT_MILLIS
    ): Boolean
}

internal interface ExportFlushableLogDestination {
    fun flushForExport(timeoutMillis: Long): Boolean
}

internal class CompositeLogDestination(
    private val destinations: List<LogDestination>
) : CloseableLogDestination,
    DiagnosticLogDestination,
    ErasableLogDestination,
    CollectionControlledLogDestination,
    ExportFlushableLogDestination {

    override fun write(record: LogRecord) {
        destinations.forEach { destination ->
            try {
                destination.write(record)
            } catch (_: Exception) {
                // Destination failures must not crash the host app.
            }
        }
    }

    override fun flush(timeoutMillis: Long): Boolean {
        val deadlineNanos = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        return destinations
            .filterIsInstance<FlushableLogDestination>()
            .map { destination ->
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0) return@map false
                val remainingMillis = java.util.concurrent.TimeUnit.NANOSECONDS
                    .toMillis(remainingNanos)
                    .coerceAtLeast(1)
                try {
                    destination.flush(remainingMillis)
                } catch (_: Exception) {
                    false
                }
            }
            .all { flushed -> flushed }
    }

    override fun close(timeoutMillis: Long): Boolean {
        return destinations
            .map { destination ->
                try {
                    when (destination) {
                        is CloseableLogDestination -> destination.close(timeoutMillis)
                        is FlushableLogDestination -> destination.flush(timeoutMillis)
                        else -> true
                    }
                } catch (_: Exception) {
                    false
                }
            }
            .all { closed -> closed }
    }

    override fun diagnostics(): LogDiagnostics {
        return destinations
            .filterIsInstance<DiagnosticLogDestination>()
            .fold(LogDiagnostics()) { diagnostics, destination ->
                try {
                    diagnostics + destination.diagnostics()
                } catch (_: Exception) {
                    diagnostics
                }
            }
    }

    override fun erasePendingData(timeoutMillis: Long): Boolean {
        return destinations
            .filterIsInstance<ErasableLogDestination>()
            .map { destination ->
                runCatching { destination.erasePendingData(timeoutMillis) }.getOrDefault(false)
            }
            .all { erased -> erased }
    }

    override fun setCollectionEnabled(
        enabled: Boolean,
        erasePendingData: Boolean,
        timeoutMillis: Long
    ): Boolean {
        val deadlineNanos = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        return destinations
            .filterIsInstance<CollectionControlledLogDestination>()
            .map { destination ->
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0) return@map false
                val remainingMillis = java.util.concurrent.TimeUnit.NANOSECONDS
                    .toMillis(remainingNanos)
                    .coerceAtLeast(1)
                runCatching {
                    destination.setCollectionEnabled(enabled, erasePendingData, remainingMillis)
                }.getOrDefault(false)
            }
            .all { controlled -> controlled }
    }

    override fun flushForExport(timeoutMillis: Long): Boolean {
        val deadlineNanos = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        return destinations
            .filterNot { it is CollectionControlledLogDestination }
            .map { destination ->
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0) return@map false
                val remainingMillis = java.util.concurrent.TimeUnit.NANOSECONDS
                    .toMillis(remainingNanos)
                    .coerceAtLeast(1)
                runCatching {
                    when (destination) {
                        is ExportFlushableLogDestination -> destination.flushForExport(remainingMillis)
                        is FlushableLogDestination -> destination.flush(remainingMillis)
                        else -> true
                    }
                }.getOrDefault(false)
            }
            .all { flushed -> flushed }
    }
}
