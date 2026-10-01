package com.filelogger.ui

import com.filelogger.LogLevel
import com.filelogger.StoredLogEntry

data class LogViewerFilter(
    val search: String = "",
    val levels: Set<LogLevel> = LogLevel.entries.toSet()
) {
    fun apply(entries: List<StoredLogEntry>): List<StoredLogEntry> {
        val term = search.trim()
        return entries.filter { entry ->
            entry.level in levels && (
                term.isEmpty() ||
                    entry.tag.contains(term, ignoreCase = true) ||
                    entry.message.contains(term, ignoreCase = true) ||
                    entry.throwable.orEmpty().contains(term, ignoreCase = true) ||
                    entry.attributes.any { (key, value) ->
                        key.contains(term, ignoreCase = true) ||
                            value.contains(term, ignoreCase = true)
                    }
                )
        }
    }
}
