package com.filelogger.core

enum class LogLevel(internal val priority: Int) {
    DEBUG(10),
    INFO(20),
    WARN(30),
    ERROR(40);

    fun isAtLeast(minimumLevel: LogLevel): Boolean = priority >= minimumLevel.priority
}
