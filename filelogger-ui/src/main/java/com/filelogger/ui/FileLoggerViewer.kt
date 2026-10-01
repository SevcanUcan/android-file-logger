package com.filelogger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import com.filelogger.FileLogger
import com.filelogger.LogLevel
import com.filelogger.LogQuery
import com.filelogger.StoredLogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FileLoggerViewer(
    modifier: Modifier = Modifier,
    maxEntries: Int = 2_000
) {
    var refreshKey by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf(emptyList<StoredLogEntry>()) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshKey, maxEntries) {
        runCatching { FileLogger.readLogs(LogQuery(limit = maxEntries)) }
            .onSuccess { loaded -> entries = loaded; loadError = null }
            .onFailure { error -> loadError = error.message ?: error.javaClass.simpleName }
    }

    LogViewerContent(
        entries = entries,
        loadError = loadError,
        onRefresh = { refreshKey += 1 },
        modifier = modifier
    )
}

@Composable
fun LogViewerContent(
    entries: List<StoredLogEntry>,
    modifier: Modifier = Modifier,
    loadError: String? = null,
    onRefresh: () -> Unit = {}
) {
    var filter by remember { mutableStateOf(LogViewerFilter()) }
    val filtered = remember(entries, filter) { filter.apply(entries) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = filter.search,
                onValueChange = { filter = filter.copy(search = it) },
                label = { Text("Search logs") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh logs")
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LogLevel.entries.forEach { level ->
                FilterChip(
                    selected = level in filter.levels,
                    onClick = {
                        val levels = if (level in filter.levels) {
                            filter.levels - level
                        } else {
                            filter.levels + level
                        }
                        filter = filter.copy(levels = levels)
                    },
                    label = { Text(level.name) }
                )
            }
        }
        loadError?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(12.dp)
            )
        }
        Text(
            text = "${filtered.size} / ${entries.size}",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(
                items = filtered,
                key = { entry ->
                    "${entry.sourceFileName}:${entry.timestampMillis}:${entry.tag}:${entry.message.hashCode()}"
                }
            ) { entry ->
                LogEntryRow(entry)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: StoredLogEntry) {
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(entry.level.name, fontWeight = FontWeight.Bold)
            Text(entry.tag, fontWeight = FontWeight.SemiBold)
            Text(formatTimestamp(entry.timestampMillis), style = MaterialTheme.typography.labelSmall)
        }
        Text(entry.message, fontFamily = FontFamily.Monospace)
        if (entry.attributes.isNotEmpty()) {
            Text(
                text = entry.attributes.entries.joinToString { (key, value) -> "$key=$value" },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace
            )
        }
        entry.throwable?.let { throwable ->
            Text(
                text = throwable,
                color = MaterialTheme.colorScheme.error,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Text(
            text = listOfNotNull(entry.sessionId, entry.threadName, entry.processName)
                .filter { it.isNotBlank() }
                .joinToString(" | "),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

private fun formatTimestamp(timestampMillis: Long): String =
    SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestampMillis))
