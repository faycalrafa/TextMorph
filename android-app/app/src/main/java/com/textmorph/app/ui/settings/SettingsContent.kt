package com.textmorph.app.ui.settings

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.textmorph.app.data.repository.SettingsRepository
import com.textmorph.app.data.repository.UpdateRepository
import com.textmorph.app.ui.dashboard.components.ReadinessBanner
import com.textmorph.app.ui.settings.sections.AppearanceDiagnosticsSection
import com.textmorph.app.ui.settings.sections.EngineSettingsSection
import com.textmorph.app.ui.settings.sections.LocalServerSettingsSection
import com.textmorph.app.ui.settings.sections.ToolbarSettingsSection
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsContent(
    settingsRepository: SettingsRepository,
    updateRepository: UpdateRepository,
    onOpenDevLogs: () -> Unit,
    onCheckUpdate: () -> Unit,
    onOpenAccessibilityDisclosure: () -> Unit = {},
    onNavigateToModelHub: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by settingsRepository.settingsState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // System Readiness for AI Checklist
        ReadinessBanner(
            onOpenAccessibilityDisclosure = onOpenAccessibilityDisclosure
        )

        // Engine Selection & Hardware Acceleration
        EngineSettingsSection(
            settings = settings,
            onSelectEngine = { engine -> scope.launch { settingsRepository.setEngine(engine) } },
            onSetPreferredAccelerator = { acc -> scope.launch { settingsRepository.setPreferredAccelerator(acc) } },
            onSetProbeTimeout = { ms -> scope.launch { settingsRepository.setProbeTimeout(ms) } }
        )

        // Localhost HTTP Server
        LocalServerSettingsSection(
            settings = settings,
            onSaveServerConfig = { enabled, port, token ->
                scope.launch { settingsRepository.setLocalServer(enabled, port, token) }
            }
        )

        // Centralized Model Hub & Engine Configuration Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(text = "Models & Cloud APIs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Model selection, on-device engine activation (LiteRT / llama.cpp), and Cloud API endpoints (OpenAI, OpenRouter, Groq, Ollama) are now centralized in Model Hub.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onNavigateToModelHub,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Open Model Hub")
                }
            }
        }

        // Overlay & Assistance Preferences
        ToolbarSettingsSection(
            settings = settings,
            onSetFloatingToolbarEnabled = { enabled -> scope.launch { settingsRepository.setFloatingToolbarEnabled(enabled) } },
            onSetAlwaysShowFloatingToolbar = { enabled -> scope.launch { settingsRepository.setAlwaysShowFloatingToolbar(enabled) } },
            onSetAlwaysOpenBarWithKeyboard = { enabled -> scope.launch { settingsRepository.setAlwaysOpenBarWithKeyboard(enabled) } },
            onSetCompactChipsMode = { enabled -> scope.launch { settingsRepository.setCompactChipsMode(enabled) } },
            onSetQuickFixEnabled = { enabled -> scope.launch { settingsRepository.setQuickFixEnabled(enabled) } },
            onSetAutoSendVoiceInput = { enabled -> scope.launch { settingsRepository.setAutoSendVoiceInput(enabled) } },
            onSetWakeWordDetectionEnabled = { enabled -> scope.launch { settingsRepository.setWakeWordDetectionEnabled(enabled) } },
            onSetFuseChatAndAgent = { enabled -> scope.launch { settingsRepository.setFuseChatAndAgent(enabled) } },
            onSetVoiceTtsSpeed = { speed -> scope.launch { settingsRepository.setVoiceTtsSpeed(speed) } }
        )

        // Appearance & Diagnostics
        AppearanceDiagnosticsSection(
            theme = settings.theme,
            onSetTheme = { mode -> scope.launch { settingsRepository.setTheme(mode) } },
            onOpenDevLogs = onOpenDevLogs,
            onCheckUpdate = onCheckUpdate,
            versionName = updateRepository.getCurrentVersionName()
        )
    }
}
