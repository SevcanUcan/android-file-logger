package com.filelogger

enum class AsyncOverflowStrategy {
    DROP_OLDEST,
    DROP_NEWEST,
    BLOCK
}
