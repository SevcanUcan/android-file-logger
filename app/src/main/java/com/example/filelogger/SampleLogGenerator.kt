package com.example.filelogger

import com.filelogger.Logger

object SampleLogGenerator {
    fun generate(logger: Logger) {
        logger.d("Sample", "Debug event generated")
        logger.i("Sample", "Info event generated")
        logger.w("Sample", "Warning event generated")
        logger.e("Sample", "Error event generated", IllegalStateException("Sample throwable"))
    }

    fun stress(logger: Logger, count: Int = 500) {
        require(count > 0) { "count must be greater than zero" }
        repeat(count) { index -> logger.d("Stress", "record-$index") }
        logger.e("Stress", "stress-final")
    }
}
