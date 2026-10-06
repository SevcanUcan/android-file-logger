package com.filelogger.core

fun interface LogDestination {
    fun write(record: LogRecord)
}

class CompositeLogDestination(
    private val destinations: List<LogDestination>
) : LogDestination {

    constructor(vararg destinations: LogDestination) : this(destinations.toList())

    override fun write(record: LogRecord) {
        destinations.forEach { destination ->
            try {
                destination.write(record)
            } catch (_: Exception) {
                // A failing destination must not prevent delivery to the remaining destinations.
            }
        }
    }
}
