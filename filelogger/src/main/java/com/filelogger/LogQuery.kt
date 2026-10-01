package com.filelogger

data class LogQuery(
    val levels: Set<LogLevel> = emptySet(),
    val tags: Set<String> = emptySet(),
    val attributes: Map<String, String> = emptyMap(),
    val keyword: String? = null,
    val fromTimestampMillis: Long? = null,
    val toTimestampMillis: Long? = null,
    val limit: Int = 1_000
) {
    init {
        require(limit > 0) { "limit must be greater than zero" }
        require(
            fromTimestampMillis == null ||
                toTimestampMillis == null ||
                fromTimestampMillis <= toTimestampMillis
        ) { "fromTimestampMillis must not be greater than toTimestampMillis" }
    }
}

data class StoredLogEntry(
    val timestampMillis: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwable: String?,
    val threadName: String,
    val processName: String,
    val attributes: Map<String, String> = emptyMap(),
    val sessionId: String?,
    val sourceFileName: String
)
