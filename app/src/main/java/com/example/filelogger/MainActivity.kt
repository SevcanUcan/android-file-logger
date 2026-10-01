package com.example.filelogger

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.filelogger.ui.theme.AndroidfileloggerTheme
import com.filelogger.FileLogger
import com.filelogger.LogDiagnostics
import com.filelogger.SupportReportOptions
import com.filelogger.ui.FileLoggerViewer

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidfileloggerTheme {
                SampleScreen(
                    onExport = ::exportAndShare,
                    onCrash = {
                        FileLogger.addBreadcrumb("Confirmed crash simulation", category = "user-action")
                        throw IllegalStateException("Sample app crash simulation")
                    }
                )
            }
        }
    }

    private fun exportAndShare() {
        FileLogger.addBreadcrumb("Shared support report", category = "support")
        val archive = FileLogger.createSupportReport(
            SupportReportOptions(
                userNote = "Created from the FileLogger sample app",
                sections = mapOf(
                    "sample-state" to "screen=logger-lab\nsource=manual-export"
                )
            )
        )
        val uri = FileProvider.getUriForFile(
            this,
            "$packageName.filelogger.files",
            archive
        )
        startActivity(Intent.createChooser(FileLogger.createShareIntent(uri), "Share logs"))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SampleScreen(onExport: () -> Unit, onCrash: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var showCrashConfirmation by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FileLogger Lab") },
                actions = {
                    IconButton(onClick = onExport) {
                        Icon(Icons.Default.Archive, contentDescription = "Share support report")
                    }
                    IconButton(onClick = { showCrashConfirmation = true }) {
                        Icon(Icons.Default.BugReport, contentDescription = "Simulate crash")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        FileLogger.addBreadcrumb("Generated sample logs", category = "user-action")
                        SampleLogGenerator.generate(FileLogger)
                        FileLogger.flush()
                        refreshKey += 1
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text("Generate")
                }
                Button(
                    onClick = {
                        FileLogger.addBreadcrumb("Started stress run", category = "user-action")
                        SampleLogGenerator.stress(FileLogger)
                        FileLogger.flush()
                        refreshKey += 1
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Speed, contentDescription = null)
                    Text("Stress")
                }
            }
            TabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == 0,
                    onClick = {
                        FileLogger.addBreadcrumb("Opened logs tab", category = "navigation")
                        tab = 0
                    },
                    text = { Text("Logs") }
                )
                Tab(
                    selected = tab == 1,
                    onClick = {
                        FileLogger.addBreadcrumb("Opened diagnostics tab", category = "navigation")
                        tab = 1
                    },
                    text = { Text("Diagnostics") }
                )
            }
            if (tab == 0) {
                key(refreshKey) { FileLoggerViewer() }
            } else {
                DiagnosticsView(FileLogger.diagnostics())
            }
        }
    }

    if (showCrashConfirmation) {
        AlertDialog(
            onDismissRequest = { showCrashConfirmation = false },
            title = { Text("Simulate app crash?") },
            text = { Text("The crash handler will persist recent logs before termination.") },
            confirmButton = { TextButton(onClick = onCrash) { Text("Crash") } },
            dismissButton = {
                TextButton(onClick = { showCrashConfirmation = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun DiagnosticsView(diagnostics: LogDiagnostics) {
    val rows = listOf(
        "Total records" to diagnostics.totalRecords,
        "Info" to diagnostics.infoRecords,
        "Errors" to diagnostics.errorRecords,
        "Dropped" to diagnostics.droppedAsyncRecords,
        "Queued" to diagnostics.queuedAsyncRecords,
        "File writes" to diagnostics.fileWriteCount,
        "Write failures" to diagnostics.fileWriteFailures,
        "Flush failures" to diagnostics.fileFlushFailures,
        "Rotations" to diagnostics.fileRotations,
        "Remote uploaded" to diagnostics.uploadedRemoteBatches,
        "Remote pending" to diagnostics.pendingRemoteBatches
    )
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        rows.forEach { (label, value) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(label)
                Text(value.toString(), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
