package com.filelogger.crashlytics

import com.filelogger.LogDestination
import com.filelogger.LogLevel
import com.filelogger.LogRecord
import com.google.firebase.crashlytics.FirebaseCrashlytics

class CrashlyticsLogDestination private constructor(
    private val client: CrashlyticsClient,
    private val config: CrashlyticsLogConfig
) : LogDestination {

    constructor(
        crashlytics: FirebaseCrashlytics = FirebaseCrashlytics.getInstance(),
        config: CrashlyticsLogConfig = CrashlyticsLogConfig()
    ) : this(FirebaseCrashlyticsClient(crashlytics), config)

    override fun write(record: LogRecord) {
        if (!record.level.isAtLeast(config.minimumLogLevel)) return

        val prefix = config.attributePrefix
        client.setKey("${prefix}level", record.level.name)
        client.setKey("${prefix}tag", record.tag)
        client.setKey("${prefix}thread", record.threadName)
        client.setKey("${prefix}process", record.processName)
        val attributes = record.attributes.entries
            .take(config.maxAttributes)
            .joinToString(separator = ",") { (key, value) -> "$key=$value" }
            .take(config.maxMessageLength)
        client.setKey("${prefix}attributes", attributes)

        val message = "${record.level.name}/${record.tag}: ${record.message}"
            .take(config.maxMessageLength)
        client.log(message)

        val throwableText = record.throwableText
        if (config.recordNonFatalExceptions && throwableText != null) {
            client.recordException(
                FileLoggerNonFatalException(throwableText.take(config.maxMessageLength))
            )
        }
    }

    internal constructor(
        client: CrashlyticsClient,
        config: CrashlyticsLogConfig,
        @Suppress("UNUSED_PARAMETER") testing: Unit = Unit
    ) : this(client, config)
}

internal interface CrashlyticsClient {
    fun log(message: String)
    fun setKey(key: String, value: String)
    fun recordException(throwable: Throwable)
}

private class FirebaseCrashlyticsClient(
    private val crashlytics: FirebaseCrashlytics
) : CrashlyticsClient {
    override fun log(message: String) = crashlytics.log(message)
    override fun setKey(key: String, value: String) = crashlytics.setCustomKey(key, value)
    override fun recordException(throwable: Throwable) = crashlytics.recordException(throwable)
}

private class FileLoggerNonFatalException(message: String) : RuntimeException(message)

private fun LogLevel.isAtLeast(minimum: LogLevel): Boolean = ordinal >= minimum.ordinal
