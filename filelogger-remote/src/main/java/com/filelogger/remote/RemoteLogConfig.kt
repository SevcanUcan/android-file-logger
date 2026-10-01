package com.filelogger.remote

import java.io.File

data class RemoteLogConfig(
    val spoolDirectory: File,
    val batchSize: Int = 50,
    val queueCapacity: Int = 1_000,
    val maxPendingBatches: Int = 100,
    val maxRetries: Int = 3,
    val initialBackoffMillis: Long = 500,
    val gzipEnabled: Boolean = true
) {
    init {
        require(batchSize > 0) { "batchSize must be greater than zero" }
        require(queueCapacity > 0) { "queueCapacity must be greater than zero" }
        require(maxPendingBatches > 0) { "maxPendingBatches must be greater than zero" }
        require(maxRetries >= 0) { "maxRetries must not be negative" }
        require(initialBackoffMillis >= 0) { "initialBackoffMillis must not be negative" }
    }
}

fun interface RemoteLogUploader {
    @Throws(Exception::class)
    fun upload(batch: RemoteLogBatch): Boolean
}

data class RemoteLogBatch(
    val payload: ByteArray,
    val contentType: String = "application/x-ndjson",
    val contentEncoding: String? = "gzip"
)
