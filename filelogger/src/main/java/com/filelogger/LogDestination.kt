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

internal class CompositeLogDestination(
    private val destinations: List<LogDestination>
) : CloseableLogDestination, DiagnosticLogDestination {

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
        return destinations
            .filterIsInstance<FlushableLogDestination>()
            .map { destination ->
                try {
                    destination.flush(timeoutMillis)
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
}
