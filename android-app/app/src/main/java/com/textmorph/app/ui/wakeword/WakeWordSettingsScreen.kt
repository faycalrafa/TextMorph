package com.textmorph.app.ui.wakeword

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.model.WakeWordEntity
import com.textmorph.app.data.repository.SettingsRepository
import com.textmorph.app.data.repository.WakeWordRepository
import com.textmorph.app.ui.wakeword.components.CustomWakeWordEditorDialog
import com.textmorph.app.ui.wakeword.components.WakeWordCalibrationDialog
import com.textmorph.app.ui.wakeword.components.WakeWordItemCard
import kotlinx.coroutines.launch

/**
 * Dedicated Wake-Word Studio route for managing offline triggers,
 * 3-attempt acoustic calibration testing, and strict max 2 active limit.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WakeWordSettingsScreen(
    onNavigateBack: () -> Unit,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsRepo = remember { SettingsRepository.getInstance(context) }
    val wakeRepo = remember { WakeWordRepository.getInstance(context) }

    val settings by settingsRepo.settingsState.collectAsState()
    val wakeWords by wakeRepo.wakeWordsState.collectAsState()

    var selectedFilter by remember { mutableStateOf("All") } // "All", "Active", "Built-in", "Custom"
    var calibratingEntity by remember { mutableStateOf<WakeWordEntity?>(null) }
    var showCustomEditor by remember { mutableStateOf(false) }

    BackHandler { onNavigateBack() }

    val activeCount = wakeWords.count { it.isEnabled }
    val filteredWords = remember(wakeWords, selectedFilter) {
        when (selectedFilter) {
            "Active" -> wakeWords.filter { it.isEnabled }
            "Built-in" -> wakeWords.filter { it.isBuiltIn }
            "Custom" -> wakeWords.filter { !it.isBuiltIn }
            else -> wakeWords
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B1020))
    ) {
        // Top App Bar
        Surface(
            color = Color(0xFF111827),
            shadowElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color(0xFFE5E7EB)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Wake-Word Studio",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Offline trigger calibration & management",
                        color = Color(0xFF9CA3AF),
                        fontSize = 11.5.sp
                    )
                }

                IconButton(onClick = onOpenDrawer) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Open Drawer",
                        tint = Color(0xFFE5E7EB)
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Master Toggle Card
            item {
                Surface(
                    color = Color(0xFF111827),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, if (settings.wakeWordDetectionEnabled) Color(0xFF2563EB).copy(alpha = 0.5f) else Color(0xFF374151)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    if (settings.wakeWordDetectionEnabled) Color(0xFF1D4ED8).copy(alpha = 0.25f) else Color(0xFF374151).copy(alpha = 0.4f),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                tint = if (settings.wakeWordDetectionEnabled) Color(0xFF60A5FA) else Color(0xFF9CA3AF),
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Wake-Word Detection",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = if (settings.wakeWordDetectionEnabled) "Active in background service" else "Detection service paused",
                                fontSize = 11.5.sp,
                                color = if (settings.wakeWordDetectionEnabled) Color(0xFF34D399) else Color(0xFF9CA3AF)
                            )
                        }

                        Switch(
                            checked = settings.wakeWordDetectionEnabled,
                            onCheckedChange = { isChecked ->
                                scope.launch {
                                    settingsRepo.updateSettings { it.copy(wakeWordDetectionEnabled = isChecked) }
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF2563EB),
                                uncheckedThumbColor = Color(0xFF9CA3AF),
                                uncheckedTrackColor = Color(0xFF1F2937)
                            )
                        )
                    }
                }
            }

            // Status Banner: Active Triggers (Max 2) & Add Action
            item {
                Surface(
                    color = Color(0xFF1E293B),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFF334155)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Active Triggers: $activeCount / 2 Max",
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (activeCount == 2) Color(0xFF60A5FA) else Color.White
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Each trigger must pass 3 acoustic test attempts before activation.",
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }

                        Button(
                            onClick = { showCustomEditor = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add Custom", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }

            // Filter Tabs
            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf("All", "Active", "Built-in", "Custom").forEach { tab ->
                        FilterChip(
                            selected = selectedFilter == tab,
                            onClick = { selectedFilter = tab },
                            label = { Text(tab, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF3B82F6),
                                selectedLabelColor = Color.White,
                                containerColor = Color(0xFF1F2937),
                                labelColor = Color(0xFF9CA3AF)
                            )
                        )
                    }
                }
            }

            // Items List
            items(filteredWords, key = { it.id }) { item ->
                WakeWordItemCard(
                    wakeWord = item,
                    onToggleActive = {
                        scope.launch {
                            val res = wakeRepo.toggleWakeWord(item.id)
                            res.onFailure { t ->
                                Toast.makeText(context, t.message ?: "Failed toggling wake-word", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onCalibrationRequired = {
                        Toast.makeText(
                            context,
                            "Calibrate '${item.displayName}' with 3 acoustic test passes before activation.",
                            Toast.LENGTH_LONG
                        ).show()
                        calibratingEntity = item
                    },
                    onTestCalibrate = {
                        calibratingEntity = item
                    },
                    onDelete = if (!item.isBuiltIn) {
                        {
                            scope.launch {
                                wakeRepo.deleteWakeWord(item.id)
                                Toast.makeText(context, "Deleted custom trigger '${item.displayName}'", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else null
                )
            }
        }
    }

    // Calibration Dialog
    calibratingEntity?.let { entity ->
        WakeWordCalibrationDialog(
            targetWakeWord = entity,
            onCalibrationSuccess = { autoEnabled ->
                scope.launch {
                    wakeRepo.recordCalibrationSuccess(entity.id)
                    val msg = if (autoEnabled) {
                        "✓ '${entity.displayName}' calibrated and activated!"
                    } else {
                        "✓ '${entity.displayName}' calibrated! (Deactivate an existing trigger to enable)"
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
                calibratingEntity = null
            },
            onDismiss = {
                calibratingEntity = null
            }
        )
    }

    // Custom Wake-Word Creator Dialog
    if (showCustomEditor) {
        CustomWakeWordEditorDialog(
            onDismiss = { showCustomEditor = false },
            onSave = { newEntity ->
                scope.launch {
                    val res = wakeRepo.saveCustomWakeWord(newEntity)
                    showCustomEditor = false
                    res.onSuccess {
                        Toast.makeText(context, "Added '${newEntity.displayName}'. Test it 3x to calibrate!", Toast.LENGTH_LONG).show()
                        calibratingEntity = newEntity
                    }.onFailure { t ->
                        Toast.makeText(context, t.message ?: "Failed saving trigger", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }
}
