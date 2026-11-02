package com.textmorph.app.ui.chat.components

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.textmorph.app.data.model.CloudApiPreset
import com.textmorph.app.llm.OpenAiCloudEngine
import com.textmorph.app.ui.settings.sections.CloudModelSearchDialog
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Full-width responsive dialog to create or edit custom Cloud API presets.
 * Supports model browsing from the remote endpoint with fuzzy filtering.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ApiPresetEditDialog(
    preset: CloudApiPreset? = null,
    onSave: (CloudApiPreset) -> Unit,
    onDelete: ((String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cloudEngine = remember { OpenAiCloudEngine() }

    var name by remember(preset) { mutableStateOf(preset?.name ?: "") }
    var baseUrl by remember(preset) { mutableStateOf(preset?.baseUrl ?: "https://api.openai.com/v1") }
    var apiKey by remember(preset) { mutableStateOf(preset?.apiKey ?: "") }
    var defaultModelId by remember(preset) { mutableStateOf(preset?.defaultModelId ?: "gpt-4o-mini") }
    var isApiKeyVisible by remember { mutableStateOf(false) }

    var isFetchingModels by remember { mutableStateOf(false) }
    var fetchedModelsList by remember { mutableStateOf<List<String>>(emptyList()) }
    var showModelSearchDialog by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .heightIn(max = 640.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (preset == null) "New API Preset" else "Edit API Preset",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF9FAFB)
                        )
                        Text(
                            text = "Configure custom endpoint, credentials & default model",
                            fontSize = 12.sp,
                            color = Color(0xFF9CA3AF)
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF9CA3AF))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 1. Preset Name Field
                Text(
                    text = "PRESET NAME",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF60A5FA),
                    letterSpacing = 0.8.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("e.g. Ollama, OpenRouter, Work API", color = Color(0xFF6B7280)) },
                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF3B82F6),
                        unfocusedBorderColor = Color(0xFF374151),
                        focusedContainerColor = Color(0xFF1F2937),
                        unfocusedContainerColor = Color(0xFF1F2937)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(14.dp))

                // 2. Base URL Field
                Text(
                    text = "BASE URL (OPENAI COMPATIBLE)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF60A5FA),
                    letterSpacing = 0.8.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    placeholder = { Text("https://api.openai.com/v1", color = Color(0xFF6B7280)) },
                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF3B82F6),
                        unfocusedBorderColor = Color(0xFF374151),
                        focusedContainerColor = Color(0xFF1F2937),
                        unfocusedContainerColor = Color(0xFF1F2937)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(14.dp))

                // 3. API Key Field
                Text(
                    text = "API KEY (OPTIONAL FOR LOCAL ENDPOINTS)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF60A5FA),
                    letterSpacing = 0.8.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    placeholder = { Text("sk-...", color = Color(0xFF6B7280)) },
                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                    visualTransformation = if (isApiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { isApiKeyVisible = !isApiKeyVisible }) {
                            Icon(
                                imageVector = if (isApiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (isApiKeyVisible) "Hide API Key" else "Show API Key",
                                tint = Color(0xFF9CA3AF)
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF3B82F6),
                        unfocusedBorderColor = Color(0xFF374151),
                        focusedContainerColor = Color(0xFF1F2937),
                        unfocusedContainerColor = Color(0xFF1F2937)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(14.dp))

                // 4. Default Model ID Field with Browse Button
                Text(
                    text = "DEFAULT MODEL ID",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF60A5FA),
                    letterSpacing = 0.8.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = defaultModelId,
                    onValueChange = { defaultModelId = it },
                    placeholder = { Text("gpt-4o-mini", color = Color(0xFF6B7280)) },
                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
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
                                    if (baseUrl.isBlank()) {
                                        Toast.makeText(context, "Please enter Base URL first", Toast.LENGTH_SHORT).show()
                                        return@IconButton
                                    }
                                    scope.launch {
                                        isFetchingModels = true
                                        try {
                                            val res = cloudEngine.fetchAvailableModels(baseUrl, apiKey)
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
                                            AppLogger.e("ApiPresetEditDialog", "Error fetching models: ${t.message}", t)
                                            Toast.makeText(context, "Error: ${t.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.TravelExplore,
                                    contentDescription = "Browse models from API",
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
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Actions: Save, Cancel, and Delete (if editing)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (name.isBlank()) {
                                Toast.makeText(context, "Please enter a preset name", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            if (baseUrl.isBlank()) {
                                Toast.makeText(context, "Please enter a Base URL", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            val updatedPreset = CloudApiPreset(
                                id = preset?.id ?: UUID.randomUUID().toString(),
                                name = name.trim(),
                                baseUrl = baseUrl.trim(),
                                apiKey = apiKey.trim(),
                                defaultModelId = defaultModelId.trim()
                            )
                            onSave(updatedPreset)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                    ) {
                        Text(if (preset == null) "Create Preset" else "Save Preset")
                    }

                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Cancel", color = Color(0xFF9CA3AF))
                    }

                    if (preset != null && onDelete != null) {
                        OutlinedButton(
                            onClick = {
                                onDelete(preset.id)
                                onDismiss()
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444))
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Delete")
                        }
                    }
                }
            }
        }
    }

    if (showModelSearchDialog) {
        CloudModelSearchDialog(
            models = fetchedModelsList,
            currentModel = defaultModelId,
            onSelectModel = { selected ->
                defaultModelId = selected
                showModelSearchDialog = false
            },
            onDismiss = { showModelSearchDialog = false }
        )
    }
}
