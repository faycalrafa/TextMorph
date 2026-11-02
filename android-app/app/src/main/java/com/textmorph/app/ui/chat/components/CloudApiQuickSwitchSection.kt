package com.textmorph.app.ui.chat.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.model.AppSettings
import com.textmorph.app.data.model.CloudApiPreset
import com.textmorph.app.llm.OpenAiCloudEngine
import com.textmorph.app.ui.settings.sections.CloudModelSearchDialog
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.launch

/**
 * Responsive Cloud API selection section for ModelQuickSwitchBottomSheet.
 * Displays user-created API presets with edit/add dialogues and a live model explorer dialog.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun CloudApiQuickSwitchSection(
    settings: AppSettings,
    stagedPreset: CloudApiPreset?,
    onSelectPreset: (CloudApiPreset) -> Unit,
    stagedCloudModelId: String,
    onModelIdChanged: (String) -> Unit,
    onSavePreset: (CloudApiPreset) -> Unit,
    onDeletePreset: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cloudEngine = remember { OpenAiCloudEngine() }

    var editingPreset by remember { mutableStateOf<CloudApiPreset?>(null) }
    var showEditDialog by remember { mutableStateOf(false) }

    var isFetchingModels by remember { mutableStateOf(false) }
    var fetchedModelsList by remember { mutableStateOf<List<String>>(emptyList()) }
    var showModelSearchDialog by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Presets Header Row with + Add Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "API PRESETS:",
                color = Color(0xFF9CA3AF),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp
            )
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFF1F2937),
                border = BorderStroke(1.dp, Color(0xFF374151)),
                modifier = Modifier.combinedClickable(
                    onClick = {
                        editingPreset = null
                        showEditDialog = true
                    }
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Preset",
                        tint = Color(0xFF60A5FA),
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "New Preset",
                        fontSize = 11.sp,
                        color = Color(0xFF60A5FA),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Presets Chip Row
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            settings.apiPresets.forEach { preset ->
                val isSelected = stagedPreset?.id == preset.id ||
                    (stagedPreset == null && stagedCloudModelId.equals(preset.defaultModelId, ignoreCase = true))

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) Color(0xFF2563EB) else Color(0xFF1F2937),
                    border = BorderStroke(1.dp, if (isSelected) Color(0xFF60A5FA) else Color(0xFF374151)),
                    modifier = Modifier.combinedClickable(
                        onClick = {
                            onSelectPreset(preset)
                            onModelIdChanged(preset.defaultModelId)
                        },
                        onLongClick = {
                            editingPreset = preset
                            showEditDialog = true
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = preset.name,
                            fontSize = 12.sp,
                            color = if (isSelected) Color.White else Color(0xFFE5E7EB),
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                        if (isSelected) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit Preset",
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier
                                    .size(12.dp)
                                    .combinedClickable(onClick = {
                                        editingPreset = preset
                                        showEditDialog = true
                                    })
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Model ID with Explore Button
        Text(
            text = "MODEL ID:",
            color = Color(0xFF9CA3AF),
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp
        )
        OutlinedTextField(
            value = stagedCloudModelId,
            onValueChange = onModelIdChanged,
            textStyle = TextStyle(color = Color.White, fontSize = 13.5.sp),
            trailingIcon = {
                if (isFetchingModels) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF3B82F6)
                    )
                } else {
                    IconButton(
                        onClick = {
                            val activeUrl = stagedPreset?.baseUrl ?: settings.cloudBaseUrl
                            val activeKey = stagedPreset?.apiKey ?: settings.cloudApiKey
                            if (activeUrl.isBlank()) {
                                Toast.makeText(context, "Please select a preset or configure Base URL", Toast.LENGTH_SHORT).show()
                                return@IconButton
                            }
                            scope.launch {
                                isFetchingModels = true
                                try {
                                    val res = cloudEngine.fetchAvailableModels(activeUrl, activeKey)
                                    isFetchingModels = false
                                    res.onSuccess { models ->
                                        if (models.isEmpty()) {
                                            Toast.makeText(context, "No models returned by API.", Toast.LENGTH_SHORT).show()
                                        } else {
                                            fetchedModelsList = models
                                            showModelSearchDialog = true
                                        }
                                    }.onFailure { err ->
                                        Toast.makeText(context, "Failed to fetch models: ${err.message}", Toast.LENGTH_LONG).show()
                                    }
                                } catch (t: Throwable) {
                                    isFetchingModels = false
                                    AppLogger.e("CloudApiQuickSwitchSection", "Error fetching models: ${t.message}", t)
                                    Toast.makeText(context, "Error: ${t.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.TravelExplore,
                            contentDescription = "Browse Models from API",
                            tint = Color(0xFF3B82F6)
                        )
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF3B82F6),
                unfocusedBorderColor = Color(0xFF374151),
                focusedContainerColor = Color(0xFF1F2937),
                unfocusedContainerColor = Color(0xFF1F2937)
            ),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }

    if (showEditDialog) {
        ApiPresetEditDialog(
            preset = editingPreset,
            onSave = onSavePreset,
            onDelete = onDeletePreset,
            onDismiss = { showEditDialog = false }
        )
    }

    if (showModelSearchDialog) {
        CloudModelSearchDialog(
            models = fetchedModelsList,
            currentModel = stagedCloudModelId,
            onSelectModel = { selected ->
                onModelIdChanged(selected)
                showModelSearchDialog = false
            },
            onDismiss = { showModelSearchDialog = false }
        )
    }
}
