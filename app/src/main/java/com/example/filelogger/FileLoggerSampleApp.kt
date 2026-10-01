package com.example.filelogger

import android.app.Application
import android.content.pm.ApplicationInfo
import com.filelogger.FileLogger
import com.filelogger.LoggerConfig

class FileLoggerSampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        FileLogger.init(this, if (debuggable) LoggerConfig.dev() else LoggerConfig.prod())
        FileLogger.d("SampleApp", "Sample logger initialized")
    }
}
