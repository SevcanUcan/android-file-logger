package com.filelogger

data class LogDiagnostics(
    val droppedAsyncRecords: Long = 0,
    val queuedAsyncRecords: Int = 0,
    val asyncQueueCapacity: Int = 0,
    val currentLogFiles: List<String> = emptyList()
) {
    operator fun plus(other: LogDiagnostics): LogDiagnostics {
        return LogDiagnostics(
            droppedAsyncRecords = droppedAsyncRecords + other.droppedAsyncRecords,
            queuedAsyncRecords = queuedAsyncRecords + other.queuedAsyncRecords,
            asyncQueueCapacity = asyncQueueCapacity + other.asyncQueueCapacity,
            currentLogFiles = currentLogFiles + other.currentLogFiles
        )
    }
}

internal interface DiagnosticLogDestination {
    fun diagnostics(): LogDiagnostics
}
