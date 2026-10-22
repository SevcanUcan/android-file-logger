package com.filelogger

internal class RedactingLogDestination(
    private val delegate: LogDestination,
    private val redactor: LogRedactor
) : CloseableLogDestination,
    DiagnosticLogDestination,
    ErasableLogDestination,
    CollectionControlledLogDestination,
    ExportFlushableLogDestination {

    override fun write(record: LogRecord) {
        val throwableText = record.throwableText ?: record.throwable?.stackTraceString()
        delegate.write(
            record.copy(
                message = redactor.redact(record.message),
                attributes = record.attributes.mapValues { (key, value) ->
                    SensitiveFieldPolicy.redactAttribute(key, value, redactor)
                },
                throwable = null,
                throwableText = throwableText?.let(redactor::redact)
            )
        )
    }

    override fun flush(timeoutMillis: Long): Boolean =
        (delegate as? FlushableLogDestination)?.flush(timeoutMillis) ?: true

    override fun close(timeoutMillis: Long): Boolean = when (delegate) {
        is CloseableLogDestination -> delegate.close(timeoutMillis)
        is FlushableLogDestination -> delegate.flush(timeoutMillis)
        else -> true
    }

    override fun diagnostics(): LogDiagnostics =
        (delegate as? DiagnosticLogDestination)?.diagnostics() ?: LogDiagnostics()

    override fun erasePendingData(timeoutMillis: Long): Boolean =
        (delegate as? ErasableLogDestination)?.erasePendingData(timeoutMillis) ?: true

    override fun setCollectionEnabled(
        enabled: Boolean,
        erasePendingData: Boolean,
        timeoutMillis: Long
    ): Boolean = (delegate as? CollectionControlledLogDestination)
        ?.setCollectionEnabled(enabled, erasePendingData, timeoutMillis)
        ?: true

    override fun flushForExport(timeoutMillis: Long): Boolean =
        (delegate as? ExportFlushableLogDestination)?.flushForExport(timeoutMillis)
            ?: (delegate as? FlushableLogDestination)?.flush(timeoutMillis)
            ?: true
}
