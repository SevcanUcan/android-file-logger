package com.filelogger

import java.io.File

internal object LoggerEngine {

    fun createDefaultDestination(
        filesDirProvider: () -> File,
        configProvider: () -> LoggerConfig
    ): LogDestination {
        val fileDestination = AsyncLogDestination(
            delegate = FileLogDestination(
                fileProvider = {
                    File(
                        File(filesDirProvider(), configProvider().logFolder),
                        configProvider().logFileName
                    )
                },
                formatter = configProvider().logFormatter
            )
        )

        return CompositeLogDestination(
            listOf(
                LogcatDestination(),
                fileDestination
            )
        )
    }
}
