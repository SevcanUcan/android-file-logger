package com.filelogger

data class LogDiagnostics(
    val totalRecords: Long = 0,
    val debugRecords: Long = 0,
    val infoRecords: Long = 0,
    val warningRecords: Long = 0,
    val errorRecords: Long = 0,
    val droppedAsyncRecords: Long = 0,
    val queuedAsyncRecords: Int = 0,
    val asyncQueueCapacity: Int = 0,
    val fileWriteFailures: Long = 0,
    val fileFlushFailures: Long = 0,
    val fileRotations: Long = 0,
    val fileWriteCount: Long = 0,
    val totalFileWriteDurationNanos: Long = 0,
    val uploadedRemoteBatches: Long = 0,
    val failedRemoteBatches: Long = 0,
    val pendingRemoteBatches: Int = 0,
    val currentLogFiles: List<String> = emptyList()
) {
    operator fun plus(other: LogDiagnostics): LogDiagnostics {
        return LogDiagnostics(
            totalRecords = totalRecords + other.totalRecords,
            debugRecords = debugRecords + other.debugRecords,
            infoRecords = infoRecords + other.infoRecords,
            warningRecords = warningRecords + other.warningRecords,
            errorRecords = errorRecords + other.errorRecords,
            droppedAsyncRecords = droppedAsyncRecords + other.droppedAsyncRecords,
            queuedAsyncRecords = queuedAsyncRecords + other.queuedAsyncRecords,
            asyncQueueCapacity = asyncQueueCapacity + other.asyncQueueCapacity,
            fileWriteFailures = fileWriteFailures + other.fileWriteFailures,
            fileFlushFailures = fileFlushFailures + other.fileFlushFailures,
            fileRotations = fileRotations + other.fileRotations,
            fileWriteCount = fileWriteCount + other.fileWriteCount,
            totalFileWriteDurationNanos = totalFileWriteDurationNanos + other.totalFileWriteDurationNanos,
            uploadedRemoteBatches = uploadedRemoteBatches + other.uploadedRemoteBatches,
            failedRemoteBatches = failedRemoteBatches + other.failedRemoteBatches,
            pendingRemoteBatches = pendingRemoteBatches + other.pendingRemoteBatches,
            currentLogFiles = currentLogFiles + other.currentLogFiles
        )
    }
}

interface DiagnosticLogDestination {
    fun diagnostics(): LogDiagnostics
}
