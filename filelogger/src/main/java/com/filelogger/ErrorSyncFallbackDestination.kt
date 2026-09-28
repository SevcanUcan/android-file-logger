package com.filelogger

internal class ErrorSyncFallbackDestination(
    private val asyncDestination: FlushableLogDestination,
    private val syncDestination: FlushableLogDestination
) : CloseableLogDestination, DiagnosticLogDestination {

    private val writeLock = Any()

    override fun write(record: LogRecord) {
        synchronized(writeLock) {
            if (record.level == LogLevel.ERROR) {
                asyncDestination.flush()
                syncDestination.write(record)
                syncDestination.flush()
                return
            }

            asyncDestination.write(record)
        }
    }

    override fun flush(timeoutMillis: Long): Boolean {
        return synchronized(writeLock) {
            val asyncFlushed = asyncDestination.flush(timeoutMillis)
            val syncFlushed = syncDestination.flush(timeoutMillis)
            asyncFlushed && syncFlushed
        }
    }

    override fun close(timeoutMillis: Long): Boolean {
        return synchronized(writeLock) {
            val asyncClosed = (asyncDestination as? CloseableLogDestination)
                ?.close(timeoutMillis)
                ?: asyncDestination.flush(timeoutMillis)
            val syncFlushed = syncDestination.flush(timeoutMillis)
            asyncClosed && syncFlushed
        }
    }

    override fun diagnostics(): LogDiagnostics {
        return (asyncDestination as? DiagnosticLogDestination)
            ?.diagnostics()
            ?: LogDiagnostics()
    }
}
