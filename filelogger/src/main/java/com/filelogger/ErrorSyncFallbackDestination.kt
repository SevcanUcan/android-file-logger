package com.filelogger

internal class ErrorSyncFallbackDestination(
    private val asyncDestination: FlushableLogDestination,
    private val syncDestination: FlushableLogDestination
) : CloseableLogDestination, DiagnosticLogDestination {

    override fun write(record: LogRecord) {
        if (record.level == LogLevel.ERROR) {
            syncDestination.write(record)
            syncDestination.flush()
            return
        }

        asyncDestination.write(record)
    }

    override fun flush(timeoutMillis: Long): Boolean {
        val asyncFlushed = asyncDestination.flush(timeoutMillis)
        val syncFlushed = syncDestination.flush(timeoutMillis)
        return asyncFlushed && syncFlushed
    }

    override fun close(timeoutMillis: Long): Boolean {
        val asyncClosed = (asyncDestination as? CloseableLogDestination)
            ?.close(timeoutMillis)
            ?: asyncDestination.flush(timeoutMillis)
        val syncFlushed = syncDestination.flush(timeoutMillis)
        return asyncClosed && syncFlushed
    }

    override fun diagnostics(): LogDiagnostics {
        return (asyncDestination as? DiagnosticLogDestination)
            ?.diagnostics()
            ?: LogDiagnostics()
    }
}
