package com.textmorph.app.ui.about

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import com.textmorph.app.ui.about.components.CrashReportCard
import com.textmorph.app.ui.about.components.LogCard
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.util.AppLogger
import com.textmorph.app.util.CrashLogManager
import com.textmorph.app.util.CrashReport
import com.textmorph.app.util.LogItem
import com.textmorph.app.util.LogLevel
import kotlinx.coroutines.launch

private enum class LogFilterType {
    ALL, CRASHES, ERRORS, WARNINGS, INFO, DEBUG
}

@Composable
fun DevLogsScreen(
    installedVersionName: String,
    onNavigateBack: () -> Unit,
    onOpenDrawer: () -> Unit = onNavigateBack,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val allLogs by AppLogger.logsState.collectAsState()
    val crashLogManager = remember { CrashLogManager.getInstance(context) }
    var crashReports by remember { mutableStateOf(crashLogManager.getCrashReports()) }

    var filterType by remember { mutableStateOf(LogFilterType.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var isDumpingLogcat by remember { mutableStateOf(false) }

    BackHandler { onNavigateBack() }

    val filteredLogs = remember(allLogs, filterType, searchQuery) {
        if (filterType == LogFilterType.CRASHES) emptyList()
        else {
            allLogs.filter { item ->
                val matchesLevel = when (filterType) {
                    LogFilterType.ALL -> true
                    LogFilterType.ERRORS -> item.level == LogLevel.ERROR
                    LogFilterType.WARNINGS -> item.level == LogLevel.WARN
                    LogFilterType.INFO -> item.level == LogLevel.INFO
                    LogFilterType.DEBUG -> item.level == LogLevel.DEBUG
                    LogFilterType.CRASHES -> false
                }
                val matchesSearch = if (searchQuery.isBlank()) {
                    true
                } else {
                    item.tag.contains(searchQuery, ignoreCase = true) ||
                            item.message.contains(searchQuery, ignoreCase = true) ||
                            (item.stackTrace?.contains(searchQuery, ignoreCase = true) == true)
                }
                matchesLevel && matchesSearch
            }
        }
    }

    val filteredCrashes = remember(crashReports, searchQuery) {
        if (searchQuery.isBlank()) crashReports
        else {
            crashReports.filter { r ->
                r.exceptionClass.contains(searchQuery, ignoreCase = true) ||
                        r.message.contains(searchQuery, ignoreCase = true) ||
                        r.crashType.contains(searchQuery, ignoreCase = true) ||
                        r.stackTrace.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Scaffold(
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 3.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Developer Diagnostics",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            val subtitle = if (filterType == LogFilterType.CRASHES) {
                                "${filteredCrashes.size} persistent crash reports on disk"
                            } else {
                                "Rolling disk log • ${filteredLogs.size}/${allLogs.size} entries"
                            }
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                fontSize = 11.sp
                            )
                        }

                        // Dump System Logcat Button
                        IconButton(
                            onClick = {
                                scope.launch {
                                    isDumpingLogcat = true
                                    val count = AppLogger.dumpSystemLogcat()
                                    isDumpingLogcat = false
                                    Toast.makeText(context, "Captured $count system logcat lines", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = !isDumpingLogcat
                        ) {
                            if (isDumpingLogcat) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "Dump System Logcat")
                            }
                        }

                        // Copy All Logs / Crashes
                        IconButton(
                            onClick = {
                                if (filterType == LogFilterType.CRASHES) {
                                    val text = crashReports.joinToString("\n\n" + "=".repeat(60) + "\n\n") {
                                        crashLogManager.formatCrashForExport(it)
                                    }
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("TextMorph Crashes", text))
                                    Toast.makeText(context, "Crash reports copied to clipboard", Toast.LENGTH_SHORT).show()
                                } else {
                                    val text = AppLogger.formatForExport(installedVersionName)
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("TextMorph Logs", text))
                                    Toast.makeText(context, "All logs copied to clipboard", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy All Logs")
                        }

                        // Share Logs / Crashes
                        IconButton(
                            onClick = {
                                val text = if (filterType == LogFilterType.CRASHES) {
                                    crashReports.joinToString("\n\n" + "=".repeat(60) + "\n\n") {
                                        crashLogManager.formatCrashForExport(it)
                                    }
                                } else {
                                    AppLogger.formatForExport(installedVersionName)
                                }
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, if (filterType == LogFilterType.CRASHES) "TextMorph Crash Reports" else "TextMorph Debug Logs")
                                    putExtra(Intent.EXTRA_TEXT, text)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share Diagnostics"))
                            }
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share Logs")
                        }

                        // Clear Logs or Crashes
                        IconButton(
                            onClick = {
                                if (filterType == LogFilterType.CRASHES) {
                                    crashLogManager.clearCrashes()
                                    crashReports = emptyList()
                                    Toast.makeText(context, "Crash reports cleared from disk", Toast.LENGTH_SHORT).show()
                                } else {
                                    AppLogger.clear()
                                    Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear", tint = MaterialTheme.colorScheme.error)
                        }
                    }

                    // Search input
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        placeholder = { Text("Filter by tag, message, error or stack trace...", fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear search", modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    )

                    // Filter chips row
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        item {
                            FilterChip(
                                selected = filterType == LogFilterType.ALL,
                                onClick = { filterType = LogFilterType.ALL },
                                label = { Text("All (${allLogs.size})", fontSize = 11.sp) }
                            )
                        }
                        item {
                            val crashCount = crashReports.size
                            FilterChip(
                                selected = filterType == LogFilterType.CRASHES,
                                onClick = {
                                    crashReports = crashLogManager.getCrashReports()
                                    filterType = LogFilterType.CRASHES
                                },
                                label = { Text("💥 Crashes ($crashCount)", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer
                                )
                            )
                        }
                        item {
                            val errorCount = allLogs.count { it.level == LogLevel.ERROR }
                            FilterChip(
                                selected = filterType == LogFilterType.ERRORS,
                                onClick = { filterType = LogFilterType.ERRORS },
                                label = { Text("Errors ($errorCount)", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer
                                )
                            )
                        }
                        item {
                            val warnCount = allLogs.count { it.level == LogLevel.WARN }
                            FilterChip(
                                selected = filterType == LogFilterType.WARNINGS,
                                onClick = { filterType = LogFilterType.WARNINGS },
                                label = { Text("Warnings ($warnCount)", fontSize = 11.sp) }
                            )
                        }
                        item {
                            val infoCount = allLogs.count { it.level == LogLevel.INFO }
                            FilterChip(
                                selected = filterType == LogFilterType.INFO,
                                onClick = { filterType = LogFilterType.INFO },
                                label = { Text("Info ($infoCount)", fontSize = 11.sp) }
                            )
                        }
                        item {
                            val debugCount = allLogs.count { it.level == LogLevel.DEBUG }
                            FilterChip(
                                selected = filterType == LogFilterType.DEBUG,
                                onClick = { filterType = LogFilterType.DEBUG },
                                label = { Text("Debug ($debugCount)", fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }
        },
        modifier = modifier
    ) { paddingValues ->
        if (filterType == LogFilterType.CRASHES) {
            if (filteredCrashes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (searchQuery.isNotEmpty()) "No crashes match \"$searchQuery\"" else "No crash reports recorded on disk.\nSystem running healthy!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .navigationBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredCrashes, key = { it.id }) { report ->
                        CrashReportCard(
                            report = report,
                            onCopy = {
                                val text = crashLogManager.formatCrashForExport(report)
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Crash Report", text))
                                Toast.makeText(context, "Crash report copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        } else {
            if (filteredLogs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (searchQuery.isNotEmpty()) "No logs match \"$searchQuery\"" else "No logs recorded yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .navigationBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredLogs, key = { it.id }) { logItem ->
                        LogCard(logItem = logItem)
                    }
                }
            }
        }
    }
}
