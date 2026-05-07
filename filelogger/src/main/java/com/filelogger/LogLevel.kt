package com.filelogger

enum class LogLevel(val shortName: String, internal val priority: Int) {
    DEBUG("D", 10),
    WARN("W", 20),
    ERROR("E", 30)
}
