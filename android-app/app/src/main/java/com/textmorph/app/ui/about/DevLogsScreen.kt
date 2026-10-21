package com.textmorph.app.ui.about

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.HorizontalDivider
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
import com.textmorph.app.util.LogItem
import com.textmorph.app.util.LogLevel
import kotlinx.coroutines.launch

private enum class LogFilterType {
    ALL, ERRORS, WARNINGS, INFO, DEBUG
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

    var filterType by remember { mutableStateOf(LogFilterType.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var isDumpingLogcat by remember { mutableStateOf(false) }

    BackHandler { onNavigateBack() }

    val filteredLogs = remember(allLogs, filterType, searchQuery) {
        allLogs.filter { item ->
            val matchesLevel = when (filterType) {
                LogFilterType.ALL -> true
                LogFilterType.ERRORS -> item.level == LogLevel.ERROR
                LogFilterType.WARNINGS -> item.level == LogLevel.WARN
                LogFilterType.INFO -> item.level == LogLevel.INFO
                LogFilterType.DEBUG -> item.level == LogLevel.DEBUG
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
                                text = "Developer Logs",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Last 60m • ${filteredLogs.size}/${allLogs.size} entries",
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

                        // Copy All Logs
                        IconButton(
                            onClick = {
                                val text = AppLogger.formatForExport(installedVersionName)
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("TextMorph Logs", text))
                                Toast.makeText(context, "All logs copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy All Logs")
                        }

                        // Share Logs
                        IconButton(
                            onClick = {
                                val text = AppLogger.formatForExport(installedVersionName)
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "TextMorph Debug Logs")
                                    putExtra(Intent.EXTRA_TEXT, text)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share Debug Logs"))
                            }
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share Logs")
                        }

                        // Clear Logs
                        IconButton(
                            onClick = {
                                AppLogger.clear()
                                Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear Logs", tint = MaterialTheme.colorScheme.error)
                        }
                    }

                    // Search input
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        placeholder = { Text("Filter by tag, message, or error...", fontSize = 12.sp) },
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

@Composable
private fun LogCard(logItem: LogItem) {
    val context = LocalContext.current
    var isExpanded by remember { mutableStateOf(false) }

    val (badgeColor, textColor) = when (logItem.level) {
        LogLevel.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        LogLevel.WARN -> Color(0xFFFEFCBF) to Color(0xFF744210)
        LogLevel.INFO -> Color(0xFFC6F6D5) to Color(0xFF22543D)
        LogLevel.DEBUG -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (!logItem.stackTrace.isNullOrBlank()) isExpanded = !isExpanded }
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = badgeColor
                    ) {
                        Text(
                            text = logItem.level.name,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = textColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 9.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Text(
                        text = logItem.tag,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    Text(
                        text = logItem.formattedTime,
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        fontSize = 10.sp
                    )
                }

                IconButton(
                    onClick = {
                        val fullText = "[${logItem.formattedTime}] [${logItem.level}] [${logItem.tag}]: ${logItem.message}" +
                                if (!logItem.stackTrace.isNullOrBlank()) "\n${logItem.stackTrace}" else ""
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Log Entry", fullText))
                        Toast.makeText(context, "Log entry copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy Entry",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            SelectionContainer {
                Text(
                    text = logItem.message,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 15.sp
                )
            }

            if (!logItem.stackTrace.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isExpanded) "▼ Hide Stack Trace" else "▶ Show Stack Trace",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 10.sp
                    )
                }

                AnimatedVisibility(visible = isExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .background(Color(0xFF1E1E1E), RoundedCornerShape(6.dp))
                            .padding(8.dp)
                    ) {
                        SelectionContainer {
                            Text(
                                text = logItem.stackTrace,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = Color(0xFFFC8181),
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
