package com.filelogger

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build

internal object ProcessNameResolver {

    fun resolve(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName()
        }

        val pid = android.os.Process.myPid()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

        return activityManager
            ?.runningAppProcesses
            ?.firstOrNull { process -> process.pid == pid }
            ?.processName
            ?: context.packageName
    }
}
