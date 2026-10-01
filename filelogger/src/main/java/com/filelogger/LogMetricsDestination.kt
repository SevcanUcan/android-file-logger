package com.filelogger

import java.util.concurrent.atomic.AtomicLong

internal class LogMetricsDestination : LogDestination, DiagnosticLogDestination {
    private val total = AtomicLong()
    private val debug = AtomicLong()
    private val info = AtomicLong()
    private val warning = AtomicLong()
    private val error = AtomicLong()

    override fun write(record: LogRecord) {
        total.incrementAndGet()
        when (record.level) {
            LogLevel.DEBUG -> debug.incrementAndGet()
            LogLevel.INFO -> info.incrementAndGet()
            LogLevel.WARN -> warning.incrementAndGet()
            LogLevel.ERROR -> error.incrementAndGet()
        }
    }

    override fun diagnostics(): LogDiagnostics = LogDiagnostics(
        totalRecords = total.get(),
        debugRecords = debug.get(),
        infoRecords = info.get(),
        warningRecords = warning.get(),
        errorRecords = error.get()
    )
}
