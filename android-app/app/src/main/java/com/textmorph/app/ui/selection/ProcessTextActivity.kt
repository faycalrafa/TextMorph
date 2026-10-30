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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material3.AssistChip
import com.textmorph.app.ui.prompts.getVectorIcon
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
import com.textmorph.app.llm.LiteRtLmEngineManager
import com.textmorph.app.llm.LlamaCppEngine
import com.textmorph.app.llm.LlamaCppEngineManager
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
    private lateinit var llamaCppEngine: LlamaCppEngine
    private lateinit var cloudEngine: OpenAiCloudEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val selectedText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() ?: ""
        val isReadOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)

        AppLogger.i(TAG, "Triggered with text length=${selectedText.length}, readOnly=$isReadOnly")

        promptRepository = PromptRepository.getInstance(applicationContext)
        settingsRepository = SettingsRepository.getInstance(applicationContext)
        liteRtEngine = LiteRtLmEngineManager.getInstance(applicationContext)
        llamaCppEngine = LlamaCppEngineManager.getInstance(applicationContext)
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
                    val liteRtSessionState by liteRtEngine.sessionState.collectAsState()
                    val llamaSessionState by llamaCppEngine.sessionState.collectAsState()
                    val isVisionCapable = when (settings.selectedEngine) {
                        EngineType.ON_DEVICE_LLAMA_CPP -> llamaSessionState.isVisionCapable
                        else -> liteRtSessionState.isVisionCapable
                    }

                    ProcessTextContent(
                        originalText = selectedText,
                        isReadOnly = isReadOnly,
                        prompts = prompts,
                        isVisionCapable = isVisionCapable,
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
                            } else if (currentSettings.selectedEngine == EngineType.ON_DEVICE_LLAMA_CPP) {
                                llamaCppEngine
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
    isVisionCapable: Boolean,
    onApplyResult: (String) -> Unit,
    onCancel: () -> Unit,
    engineProvider: () -> LlmEngine
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var customInstruction by remember { mutableStateOf("") }
    var resultText by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var streamJob by remember { mutableStateOf<Job?>(null) }

    var selectedImageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var selectedImageBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) {
                    selectedImageBytes = bytes
                    selectedImageBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    AppLogger.i("ProcessTextActivity", "Attached visual context: ${bytes.size} bytes")
                }
            } catch (e: Exception) {
                AppLogger.e("ProcessTextActivity", "Failed reading selected image: ${e.message}", e)
                Toast.makeText(context, "Could not load image: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun executeInference(instruction: String) {
        streamJob?.cancel()
        resultText = ""
        isGenerating = true
        val engine = engineProvider()

        streamJob = scope.launch {
            try {
                val effectivePrompt = originalText.ifBlank { "Describe and analyze the attached image." }
                engine.generateStream(
                    prompt = effectivePrompt,
                    systemInstruction = instruction,
                    imageBytes = selectedImageBytes
                ).collect { token ->
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
                val vectorIcon = prompt.getVectorIcon()
                AssistChip(
                    onClick = {
                        customInstruction = prompt.systemInstruction
                        executeInference(prompt.systemInstruction)
                    },
                    leadingIcon = if (vectorIcon != null) {
                        {
                            Icon(
                                imageVector = vectorIcon,
                                contentDescription = prompt.title,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else null,
                    label = {
                        val text = if (vectorIcon != null) prompt.title else "${prompt.iconEmoji} ${prompt.title}"
                        Text(text, fontSize = 12.sp)
                    }
                )
            }
        }

        // Attached image preview
        if (selectedImageBitmap != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Image(
                            bitmap = selectedImageBitmap!!.asImageBitmap(),
                            contentDescription = "Attached Context",
                            contentScale = ContentScale.Crop
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "📷 Visual Context Attached",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "${(selectedImageBytes?.size ?: 0) / 1024} KB • Multimodal input ready",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = {
                            selectedImageBytes = null
                            selectedImageBitmap = null
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Remove Image", modifier = Modifier.size(16.dp))
                    }
                }
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
                placeholder = { Text("Custom instruction (e.g. summarize, describe image)...", fontSize = 12.sp) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(10.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(
                onClick = {
                    if (!isVisionCapable) {
                        Toast.makeText(context, "Current model is pure text-only (vision not supported).", Toast.LENGTH_SHORT).show()
                    } else {
                        photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Default.AddPhotoAlternate,
                    contentDescription = "Attach Image",
                    tint = if (!isVisionCapable) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    else if (selectedImageBytes != null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = {
                    if (customInstruction.isNotBlank() || selectedImageBytes != null) {
                        executeInference(customInstruction.ifBlank { "Describe and explain this image." })
                    }
                },
                enabled = (customInstruction.isNotBlank() || selectedImageBytes != null) && !isGenerating
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
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Discard")
                }

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
