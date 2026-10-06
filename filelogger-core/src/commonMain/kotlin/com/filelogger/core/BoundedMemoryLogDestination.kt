package com.filelogger.core

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update

@OptIn(ExperimentalAtomicApi::class)
class BoundedMemoryLogDestination(
    val capacity: Int = DEFAULT_CAPACITY
) : LogDestination {
    private val records = AtomicReference<List<LogRecord>>(emptyList())

    init {
        require(capacity > 0) { "capacity must be greater than zero" }
    }

    override fun write(record: LogRecord) {
        records.update { current ->
            val retained = if (current.size >= capacity) current.drop(1) else current
            retained + record
        }
    }

    fun snapshot(): List<LogRecord> = records.load()

    fun clear() = records.store(emptyList())

    companion object {
        const val DEFAULT_CAPACITY: Int = 200
    }
}
