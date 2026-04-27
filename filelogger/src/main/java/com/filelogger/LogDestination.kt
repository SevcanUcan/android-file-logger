package com.filelogger

interface LogDestination {
    fun write(record: LogRecord)
}

internal class CompositeLogDestination(
    private val destinations: List<LogDestination>
) : LogDestination {

    override fun write(record: LogRecord) {
        destinations.forEach { destination ->
            destination.write(record)
        }
    }
}
