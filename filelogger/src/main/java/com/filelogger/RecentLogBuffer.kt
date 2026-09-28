package com.filelogger

import java.util.ArrayDeque

internal class RecentLogBuffer(
    private val capacity: Int
) : LogDestination {

    private val lock = Any()
    private val records = ArrayDeque<LogRecord>()

    override fun write(record: LogRecord) {
        if (capacity <= 0) {
            return
        }

        synchronized(lock) {
            while (records.size >= capacity) {
                records.removeFirst()
            }
            records.addLast(record)
        }
    }

    fun snapshot(): List<LogRecord> {
        return synchronized(lock) {
            records.toList()
        }
    }
}
