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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object FileLogger : Logger {

    private val lifecycleLock = Any()
    @Volatile
    internal var config: LoggerConfig = LoggerConfig()
    @Volatile
    private var collectionEnabled: Boolean = true
    @Volatile
    private var destination: LogDestination? = null
    @Volatile
    private var delegate: StructuredLogger? = null
    @Volatile
    private var logDirectory: File? = null
    @Volatile
    private var storageCoordinator: LogStorageCoordinator? = null
    @Volatile
    private var logsRootDirectory: File? = null
    @Volatile
    private var exportDirectory: File? = null
    @Volatile
    private var packageName: String? = null
    @Volatile
    private var processName: String? = null
    @Volatile
    private var supportReportEnvironment: SupportReportEnvironment? = null
    @Volatile
    private var session: LogSession? = null
    @Volatile
    private var recentLogBuffer: RecentLogBuffer? = null
    @Volatile
    private var breadcrumbBuffer: BreadcrumbBuffer? = null
    private var previousUncaughtExceptionHandler: Thread.UncaughtExceptionHandler? = null
    private var installedUncaughtExceptionHandler: Thread.UncaughtExceptionHandler? = null
    private var lifecycleContext = WeakReference<Context>(null)
    private var lifecycleCallback: ComponentCallbacks2? = null
    private var lifecycleFlushExecutor: ExecutorService? = null
    private val lifecycleFlushState = AtomicInteger(LIFECYCLE_FLUSH_IDLE)
    private val tagMinimumLevels = ConcurrentHashMap<String, LogLevel>()
    private val globalContext = ConcurrentHashMap<String, String>()

    fun init(
        context: Context,
        config: LoggerConfig = LoggerConfig()
    ) {
        val applicationContext = context.applicationContext ?: context
        val filesDir = applicationContext.filesDir
        val packageName = applicationContext.packageName
        val processName = ProcessNameResolver.resolve(applicationContext)
        val reportEnvironment = SupportReportEnvironment.capture(applicationContext, packageName)
        val session = if (config.sessionLoggingEnabled) {
            LogSessionFactory.create(config)
        } else {
            null
        }
        val sessionLogDirectory = LogDirectoryResolver.resolve(filesDir, config, session)

        synchronized(lifecycleLock) {
            shutdownLocked(FlushableLogDestination.DEFAULT_FLUSH_TIMEOUT_MILLIS)
            tagMinimumLevels.clear()
            globalContext.clear()

            this.config = config
            collectionEnabled = config.collectionEnabled
            this.packageName = packageName
            this.processName = processName
            supportReportEnvironment = reportEnvironment
            this.session = session
            logsRootDirectory = ContainedPathResolver.resolve(
                root = filesDir,
                relativePath = config.logFolder,
                fieldName = "logFolder",
                allowNested = true
            )
            storageCoordinator = LogStorageCoordinator(logsRootDirectory())
            logDirectory = sessionLogDirectory
            exportDirectory = File(filesDir, "log_exports")
            recentLogBuffer = if (config.crashCaptureEnabled) {
                RecentLogBuffer(config.crashBufferSize)
            } else {
                null
            }
            breadcrumbBuffer = BreadcrumbBuffer(config.breadcrumbCapacity)

            val newDestination = LoggerEngine.createDefaultDestination(
                logDirectoryProvider = { logDirectory() },
                configProvider = { this.config },
                packageNameProvider = { packageName },
                processNameProvider = { processName },
                recentLogBuffer = recentLogBuffer,
                storageCoordinator = storageCoordinator
            )
            destination = newDestination
            delegate = DefaultLogger(
                destination = newDestination,
                processNameProvider = { processName },
                contextProvider = { globalContext.toMap() },
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

    override fun i(tag: String, msg: String) {
        logger().i(tag, msg)
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

    fun i(tag: String, msg: () -> String) {
        if (isLoggable(LogLevel.INFO, tag)) {
            i(tag, msg())
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

    fun log(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable? = null,
        attributes: Map<String, String> = emptyMap()
    ) {
        logger().log(level, tag, message, throwable, attributes)
    }

    fun d(tag: String, message: String, attributes: Map<String, String>) =
        log(LogLevel.DEBUG, tag, message, attributes = attributes)

    fun i(tag: String, message: String, attributes: Map<String, String>) =
        log(LogLevel.INFO, tag, message, attributes = attributes)

    fun w(tag: String, message: String, attributes: Map<String, String>) =
        log(LogLevel.WARN, tag, message, attributes = attributes)

    fun e(
        tag: String,
        message: String,
        throwable: Throwable? = null,
        attributes: Map<String, String>
    ) = log(LogLevel.ERROR, tag, message, throwable, attributes)

    fun putContext(key: String, value: String) {
        require(key.isNotBlank()) { "context key must not be blank" }
        globalContext[key] = value
    }

    fun putContext(values: Map<String, String>) {
        values.forEach(::putContext)
    }

    fun removeContext(key: String): String? = globalContext.remove(key)

    fun clearContext() = globalContext.clear()

    fun context(): Map<String, String> = globalContext.toMap()

    fun addBreadcrumb(
        message: String,
        category: String = "user",
        attributes: Map<String, String> = emptyMap()
    ): Boolean {
        if (!collectionEnabled || config.breadcrumbCapacity == 0) return false
        val breadcrumb = BreadcrumbFactory.create(
            category = category,
            message = message,
            attributes = attributes,
            timestampMillis = System.currentTimeMillis(),
            redactor = config.redactor,
            maxAttributes = config.maxBreadcrumbAttributes,
            maxBytes = config.maxBreadcrumbBytes
        )
        breadcrumbBuffer?.add(breadcrumb) ?: return false
        return true
    }

    fun breadcrumbs(): List<Breadcrumb> = breadcrumbBuffer?.snapshot().orEmpty()

    fun clearBreadcrumbs() {
        breadcrumbBuffer?.clear()
    }

    fun isCollectionEnabled(): Boolean = collectionEnabled

    fun setCollectionEnabled(
        enabled: Boolean,
        deleteExistingData: Boolean = false,
        timeoutMillis: Long = FlushableLogDestination.DEFAULT_FLUSH_TIMEOUT_MILLIS
    ): Boolean {
        require(timeoutMillis > 0) { "timeoutMillis must be greater than zero" }
        if (!enabled) collectionEnabled = false
        val controlled = (destination as? CollectionControlledLogDestination)
            ?.setCollectionEnabled(enabled, deleteExistingData, timeoutMillis)
            ?: true
        if (!controlled) return false
        collectionEnabled = enabled
        if (!enabled && deleteExistingData && destination != null) {
            clearBreadcrumbs()
            if (!flushLocalDestinations(timeoutMillis)) return false
            val logsDeleted = deleteRecursivelyIfPresent(logsRootDirectory())
            val exportsDeleted = deleteRecursivelyIfPresent(exportDirectory())
            return logsDeleted && exportsDeleted
        }
        return true
    }

    fun deletePendingUploads(
        timeoutMillis: Long = FlushableLogDestination.DEFAULT_FLUSH_TIMEOUT_MILLIS
    ): Boolean {
        require(timeoutMillis > 0) { "timeoutMillis must be greater than zero" }
        return (destination() as? ErasableLogDestination)
            ?.erasePendingData(timeoutMillis)
            ?: true
    }

    fun deleteAllLogs(
        includeExports: Boolean = true,
        timeoutMillis: Long = FlushableLogDestination.DEFAULT_FLUSH_TIMEOUT_MILLIS
    ): Boolean {
        require(timeoutMillis > 0) { "timeoutMillis must be greater than zero" }
        return synchronized(lifecycleLock) {
            val wasEnabled = collectionEnabled
            var deletionSucceeded = false
            collectionEnabled = false
            clearBreadcrumbs()
            try {
                val pendingErased = (destination() as? CollectionControlledLogDestination)
                    ?.setCollectionEnabled(false, true, timeoutMillis)
                    ?: (destination() as? ErasableLogDestination)
                        ?.erasePendingData(timeoutMillis)
                    ?: true
                val localDrained = flushLocalDestinations(timeoutMillis)
                val logsDeleted = localDrained && deleteRecursivelyIfPresent(logsRootDirectory())
                val exportsDeleted = localDrained &&
                    (!includeExports || deleteRecursivelyIfPresent(exportDirectory()))
                deletionSucceeded = pendingErased && localDrained && logsDeleted && exportsDeleted
                deletionSucceeded
            } finally {
                if (wasEnabled && deletionSucceeded) {
                    val resumed = (destination() as? CollectionControlledLogDestination)
                        ?.setCollectionEnabled(true, false, timeoutMillis)
                        ?: true
                    collectionEnabled = resumed
                } else {
                    collectionEnabled = false
                }
            }
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
        if (!collectionEnabled) return false
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

    fun install(
        context: Context,
        config: LoggerConfig = LoggerConfig()
    ) = init(context, config)

    fun readLogs(query: LogQuery = LogQuery()): List<StoredLogEntry> {
        flush()
        return LogReader.read(
            directory = logDirectory(),
            baseLogFileName = effectiveLogFileName(),
            sessionId = session?.id,
            query = query
        )
    }

    fun readLogs(
        sessionId: String,
        query: LogQuery = LogQuery()
    ): List<StoredLogEntry> {
        flush()
        val directory = LogDirectoryResolver.resolveSession(
            root = logsRootDirectory(),
            config = config,
            sessionId = sessionId
        )
        return LogReader.read(
            directory = directory,
            baseLogFileName = effectiveLogFileName(),
            sessionId = sessionId,
            query = query
        )
    }

    fun exportLogs(
        outputFile: File = defaultExportFile(),
        redactor: LogRedactor = LogRedactor.DEFAULT_SENSITIVE
    ): File {
        check(flushLocalDestinations()) { "Cannot export logs because the local flush barrier failed" }
        return LogExporter.export(
            logDirectory = logDirectory(),
            outputFile = outputFile,
            baseLogFileName = config.logFileName,
            redactor = redactor,
            storageCoordinator = storageCoordinator,
            metadataEntries = mapOf(
                "diagnostics.json" to DiagnosticBundle.toJson(
                    appPackageName = packageName ?: "unknown",
                    processName = processName ?: "unknown",
                    session = session,
                    generatedAtMillis = System.currentTimeMillis(),
                    diagnostics = diagnostics(),
                    config = config.copy(collectionEnabled = collectionEnabled)
                )
            )
        )
    }

    fun createSupportReport(
        options: SupportReportOptions = SupportReportOptions(),
        outputFile: File = defaultSupportReportFile()
    ): File {
        check(flushLocalDestinations()) { "Cannot create a support report because the local flush barrier failed" }
        val generatedAtMillis = System.currentTimeMillis()
        val environment = supportReportEnvironment
            ?: throw IllegalStateException(
                "FileLogger.init(context) must be called before creating a support report."
            )
        val entries = linkedMapOf(
            "diagnostics.json" to DiagnosticBundle.toJson(
                appPackageName = packageName ?: "unknown",
                processName = processName ?: "unknown",
                session = session,
                generatedAtMillis = generatedAtMillis,
                diagnostics = diagnostics(),
                config = config.copy(collectionEnabled = collectionEnabled)
            )
        )
        entries.putAll(
            SupportReportBundle.metadataEntries(
                options = options,
                environment = environment,
                generatedAtMillis = generatedAtMillis,
                breadcrumbs = breadcrumbs()
            )
        )
        return LogExporter.export(
            logDirectory = logDirectory(),
            outputFile = outputFile,
            baseLogFileName = config.logFileName,
            redactor = options.redactor,
            metadataEntries = entries,
            includeLogs = options.includeLogs,
            storageCoordinator = storageCoordinator
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

    private fun defaultSupportReportFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
            .format(Date())
        return File(exportDirectory(), "SupportReport_$timestamp.zip")
    }

    private fun registerAutoFlush(context: Context) {
        lifecycleFlushState.set(LIFECYCLE_FLUSH_IDLE)
        val flushExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "FileLogger-LifecycleFlush").apply { isDaemon = true }
        }
        lifecycleFlushExecutor = flushExecutor
        val callback = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onLowMemory() {
                submitLifecycleFlush(flushExecutor)
            }

            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                    submitLifecycleFlush(flushExecutor)
                }
            }
        }
        context.registerComponentCallbacks(callback)
        lifecycleContext = WeakReference(context)
        lifecycleCallback = callback
    }

    private fun submitLifecycleFlush(executor: ExecutorService) {
        while (true) {
            when (lifecycleFlushState.get()) {
                LIFECYCLE_FLUSH_IDLE -> {
                    if (!lifecycleFlushState.compareAndSet(LIFECYCLE_FLUSH_IDLE, LIFECYCLE_FLUSH_RUNNING)) {
                        continue
                    }
                }
                LIFECYCLE_FLUSH_RUNNING -> {
                    lifecycleFlushState.compareAndSet(
                        LIFECYCLE_FLUSH_RUNNING,
                        LIFECYCLE_FLUSH_TRAILING_REQUESTED
                    )
                    return
                }
                LIFECYCLE_FLUSH_TRAILING_REQUESTED -> return
            }
            break
        }
        val accepted = runCatching {
            executor.execute {
                flushLoop@ while (true) {
                    runCatching { flushIfInitialized() }
                    while (true) {
                        when (lifecycleFlushState.get()) {
                            LIFECYCLE_FLUSH_TRAILING_REQUESTED -> {
                                if (lifecycleFlushState.compareAndSet(
                                        LIFECYCLE_FLUSH_TRAILING_REQUESTED,
                                        LIFECYCLE_FLUSH_RUNNING
                                    )
                                ) {
                                    continue@flushLoop
                                }
                            }
                            LIFECYCLE_FLUSH_RUNNING -> {
                                if (lifecycleFlushState.compareAndSet(
                                        LIFECYCLE_FLUSH_RUNNING,
                                        LIFECYCLE_FLUSH_IDLE
                                    )
                                ) {
                                    return@execute
                                }
                            }
                            else -> return@execute
                        }
                    }
                }
            }
        }.isSuccess
        if (!accepted) {
            lifecycleFlushState.set(LIFECYCLE_FLUSH_IDLE)
        }
    }

    private fun unregisterAutoFlush() {
        val callback = lifecycleCallback ?: return
        lifecycleContext.get()?.unregisterComponentCallbacks(callback)
        lifecycleCallback = null
        lifecycleContext.clear()
        lifecycleFlushExecutor?.shutdownNow()
        lifecycleFlushExecutor = null
        lifecycleFlushState.set(LIFECYCLE_FLUSH_IDLE)
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
        if (!collectionEnabled) return
        val breadcrumbSnapshot = breadcrumbs()
        val recentRecords = recentLogBuffer?.snapshot().orEmpty()
        breadcrumbSnapshot.forEach { breadcrumb ->
            log(
                level = LogLevel.ERROR,
                tag = "Breadcrumb/${breadcrumb.category}",
                message = "[breadcrumb@${breadcrumb.timestampMillis}] ${breadcrumb.message}",
                attributes = breadcrumb.attributes
            )
        }
        recentRecords.forEach { record ->
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
        storageCoordinator = null
        logsRootDirectory = null
        exportDirectory = null
        packageName = null
        processName = null
        supportReportEnvironment = null
        session = null
        recentLogBuffer = null
        breadcrumbBuffer = null
        return closed
    }

    private fun flushIfInitialized() {
        val currentDestination = destination ?: return
        (currentDestination as? FlushableLogDestination)?.flush()
    }

    private const val LIFECYCLE_FLUSH_IDLE = 0
    private const val LIFECYCLE_FLUSH_RUNNING = 1
    private const val LIFECYCLE_FLUSH_TRAILING_REQUESTED = 2

    private fun flushLocalDestinations(
        timeoutMillis: Long = FlushableLogDestination.DEFAULT_FLUSH_TIMEOUT_MILLIS
    ): Boolean {
        val currentDestination = destination ?: return true
        return when (currentDestination) {
            is ExportFlushableLogDestination -> currentDestination.flushForExport(timeoutMillis)
            is FlushableLogDestination -> currentDestination.flush(timeoutMillis)
            else -> true
        }
    }

    private fun logger(): StructuredLogger {
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

    private fun logsRootDirectory(): File {
        return logsRootDirectory
            ?: throw IllegalStateException("FileLogger.init(context) must be called before reading logs.")
    }

    private fun effectiveLogFileName(): String {
        if (!config.useProcessSpecificLogFiles) {
            return config.logFileName
        }
        return ProcessLogFileName.forProcess(
            baseFileName = config.logFileName,
            packageName = packageName ?: "unknown",
            processName = processName ?: "unknown"
        )
    }

    private fun deleteRecursivelyIfPresent(directory: File): Boolean {
        return !directory.exists() || directory.deleteRecursively()
    }
}
