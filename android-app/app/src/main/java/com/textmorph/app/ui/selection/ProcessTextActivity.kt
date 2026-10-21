package com.textmorph.app.ui.selection

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.model.EngineType
import com.textmorph.app.data.model.PromptCategory
import com.textmorph.app.data.model.PromptEntity
import com.textmorph.app.data.repository.PromptRepository
import com.textmorph.app.data.repository.SettingsRepository
import com.textmorph.app.llm.LiteRtLmEngine
import com.textmorph.app.llm.LlmEngine
import com.textmorph.app.llm.OpenAiCloudEngine
import com.textmorph.app.theme.TextMorphTheme
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ProcessTextActivity : ComponentActivity() {

    private val TAG = "ProcessTextActivity"
    private lateinit var promptRepository: PromptRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var liteRtEngine: LiteRtLmEngine
    private lateinit var cloudEngine: OpenAiCloudEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val selectedText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() ?: ""
        val isReadOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)

        AppLogger.i(TAG, "Triggered with text length=${selectedText.length}, readOnly=$isReadOnly")

        promptRepository = PromptRepository(applicationContext)
        settingsRepository = SettingsRepository(applicationContext)
        liteRtEngine = LiteRtLmEngine(applicationContext)
        cloudEngine = OpenAiCloudEngine()

        setContent {
            val settings by settingsRepository.settingsState.collectAsState()
            val prompts by promptRepository.promptsState.collectAsState()

            TextMorphTheme(themeMode = settings.theme) {
                Surface(
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.98f),
                    modifier = Modifier.padding(12.dp),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    ProcessTextContent(
                        originalText = selectedText,
                        isReadOnly = isReadOnly,
                        prompts = prompts,
                        onApplyResult = { transformed ->
                            if (!isReadOnly) {
                                val resultIntent = Intent().apply {
                                    putExtra(Intent.EXTRA_PROCESS_TEXT, transformed)
                                }
                                setResult(Activity.RESULT_OK, resultIntent)
                            } else {
                                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("TextMorph Result", transformed))
                                Toast.makeText(this@ProcessTextActivity, "Result copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                            finish()
                        },
                        onCancel = {
                            setResult(Activity.RESULT_CANCELED)
                            finish()
                        },
                        engineProvider = {
                            val currentSettings = settingsRepository.settingsState.value
                            val useCloud = currentSettings.selectedEngine == EngineType.CLOUD_OPENAI &&
                                    (currentSettings.cloudApiKey.isNotBlank() ||
                                            currentSettings.cloudBaseUrl.contains("localhost") ||
                                            currentSettings.cloudBaseUrl.contains("127.0.0.1"))
                            if (useCloud) {
                                cloudEngine.updateConfig(
                                    currentSettings.cloudBaseUrl,
                                    currentSettings.cloudApiKey,
                                    currentSettings.cloudModelId
                                )
                                cloudEngine
                            } else {
                                liteRtEngine
                            }
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProcessTextContent(
    originalText: String,
    isReadOnly: Boolean,
    prompts: List<PromptEntity>,
    onApplyResult: (String) -> Unit,
    onCancel: () -> Unit,
    engineProvider: () -> LlmEngine
) {
    val scope = rememberCoroutineScope()
    var customInstruction by remember { mutableStateOf("") }
    var resultText by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var streamJob by remember { mutableStateOf<Job?>(null) }

    fun executeInference(instruction: String) {
        streamJob?.cancel()
        resultText = ""
        isGenerating = true
        val engine = engineProvider()

        streamJob = scope.launch {
            try {
                engine.generateStream(originalText, instruction).collect { token ->
                    resultText += token
                }
            } catch (e: Exception) {
                AppLogger.e("ProcessTextContent", "Generation error: ${e.message}", e)
                resultText = "Error during generation: ${e.message}"
            } finally {
                isGenerating = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "TextMorph AI",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "Cancel")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Text preview card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Text(
                text = originalText.ifBlank { "(No text selected)" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(10.dp),
                maxLines = 3
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Quick prompt chips
        Text(
            text = "Quick Transforms",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(6.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            prompts.take(6).forEach { prompt ->
                AssistChip(
                    onClick = {
                        customInstruction = prompt.systemInstruction
                        executeInference(prompt.systemInstruction)
                    },
                    label = { Text("${prompt.iconEmoji} ${prompt.title}", fontSize = 12.sp) }
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Custom instruction input
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = customInstruction,
                onValueChange = { customInstruction = it },
                placeholder = { Text("Custom instruction (e.g. summarize in 2 sentences)...", fontSize = 12.sp) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(10.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = {
                    if (customInstruction.isNotBlank()) {
                        executeInference(customInstruction)
                    }
                },
                enabled = customInstruction.isNotBlank() && !isGenerating
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Run", tint = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Streaming result display
        if (resultText.isNotEmpty() || isGenerating) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isGenerating) "Generating response..." else "Generated Output:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        if (isGenerating) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = resultText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action button (Replace or Copy)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Discard")
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = { onApplyResult(resultText) },
                    enabled = resultText.isNotBlank() && !isGenerating,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = if (!isReadOnly) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (!isReadOnly) "Replace Text" else "Copy to Clipboard")
                }
            }
        }
    }
}
