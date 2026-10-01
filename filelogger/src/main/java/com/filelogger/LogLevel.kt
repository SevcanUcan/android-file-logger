package com.filelogger

enum class LogLevel(val shortName: String, internal val priority: Int) {
    DEBUG("D", 10),
    INFO("I", 20),
    WARN("W", 30),
    ERROR("E", 40)
}
