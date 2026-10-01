package com.filelogger

import com.google.gson.JsonParser
import java.io.File
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale

internal object LogReader {

    fun read(
        directory: File,
        baseLogFileName: String,
        sessionId: String?,
        query: LogQuery
    ): List<StoredLogEntry> {
        if (!directory.isDirectory) return emptyList()

        return directory
            .listFiles { file ->
                file.isFile &&
                    (file.name == baseLogFileName || file.name.startsWith("$baseLogFileName."))
            }
            .orEmpty()
            .sortedWith(compareBy<File> { backupIndex(it.name, baseLogFileName) }.reversed())
            .asSequence()
            .flatMap { file -> parseFile(file, sessionId).asSequence() }
            .filter { entry -> query.matches(entry) }
            .take(query.limit)
            .toList()
    }

    private fun parseFile(file: File, sessionId: String?): List<StoredLogEntry> {
        val entries = mutableListOf<StoredLogEntry>()
        var pending: StoredLogEntry? = null

        file.useLines { lines ->
            lines.forEach { line ->
                val parsed = parseJson(line, file.name, sessionId)
                    ?: parsePlainText(line, file.name, sessionId)
                if (parsed != null) {
                    pending?.let(entries::add)
                    pending = parsed
                } else if (pending != null && line.isNotBlank()) {
                    pending = pending?.copy(
                        throwable = listOfNotNull(pending?.throwable, line).joinToString("\n")
                    )
                }
            }
        }
        pending?.let(entries::add)
        return entries
    }

    private fun parseJson(
        line: String,
        sourceFileName: String,
        sessionId: String?
    ): StoredLogEntry? = runCatching {
        val json = JsonParser.parseString(line).asJsonObject
        StoredLogEntry(
            timestampMillis = parseTimestamp(json.get("time").asString) ?: return null,
            level = parseLevel(json.get("level").asString) ?: return null,
            tag = json.get("tag").asString,
            message = json.get("message").asString,
            throwable = json.get("throwable")?.takeUnless { it.isJsonNull }?.asString,
            threadName = json.get("thread")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
            processName = json.get("process")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
            attributes = json.getAsJsonObject("attributes")
                ?.entrySet()
                ?.associate { (key, value) -> key to value.asString }
                .orEmpty(),
            sessionId = sessionId,
            sourceFileName = sourceFileName
        )
    }.getOrNull()

    private fun parsePlainText(
        line: String,
        sourceFileName: String,
        sessionId: String?
    ): StoredLogEntry? {
        val match = PLAIN_TEXT_PATTERN.matchEntire(line) ?: return null
        val attributes = match.groupValues[6]
            .takeIf { it.isNotEmpty() }
            ?.let(::parseAttributes)
            .orEmpty()
        return StoredLogEntry(
            timestampMillis = parseTimestamp(match.groupValues[1]) ?: return null,
            level = parseLevel(match.groupValues[2]) ?: return null,
            tag = match.groupValues[3],
            threadName = match.groupValues[4],
            processName = match.groupValues[5],
            attributes = attributes,
            message = match.groupValues[7],
            throwable = null,
            sessionId = sessionId,
            sourceFileName = sourceFileName
        )
    }

    private fun parseTimestamp(value: String): Long? {
        val position = ParsePosition(0)
        val parsed = timestampFormatter().parse(value, position) ?: return null
        return parsed.time.takeIf { position.index == value.length }
    }

    private fun parseLevel(value: String): LogLevel? {
        return LogLevel.entries.firstOrNull { level ->
            level.shortName.equals(value, ignoreCase = true) ||
                level.name.equals(value, ignoreCase = true)
        }
    }

    private fun LogQuery.matches(entry: StoredLogEntry): Boolean {
        if (levels.isNotEmpty() && entry.level !in levels) return false
        if (tags.isNotEmpty() && entry.tag !in tags) return false
        if (attributes.any { (key, value) -> entry.attributes[key] != value }) return false
        if (fromTimestampMillis != null && entry.timestampMillis < fromTimestampMillis) return false
        if (toTimestampMillis != null && entry.timestampMillis > toTimestampMillis) return false
        val search = keyword?.trim().orEmpty()
        if (search.isNotEmpty()) {
            val searchable = listOf(entry.tag, entry.message, entry.throwable.orEmpty()) +
                entry.attributes.flatMap { (key, value) -> listOf(key, value) }
            if (searchable.none { it.contains(search, ignoreCase = true) }) return false
        }
        return true
    }

    private fun backupIndex(fileName: String, baseLogFileName: String): Int {
        if (fileName == baseLogFileName) return 0
        return fileName.removePrefix("$baseLogFileName.").toIntOrNull() ?: Int.MAX_VALUE
    }

    private fun timestampFormatter(): SimpleDateFormat =
        SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US).apply { isLenient = false }

    private val PLAIN_TEXT_PATTERN = Regex(
        "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) ([DIWE])/([^ ]+) " +
            "\\[thread=([^,]*), process=([^]]*)\\](?:\\[attributes=(\\{.*\\})\\])? (.*)$"
    )

    private fun parseAttributes(value: String): Map<String, String> = runCatching {
        JsonParser.parseString(value).asJsonObject.entrySet()
            .associate { (key, jsonValue) -> key to jsonValue.asString }
    }.getOrDefault(emptyMap())

    private const val TIMESTAMP_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS"
}
