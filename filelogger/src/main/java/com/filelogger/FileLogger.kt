package com.filelogger

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object FileLogger : Logger {

    private val lifecycleLock = Any()
    @Volatile
    internal var config: LoggerConfig = LoggerConfig()
    @Volatile
    private var destination: LogDestination? = null
    @Volatile
    private var delegate: Logger? = null
    @Volatile
    private var logDirectory: File? = null
    @Volatile
    private var exportDirectory: File? = null
    @Volatile
    private var packageName: String? = null
    @Volatile
    private var processName: String? = null
    @Volatile
    private var session: LogSession? = null
    @Volatile
    private var recentLogBuffer: RecentLogBuffer? = null
    private var previousUncaughtExceptionHandler: Thread.UncaughtExceptionHandler? = null
    private var installedUncaughtExceptionHandler: Thread.UncaughtExceptionHandler? = null
    private var lifecycleContext = WeakReference<Context>(null)
    private var lifecycleCallback: ComponentCallbacks2? = null
    private val tagMinimumLevels = ConcurrentHashMap<String, LogLevel>()

    fun init(
        context: Context,
        config: LoggerConfig = LoggerConfig()
    ) {
        val applicationContext = context.applicationContext
        val filesDir = applicationContext.filesDir
        val packageName = applicationContext.packageName
        val processName = ProcessNameResolver.resolve(applicationContext)
        val session = if (config.sessionLoggingEnabled) {
            LogSessionFactory.create(config)
        } else {
            null
        }
        val sessionLogDirectory = LogDirectoryResolver.resolve(filesDir, config, session)

        synchronized(lifecycleLock) {
            shutdownLocked(FlushableLogDestination.DEFAULT_FLUSH_TIMEOUT_MILLIS)
            tagMinimumLevels.clear()

            this.config = config
            this.packageName = packageName
            this.processName = processName
            this.session = session
            logDirectory = sessionLogDirectory
            exportDirectory = File(filesDir, "log_exports")
            recentLogBuffer = if (config.crashCaptureEnabled) {
                RecentLogBuffer(config.crashBufferSize)
            } else {
                null
            }

            val newDestination = LoggerEngine.createDefaultDestination(
                logDirectoryProvider = { logDirectory() },
                configProvider = { this.config },
                packageNameProvider = { packageName },
                processNameProvider = { processName },
                recentLogBuffer = recentLogBuffer
            )
            destination = newDestination
            delegate = DefaultLogger(
                destination = newDestination,
                processNameProvider = { processName },
                minimumLogLevel = this.config.minimumLogLevel,
                isLoggable = ::isLoggable
            )

            if (config.autoFlushOnAppBackground) {
                registerAutoFlush(applicationContext)
            }
            if (config.crashCaptureEnabled) {
                installCrashHandler()
            }
        }
    }

    override fun d(tag: String, msg: String) {
        logger().d(tag, msg)
    }

    override fun w(tag: String, msg: String) {
        logger().w(tag, msg)
    }

    override fun e(tag: String, msg: String, tr: Throwable?) {
        logger().e(tag, msg, tr)
    }

    fun d(tag: String, msg: () -> String) {
        if (isLoggable(LogLevel.DEBUG, tag)) {
            d(tag, msg())
        }
    }

    fun w(tag: String, msg: () -> String) {
        if (isLoggable(LogLevel.WARN, tag)) {
            w(tag, msg())
        }
    }

    fun e(tag: String, tr: Throwable? = null, msg: () -> String) {
        if (isLoggable(LogLevel.ERROR, tag)) {
            e(tag, msg(), tr)
        }
    }

    fun setTagMinimumLevel(tag: String, level: LogLevel?) {
        if (level == null) {
            tagMinimumLevels.remove(tag)
        } else {
            tagMinimumLevels[tag] = level
        }
    }

    fun clearTagMinimumLevels() {
        tagMinimumLevels.clear()
    }

    fun isLoggable(level: LogLevel, tag: String): Boolean {
        val minimumLevel = tagMinimumLevels[tag] ?: config.minimumLogLevel
        return level.priority >= minimumLevel.priority
    }

    fun session(): LogSession? {
        return session
    }

    fun flush(
        timeoutMillis: Long = FlushableLogDestination.DEFAULT_FLUSH_TIMEOUT_MILLIS
    ): Boolean {
        return (destination() as? FlushableLogDestination)
            ?.flush(timeoutMillis)
            ?: true
    }

    fun shutdown(
        timeoutMillis: Long = FlushableLogDestination.DEFAULT_FLUSH_TIMEOUT_MILLIS
    ): Boolean {
        require(timeoutMillis > 0) { "timeoutMillis must be greater than 0." }
        return synchronized(lifecycleLock) {
            shutdownLocked(timeoutMillis)
        }
    }

    fun diagnostics(): LogDiagnostics {
        val destinationDiagnostics = (destination() as? DiagnosticLogDestination)
            ?.diagnostics()
            ?: LogDiagnostics()

        return destinationDiagnostics.copy(
            currentLogFiles = logDirectory()
                .listFiles()
                .orEmpty()
                .filter { file -> file.isFile }
                .map { file -> file.name }
                .sorted()
        )
    }

    fun exportLogs(
        outputFile: File = defaultExportFile(),
        redactor: LogRedactor = LogRedactor.NONE
    ): File {
        flush()
        return LogExporter.export(
            logDirectory = logDirectory(),
            outputFile = outputFile,
            baseLogFileName = config.logFileName,
            redactor = redactor,
            metadataEntries = mapOf(
                "diagnostics.json" to DiagnosticBundle.toJson(
                    appPackageName = packageName ?: "unknown",
                    processName = processName ?: "unknown",
                    session = session,
                    generatedAtMillis = System.currentTimeMillis(),
                    diagnostics = diagnostics(),
                    config = config
                )
            )
        )
    }

    fun createShareIntent(
        archiveUri: Uri,
        mimeType: String = "application/zip"
    ): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, archiveUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun defaultExportFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(Date())
        return File(exportDirectory(), "${config.zipPrefix}_$timestamp.zip")
    }

    private fun registerAutoFlush(context: Context) {
        val callback = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            override fun onLowMemory() {
                flushIfInitialized()
            }

            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                    flushIfInitialized()
                }
            }
        }
        context.registerComponentCallbacks(callback)
        lifecycleContext = WeakReference(context)
        lifecycleCallback = callback
    }

    private fun unregisterAutoFlush() {
        val callback = lifecycleCallback ?: return
        lifecycleContext.get()?.unregisterComponentCallbacks(callback)
        lifecycleCallback = null
        lifecycleContext.clear()
    }

    private fun installCrashHandler() {
        if (installedUncaughtExceptionHandler != null) {
            return
        }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        previousUncaughtExceptionHandler = previous
        val handler = Thread.UncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrashSnapshot(thread, throwable) }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            }
        }
        installedUncaughtExceptionHandler = handler
        Thread.setDefaultUncaughtExceptionHandler(handler)
    }

    private fun uninstallCrashHandler() {
        if (Thread.getDefaultUncaughtExceptionHandler() == installedUncaughtExceptionHandler) {
            Thread.setDefaultUncaughtExceptionHandler(previousUncaughtExceptionHandler)
        }
        installedUncaughtExceptionHandler = null
        previousUncaughtExceptionHandler = null
    }

    private fun writeCrashSnapshot(
        thread: Thread,
        throwable: Throwable
    ) {
        recentLogBuffer
            ?.snapshot()
            .orEmpty()
            .forEach { record ->
                destination().write(
                    record.copy(
                        level = LogLevel.ERROR,
                        tag = "CrashBuffer/${record.tag}",
                        message = "[recent] ${record.message}"
                    )
                )
            }

        e("FileLoggerCrash", "Uncaught exception on ${thread.name}", throwable)
        flush()
    }

    private fun shutdownLocked(timeoutMillis: Long): Boolean {
        delegate = null
        unregisterAutoFlush()
        uninstallCrashHandler()

        val oldDestination = destination
        destination = null
        val closed = when (oldDestination) {
            is CloseableLogDestination -> oldDestination.close(timeoutMillis)
            is FlushableLogDestination -> oldDestination.flush(timeoutMillis)
            else -> true
        }

        logDirectory = null
        exportDirectory = null
        packageName = null
        processName = null
        session = null
        recentLogBuffer = null
        return closed
    }

    private fun flushIfInitialized() {
        val currentDestination = destination ?: return
        (currentDestination as? FlushableLogDestination)?.flush()
    }

    private fun logger(): Logger {
        return delegate ?: throw IllegalStateException("FileLogger.init(context) must be called before logging.")
    }

    private fun destination(): LogDestination {
        return destination ?: throw IllegalStateException("FileLogger.init(context) must be called before using FileLogger.")
    }

    private fun logDirectory(): File {
        return logDirectory ?: throw IllegalStateException("FileLogger.init(context) must be called before accessing logs.")
    }

    private fun exportDirectory(): File {
        return exportDirectory ?: throw IllegalStateException("FileLogger.init(context) must be called before exporting logs.")
    }
}
