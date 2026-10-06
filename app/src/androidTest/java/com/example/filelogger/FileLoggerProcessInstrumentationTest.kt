package com.example.filelogger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filelogger.FileLogger
import com.filelogger.LoggerConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FileLoggerProcessInstrumentationTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDown() {
        runCatching { FileLogger.shutdown() }
    }

    @Test
    fun concurrentProcessesWriteSeparateFilesWithoutCorruption() {
        val folder = "multiprocess_${System.nanoTime()}"
        val directory = File(context.filesDir, folder)
        FileLogger.init(context, config(folder, crashCapture = false))
        val latch = CountDownLatch(1)
        var resultCode = 0
        val resultAction = "com.example.filelogger.RESULT.${System.nanoTime()}"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                resultCode = intent?.getIntExtra(
                    FileLoggerProcessTestService.EXTRA_RESULT_CODE,
                    0
                ) ?: 0
                latch.countDown()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver,
                IntentFilter(resultAction),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, IntentFilter(resultAction))
        }

        try {
            context.startService(
                Intent(context, FileLoggerProcessTestService::class.java)
                    .setAction(FileLoggerProcessTestService.ACTION_WRITE)
                    .putExtra(FileLoggerProcessTestService.EXTRA_FOLDER, folder)
                    .putExtra(FileLoggerProcessTestService.EXTRA_RESULT_ACTION, resultAction)
            )
            repeat(100) { index -> FileLogger.d("MainProcess", "main-$index") }
            FileLogger.e("MainProcess", "main-final")

            assertTrue(latch.await(15, TimeUnit.SECONDS))
            assertEquals(1, resultCode)
        } finally {
            context.unregisterReceiver(receiver)
        }
        assertTrue(FileLogger.flush())
        val files = directory.listFiles().orEmpty().filter { it.isFile }
        assertEquals(2, files.size)
        val combined = files.joinToString("\n") { it.readText() }
        assertTrue(combined.contains("main-final"))
        assertTrue(combined.contains("remote-final"))
        assertTrue(files.all { it.readLines().all(String::isNotBlank) })
    }

    @Test
    fun uncaughtExceptionInSecondaryProcessPersistsCrashSnapshot() {
        val folder = "crash_${System.nanoTime()}"
        val directory = File(context.filesDir, folder)

        context.startService(
            Intent(context, FileLoggerProcessTestService::class.java)
                .setAction(FileLoggerProcessTestService.ACTION_CRASH)
                .putExtra(FileLoggerProcessTestService.EXTRA_FOLDER, folder)
        )

        val logText = waitForText(directory, "simulated-process-crash", 15_000)
        assertTrue(logText.contains("before-real-crash"))
        assertTrue(logText.contains("Opened checkout before crash"))
        assertTrue(logText.contains("Breadcrumb/navigation"))
        assertTrue(logText.contains("FileLoggerCrash"))
        assertTrue(logText.contains("simulated-process-crash"))
    }

    private fun config(folder: String, crashCapture: Boolean) =
        LoggerConfig.dev().copy(
            logFolder = folder,
            logFileName = "process.log",
            sessionLoggingEnabled = false,
            autoFlushOnAppBackground = false,
            crashCaptureEnabled = crashCapture
        )

    private fun waitForText(directory: File, needle: String, timeoutMillis: Long): String {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val text = directory.listFiles().orEmpty()
                .filter { it.isFile }
                .joinToString("\n") { runCatching { it.readText() }.getOrDefault("") }
            if (text.contains(needle)) return text
            Thread.sleep(100)
        }
        return directory.listFiles().orEmpty().joinToString("\n") { it.readText() }
    }
}
