package com.example.filelogger

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.filelogger.FileLogger
import com.filelogger.LoggerConfig

class FileLoggerProcessTestService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val folder = requireNotNull(intent?.getStringExtra(EXTRA_FOLDER))
        FileLogger.init(
            this,
            LoggerConfig.dev().copy(
                logFolder = folder,
                logFileName = "process.log",
                sessionLoggingEnabled = false,
                autoFlushOnAppBackground = false,
                crashCaptureEnabled = action == ACTION_CRASH
            )
        )

        if (action == ACTION_CRASH) {
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
            val success = FileLogger.flush() && FileLogger.shutdown()
            intent?.getStringExtra(EXTRA_RESULT_ACTION)?.let { resultAction ->
                sendBroadcast(
                    Intent(resultAction)
                        .setPackage(packageName)
                        .putExtra(EXTRA_RESULT_CODE, if (success) 1 else 0)
                )
            }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    companion object {
        const val ACTION_WRITE = "com.example.filelogger.WRITE"
        const val ACTION_CRASH = "com.example.filelogger.CRASH"
        const val EXTRA_FOLDER = "folder"
        const val EXTRA_RESULT_ACTION = "result_action"
        const val EXTRA_RESULT_CODE = "result_code"
    }
}
