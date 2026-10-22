package com.example.filelogger

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.filelogger.FileLogger
import com.filelogger.LogLevel
import com.filelogger.LogRecord
import com.filelogger.LogRedactor
import com.filelogger.LoggerConfig
import com.filelogger.remote.IdentifiedRemoteLogUploader
import com.filelogger.remote.RemoteLogConfig
import com.filelogger.remote.RemoteLogDestination
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

class RemoteProcessTestService : Service() {

    @Volatile
    private var remoteDestination: RemoteLogDestination? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        remoteDestination?.close(1_000)
        remoteDestination = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        val folder = intent.getStringExtra(EXTRA_FOLDER) ?: return START_NOT_STICKY
        val root = File(filesDir, folder).apply { mkdirs() }
        when (action) {
            ACTION_REMOTE_BLOCKING_UPLOAD -> startRemote(root, UploadMode.BLOCKING)
            ACTION_REMOTE_FAIL_WITH_BACKOFF -> startRemote(root, UploadMode.FAIL_WITH_BACKOFF)
            ACTION_REMOTE_WAIT_FOR_RESUME -> waitForRemoteResume(root)
            ACTION_REMOTE_QUOTA_RACE -> prepareQuotaRace(root)
            ACTION_REMOTE_STOP -> {
                remoteDestination?.close(TEST_TIMEOUT_MILLIS)
                remoteDestination = null
                stopSelf(startId)
            }
            ACTION_PROCESS_KILL -> Process.killProcess(Process.myPid())
            ACTION_EXPORT_KILL_DURING_WRITE -> startExportAndKill(root, intent, KillPhase.DURING_WRITE)
            ACTION_EXPORT_KILL_AT_COMMIT -> startExportAndKill(root, intent, KillPhase.COMMIT_BOUNDARY)
        }
        return START_NOT_STICKY
    }

    private fun startRemote(spool: File, mode: UploadMode) {
        remoteDestination?.close(1_000)
        val destination = RemoteLogDestination.withIdentifiedUploader(
            uploader = IdentifiedRemoteLogUploader { _, context ->
                File(
                    spool,
                    "$UPLOAD_EVENT_PREFIX${context.batchId}_${context.attemptId}_${Process.myPid()}"
                ).writeText(context.batchId)
                when (mode) {
                    UploadMode.BLOCKING -> {
                        waitForFile(File(spool, RELEASE_FILE), TEST_TIMEOUT_MILLIS)
                        File(spool, RELEASE_FILE).exists()
                    }
                    UploadMode.FAIL_WITH_BACKOFF -> false
                }
            },
            config = RemoteLogConfig(
                spoolDirectory = spool,
                batchSize = 1,
                queueCapacity = 8,
                maxPendingBatches = 8,
                maxRetries = if (mode == UploadMode.FAIL_WITH_BACKOFF) 3 else 0,
                initialBackoffMillis = if (mode == UploadMode.FAIL_WITH_BACKOFF) 5_000 else 0,
                gzipEnabled = false
            )
        )
        remoteDestination = destination
        File(spool, READY_FILE).writeText(Process.myPid().toString())
        destination.write(record("remote-${mode.name.lowercase()}"))
    }

    private fun prepareQuotaRace(spool: File) {
        remoteDestination?.close(1_000)
        val destination = RemoteLogDestination(
            uploader = { false },
            config = RemoteLogConfig(
                spoolDirectory = spool,
                batchSize = 1,
                queueCapacity = 4,
                maxPendingBatches = 1,
                maxRetries = 0,
                gzipEnabled = false
            )
        )
        remoteDestination = destination
        File(spool, READY_FILE).writeText(Process.myPid().toString())
        Thread {
            waitForFile(File(spool, START_FILE), TEST_TIMEOUT_MILLIS)
            destination.write(record("remote-quota"))
            destination.flush(5_000)
            File(spool, DONE_FILE).writeText("done")
        }.apply {
            name = "FileLogger-Remote-Process-Quota"
            isDaemon = true
            start()
        }
    }

    private fun waitForRemoteResume(spool: File) {
        remoteDestination?.close(1_000)
        val destination = RemoteLogDestination.withIdentifiedUploader(
            uploader = IdentifiedRemoteLogUploader { _, context ->
                File(
                    spool,
                    "$UPLOAD_EVENT_PREFIX${context.batchId}_${context.attemptId}_${Process.myPid()}"
                ).writeText(context.batchId)
                true
            },
            config = RemoteLogConfig(
                spoolDirectory = spool,
                batchSize = 1,
                queueCapacity = 8,
                maxPendingBatches = 8,
                maxRetries = 0,
                gzipEnabled = false
            )
        )
        remoteDestination = destination
        File(spool, READY_FILE).writeText(Process.myPid().toString())
        Thread {
            waitForFile(File(spool, START_FILE), TEST_TIMEOUT_MILLIS)
            destination.write(record("remote-after-resume"))
            File(spool, DONE_FILE).writeText(destination.flush(5_000).toString())
        }.apply {
            name = "FileLogger-Remote-Process-Resume"
            isDaemon = true
            start()
        }
    }

    private fun startExportAndKill(root: File, intent: Intent, phase: KillPhase) {
        val outputDirectory = File(root, "exports").apply { mkdirs() }
        val output = File(outputDirectory, intent.getStringExtra(EXTRA_OUTPUT_NAME) ?: "process-export.zip")
        val logDirectory = File(root, "logs").apply { mkdirs() }
        val log = File(logDirectory, "process.log")
        log.bufferedWriter().use { writer ->
            repeat(350_000) { index ->
                writer.append("{\"index\":").append(index.toString())
                    .append(",\"message\":\"").append("x".repeat(96)).append("\"}\n")
            }
        }
        FileLogger.init(
            this,
            LoggerConfig.dev().copy(
                logFolder = "${root.name}/logs",
                logFileName = "process.log",
                sessionLoggingEnabled = false,
                crashCaptureEnabled = false,
                autoFlushOnAppBackground = false
            )
        )
        File(root, READY_FILE).writeText(Process.myPid().toString())
        Thread {
            runCatching { FileLogger.exportLogs(output, LogRedactor.NONE) }
        }.apply {
            name = "FileLogger-Remote-Process-Export"
            start()
        }
        Thread {
            when (phase) {
                KillPhase.DURING_WRITE -> waitForGrowingTemp(outputDirectory, output.name)
                KillPhase.COMMIT_BOUNDARY -> waitForValidTemp(outputDirectory, output.name)
            }
            File(root, KILL_TRIGGERED_FILE).writeText(phase.name)
            Process.killProcess(Process.myPid())
        }.apply {
            name = "FileLogger-Remote-Process-Killer"
            isDaemon = true
            start()
        }
    }

    private fun waitForGrowingTemp(directory: File, outputName: String) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TEST_TIMEOUT_MILLIS)
        while (System.nanoTime() < deadline) {
            val temp = exportTemps(directory, outputName).firstOrNull()
            if (temp != null && temp.length() >= 64 * 1024) return
            Thread.sleep(5)
        }
    }

    private fun waitForValidTemp(directory: File, outputName: String) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TEST_TIMEOUT_MILLIS)
        while (System.nanoTime() < deadline) {
            if (exportTemps(directory, outputName).any { candidate ->
                    candidate.exists() && runCatching {
                        ZipFile(candidate).use { zip ->
                            zip.entries().asSequence().toList().isNotEmpty()
                        }
                    }.getOrDefault(false)
                }
            ) return
            Thread.sleep(2)
        }
    }

    private fun exportTemps(directory: File, outputName: String): List<File> =
        directory.listFiles().orEmpty().filter {
            it.name.startsWith(".${outputName.take(32)}-") && it.name.endsWith(".tmp")
        }

    private fun waitForFile(file: File, timeoutMillis: Long) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!file.exists() && System.nanoTime() < deadline) Thread.sleep(10)
    }

    private fun record(message: String) = LogRecord(
        timestampMillis = System.currentTimeMillis(),
        level = LogLevel.INFO,
        tag = "RemoteProcess",
        message = message,
        threadName = Thread.currentThread().name,
        processName = packageName
    )

    private enum class UploadMode { BLOCKING, FAIL_WITH_BACKOFF }
    private enum class KillPhase { DURING_WRITE, COMMIT_BOUNDARY }

    companion object {
        const val ACTION_REMOTE_BLOCKING_UPLOAD = "com.example.filelogger.REMOTE_BLOCKING_UPLOAD"
        const val ACTION_REMOTE_FAIL_WITH_BACKOFF = "com.example.filelogger.REMOTE_FAIL_BACKOFF"
        const val ACTION_REMOTE_WAIT_FOR_RESUME = "com.example.filelogger.REMOTE_WAIT_FOR_RESUME"
        const val ACTION_REMOTE_QUOTA_RACE = "com.example.filelogger.REMOTE_QUOTA_RACE"
        const val ACTION_REMOTE_STOP = "com.example.filelogger.REMOTE_STOP"
        const val ACTION_PROCESS_KILL = "com.example.filelogger.REMOTE_PROCESS_KILL"
        const val ACTION_EXPORT_KILL_DURING_WRITE = "com.example.filelogger.EXPORT_KILL_WRITE"
        const val ACTION_EXPORT_KILL_AT_COMMIT = "com.example.filelogger.EXPORT_KILL_COMMIT"
        const val EXTRA_FOLDER = "folder"
        const val EXTRA_OUTPUT_NAME = "output_name"
        const val READY_FILE = ".test-ready"
        const val START_FILE = ".test-start"
        const val DONE_FILE = ".test-done"
        const val RELEASE_FILE = ".test-release"
        const val KILL_TRIGGERED_FILE = ".test-kill-triggered"
        const val UPLOAD_EVENT_PREFIX = ".test-upload-"
        private const val TEST_TIMEOUT_MILLIS = 30_000L
    }
}
