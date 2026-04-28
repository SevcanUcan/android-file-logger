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

internal class CompositeLogDestination(
    private val destinations: List<LogDestination>
) : FlushableLogDestination {

    override fun write(record: LogRecord) {
        destinations.forEach { destination ->
            destination.write(record)
        }
    }

    override fun flush(timeoutMillis: Long): Boolean {
        return destinations
            .filterIsInstance<FlushableLogDestination>()
            .all { destination -> destination.flush(timeoutMillis) }
    }
}
