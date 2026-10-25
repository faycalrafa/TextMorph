package com.textmorph.app.ui.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.textmorph.app.data.model.AppSettings
import com.textmorph.app.util.AppLogger
import com.textmorph.app.util.PermissionUtils

@Composable
fun ToolbarSettingsSection(
    settings: AppSettings,
    onSetFloatingToolbarEnabled: (Boolean) -> Unit,
    onSetAlwaysShowFloatingToolbar: (Boolean) -> Unit,
    onSetAlwaysOpenBarWithKeyboard: (Boolean) -> Unit,
    onSetCompactChipsMode: (Boolean) -> Unit,
    onSetQuickFixEnabled: (Boolean) -> Unit,
    onSetGuessNextWordEnabled: (Boolean) -> Unit = {},
    onSetAutoSendVoiceInput: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val TAG = "ToolbarSettingsSection"
    var showPermissionDialog by remember { mutableStateOf(false) }
    var missingServiceType by remember { mutableStateOf<String?>(null) }

    fun checkAndPromptPermissions(): Boolean {
        return if (!PermissionUtils.isAccessibilityServiceEnabled(context)) {
            AppLogger.w(TAG, "Floating toolbar enabled but Accessibility Service is disabled")
            missingServiceType = "accessibility"
            showPermissionDialog = true
            false
        } else if (!PermissionUtils.canDrawOverlays(context)) {
            AppLogger.w(TAG, "Floating toolbar enabled but Display Over Apps permission is missing")
            missingServiceType = "overlay"
            showPermissionDialog = true
            false
        } else {
            true
        }
    }

    if (showPermissionDialog) {
        val isAccessibility = missingServiceType == "accessibility"
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = {
                Text(
                    text = if (isAccessibility) "Accessibility Service Required" else "Display Over Apps Required",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF9FAFB)
                )
            },
            text = {
                Text(
                    text = if (isAccessibility) {
                        "TextMorph uses the Android Accessibility Service to draw the floating assistant companion over your active apps and detect input focus. Please enable TextMorph under Downloaded Services in Accessibility Settings."
                    } else {
                        "Display Over Other Apps permission is required so the floating companion can appear above your screen. Please grant this permission in Android Settings."
                    },
                    color = Color(0xFFD1D5DB)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPermissionDialog = false
                        if (isAccessibility) {
                            PermissionUtils.openAccessibilitySettings(context)
                        } else {
                            PermissionUtils.requestOverlayPermission(context)
                        }
                    }
                ) {
                    Text("Open Settings", fontWeight = FontWeight.Bold, color = Color(0xFF60A5FA))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) {
                    Text("Cancel", color = Color(0xFF9CA3AF))
                }
            },
            containerColor = Color(0xFF1F2937),
            properties = DialogProperties(usePlatformDefaultWidth = false)
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Widgets, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(10.dp))
                Text(text = "Writing Assistant Overlays", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Floating Toolbar", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("Show pill above active editable fields", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = settings.floatingToolbarEnabled,
                    onCheckedChange = { enabled ->
                        onSetFloatingToolbarEnabled(enabled)
                        if (enabled) {
                            checkAndPromptPermissions()
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Always Show Floating Button", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("Keep floating assistant visible on screen at all times, even without keyboard open", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = settings.alwaysShowFloatingToolbar,
                    enabled = settings.floatingToolbarEnabled,
                    onCheckedChange = { enabled ->
                        onSetAlwaysShowFloatingToolbar(enabled)
                        if (enabled) {
                            checkAndPromptPermissions()
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-Open Bar with Keyboard", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("Automatically expand the assistant bar whenever the keyboard appears", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = settings.alwaysOpenBarWithKeyboard,
                    enabled = settings.floatingToolbarEnabled,
                    onCheckedChange = onSetAlwaysOpenBarWithKeyboard
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Compact Toolbar (Icons Only)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("Display compact icon chips instead of text labels in the floating toolbar to save screen space", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = settings.compactChipsMode,
                    enabled = settings.floatingToolbarEnabled,
                    onCheckedChange = onSetCompactChipsMode
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto Quick Fix", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("Automatically fixes grammar & spelling errors in-place with an Undo/Accept prompt when typing stops", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = settings.quickFixEnabled,
                    enabled = settings.floatingToolbarEnabled,
                    onCheckedChange = onSetQuickFixEnabled
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-Send Voice Dictation", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("Automatically transmit the prompt immediately when microphone speech recognition completes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = settings.autoSendVoiceInput,
                    onCheckedChange = onSetAutoSendVoiceInput
                )
            }
        }
    }
}
