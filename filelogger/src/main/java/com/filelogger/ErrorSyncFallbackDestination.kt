package com.filelogger

internal class ErrorSyncFallbackDestination(
    private val asyncDestination: FlushableLogDestination,
    private val syncDestination: FlushableLogDestination,
    private val useNonBlockingErrorWrite: () -> Boolean = {
        runCatching {
            android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        }.getOrDefault(false)
    }
) : CloseableLogDestination, DiagnosticLogDestination {

    private val writeLock = Any()
    private var pendingOrderingFailure = false

    override fun write(record: LogRecord) {
        synchronized(writeLock) {
            if (record.level == LogLevel.ERROR) {
                if (useNonBlockingErrorWrite()) {
                    (asyncDestination as? NonBlockingLogDestination)
                        ?.writeNonBlocking(record)
                        ?: asyncDestination.write(record)
                    return
                }
                if (!asyncDestination.flush()) {
                    pendingOrderingFailure = true
                }
                syncDestination.write(record)
                if (!syncDestination.flush()) {
                    pendingOrderingFailure = true
                }
                return
            }

            asyncDestination.write(record)
        }
    }

    override fun flush(timeoutMillis: Long): Boolean {
        return synchronized(writeLock) {
            val orderingFailure = pendingOrderingFailure
            pendingOrderingFailure = false
            val asyncFlushed = asyncDestination.flush(timeoutMillis)
            val syncFlushed = syncDestination.flush(timeoutMillis)
            !orderingFailure && asyncFlushed && syncFlushed
        }
    }

    override fun close(timeoutMillis: Long): Boolean {
        return synchronized(writeLock) {
            val orderingFailure = pendingOrderingFailure
            pendingOrderingFailure = false
            val asyncClosed = (asyncDestination as? CloseableLogDestination)
                ?.close(timeoutMillis)
                ?: asyncDestination.flush(timeoutMillis)
            val syncFlushed = syncDestination.flush(timeoutMillis)
            !orderingFailure && asyncClosed && syncFlushed
        }
    }

    override fun diagnostics(): LogDiagnostics {
        return (asyncDestination as? DiagnosticLogDestination)
            ?.diagnostics()
            ?: LogDiagnostics()
    }
}

internal interface NonBlockingLogDestination {
    fun writeNonBlocking(record: LogRecord)
}
