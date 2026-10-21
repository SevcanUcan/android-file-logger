package com.example.filelogger

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import com.filelogger.FileLogger
import com.filelogger.LoggerConfig
import java.io.File
import java.io.FileOutputStream

class FileLoggerProcessTestService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val folder = requireNotNull(intent?.getStringExtra(EXTRA_FOLDER))
        if (action == ACTION_STOP) {
            FileLogger.shutdown(PROCESS_TEST_TIMEOUT_MILLIS)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        FileLogger.init(
            this,
            LoggerConfig.dev().copy(
                logFolder = folder,
                logFileName = "process.log",
                sessionLoggingEnabled = action == ACTION_HOLD_SESSION || action == ACTION_KILL_SESSION,
                sessionId = when (action) {
                    ACTION_HOLD_SESSION -> REMOTE_SESSION_ID
                    ACTION_KILL_SESSION -> DEAD_SESSION_ID
                    else -> null
                },
                autoFlushOnAppBackground = false,
                crashCaptureEnabled = action == ACTION_CRASH
            )
        )

        if (action == ACTION_HOLD_SESSION || action == ACTION_KILL_SESSION) {
            FileLogger.i("RemoteProcess", "remote-session-is-active")
            val flushed = FileLogger.flush(PROCESS_TEST_TIMEOUT_MILLIS)
            if (action == ACTION_KILL_SESSION) {
                writeProcessDeathReadyFile(folder, if (flushed) 1 else 0)
                Handler(Looper.getMainLooper()).postDelayed(
                    { Process.killProcess(Process.myPid()) },
                    200
                )
            } else {
                sendResult(intent, if (flushed) 1 else 0)
            }
        } else if (action == ACTION_CRASH) {
            FileLogger.addBreadcrumb(
                message = "Opened checkout before crash",
                category = "navigation",
                attributes = mapOf("screen" to "checkout")
            )
            FileLogger.d("RemoteProcess", "before-real-crash")
            Handler(Looper.getMainLooper()).postDelayed(
                { throw IllegalStateException("simulated-process-crash") },
                100
            )
        } else {
            repeat(100) { index -> FileLogger.d("RemoteProcess", "remote-$index") }
            FileLogger.e("RemoteProcess", "remote-final")
            val flushed = FileLogger.flush(PROCESS_TEST_TIMEOUT_MILLIS)
            val shutdown = FileLogger.shutdown(PROCESS_TEST_TIMEOUT_MILLIS)
            val success = flushed && shutdown
            sendResult(intent, if (success) 1 else 0)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun sendResult(intent: Intent?, resultCode: Int) {
        intent?.getStringExtra(EXTRA_RESULT_ACTION)?.let { resultAction ->
            sendBroadcast(
                Intent(resultAction)
                    .setPackage(packageName)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
            )
        }
    }

    private fun writeProcessDeathReadyFile(folder: String, resultCode: Int) {
        val marker = File(File(filesDir, folder), PROCESS_DEATH_READY_FILE)
        FileOutputStream(marker).use { output ->
            output.write(resultCode.toString().toByteArray())
            output.fd.sync()
        }
    }

    companion object {
        const val ACTION_WRITE = "com.example.filelogger.WRITE"
        const val ACTION_CRASH = "com.example.filelogger.CRASH"
        const val ACTION_HOLD_SESSION = "com.example.filelogger.HOLD_SESSION"
        const val ACTION_KILL_SESSION = "com.example.filelogger.KILL_SESSION"
        const val ACTION_STOP = "com.example.filelogger.STOP"
        const val EXTRA_FOLDER = "folder"
        const val EXTRA_RESULT_ACTION = "result_action"
        const val EXTRA_RESULT_CODE = "result_code"
        const val REMOTE_SESSION_ID = "process_b"
        const val DEAD_SESSION_ID = "dead_process"
        const val PROCESS_DEATH_READY_FILE = ".process-death-ready"
        private const val PROCESS_TEST_TIMEOUT_MILLIS = 60_000L
    }
}
