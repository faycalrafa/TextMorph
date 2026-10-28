package com.textmorph.app.ui.setup

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.textmorph.app.ui.setup.components.SetupPermissionItemCard
import com.textmorph.app.util.ClipboardHelper
import com.textmorph.app.util.PermissionUtils

/**
 * Interactive Setup & Permissions Guide screen.
 * Details every permission required by TextMorph, explaining feature purposes
 * and offering direct one-tap configuration actions.
 */
@Composable
fun SetupPermissionsScreen(
    onOpenDrawer: () -> Unit,
    onOpenAccessibilityDisclosure: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current

    var isAccessibilityGranted by remember { mutableStateOf(PermissionUtils.isAccessibilityServiceEnabled(context)) }
    var isOverlayGranted by remember { mutableStateOf(PermissionUtils.canDrawOverlays(context)) }
    var isBatteryGranted by remember { mutableStateOf(PermissionUtils.isIgnoringBatteryOptimizations(context)) }
    var isStorageGranted by remember { mutableStateOf(PermissionUtils.isExternalStorageManager()) }
    var isNotificationGranted by remember { mutableStateOf(PermissionUtils.hasNotificationPermission(context)) }
    var isAudioGranted by remember { mutableStateOf(PermissionUtils.hasRecordAudioPermission(context)) }
    var isContactsGranted by remember { mutableStateOf(PermissionUtils.hasContactsPermission(context)) }
    var isPhoneGranted by remember { mutableStateOf(PermissionUtils.hasCallPhonePermission(context)) }
    var isSettingsGranted by remember { mutableStateOf(PermissionUtils.canWriteSystemSettings(context)) }
    var isCameraGranted by remember { mutableStateOf(PermissionUtils.hasCameraPermission(context)) }

    var clipboardTestStatus by remember { mutableStateOf<String?>(null) }
    var isTestingClipboard by remember { mutableStateOf(false) }

    fun refreshAll() {
        isAccessibilityGranted = PermissionUtils.isAccessibilityServiceEnabled(context)
        isOverlayGranted = PermissionUtils.canDrawOverlays(context)
        isBatteryGranted = PermissionUtils.isIgnoringBatteryOptimizations(context)
        isStorageGranted = PermissionUtils.isExternalStorageManager()
        isNotificationGranted = PermissionUtils.hasNotificationPermission(context)
        isAudioGranted = PermissionUtils.hasRecordAudioPermission(context)
        isContactsGranted = PermissionUtils.hasContactsPermission(context)
        isPhoneGranted = PermissionUtils.hasCallPhonePermission(context)
        isSettingsGranted = PermissionUtils.canWriteSystemSettings(context)
        isCameraGranted = PermissionUtils.hasCameraPermission(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshAll()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionsList = listOf(
        isAccessibilityGranted, isOverlayGranted, isAudioGranted,
        isStorageGranted, isBatteryGranted, isContactsGranted,
        isPhoneGranted, isSettingsGranted, isCameraGranted, isNotificationGranted
    )
    val grantedCount = permissionsList.count { it }
    val totalCount = permissionsList.size
    val progress = grantedCount.toFloat() / totalCount

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B1020))
    ) {
        // Top Header
        Surface(
            color = Color(0xFF111827),
            shadowElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onOpenDrawer) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Open Drawer",
                        tint = Color(0xFFE5E7EB)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Setup & Permissions Guide",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "System privileges & clipboard diagnostics",
                        color = Color(0xFF9CA3AF),
                        fontSize = 11.5.sp
                    )
                }
                IconButton(onClick = { refreshAll() }) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = Color(0xFF3B82F6)
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Readiness Overview Banner
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF1F2937),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF374151)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "System Readiness Score",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "$grantedCount / $totalCount Granted",
                                color = if (grantedCount == totalCount) Color(0xFF10B981) else Color(0xFF3B82F6),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        LinearProgressIndicator(
                            progress = { progress },
                            color = if (grantedCount == totalCount) Color(0xFF10B981) else Color(0xFF3B82F6),
                            trackColor = Color(0xFF374151),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = if (grantedCount == totalCount)
                                "All system privileges are active. TextMorph is fully prepared for voice triggers, ScreenAssist, and offline AI inference."
                            else
                                "Some capabilities are restricted. Review the cards below to enable voice keys, clipboard dumping, or app automation.",
                            fontSize = 12.sp,
                            color = Color(0xFF9CA3AF),
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            // Section 1: Core AI & Screen Perception
            item {
                Text(
                    text = "Core AI & Screen Perception",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF60A5FA),
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
            }

            item {
                SetupPermissionItemCard(
                    title = "Accessibility Service",
                    description = "Crucial for ScreenAssist, reading visible screen elements, executing clicks/taps, and anchoring the companion toolbar directly above keyboards without stealing input focus.",
                    icon = Icons.Default.Widgets,
                    isGranted = isAccessibilityGranted,
                    actionLabel = "Enable Service",
                    onAction = onOpenAccessibilityDisclosure
                )
            }

            item {
                SetupPermissionItemCard(
                    title = "Display Over Other Apps (Overlay)",
                    description = "Enables the floating companion bubble, the quick prompt ribbon, and real-time execution dot animations across all apps.",
                    icon = Icons.Default.Layers,
                    isGranted = isOverlayGranted,
                    actionLabel = "Allow Overlay",
                    onAction = {
                        PermissionUtils.requestOverlayPermission(context)
                        isOverlayGranted = PermissionUtils.canDrawOverlays(context)
                    }
                )
            }

            item {
                SetupPermissionItemCard(
                    title = "Voice Input & Hotkeys (Microphone)",
                    description = "Required to listen for hot voice triggers (like \"assist me\", \"call my brother\"), voice mission instructions, and speech-to-text dictation.",
                    icon = Icons.Default.Mic,
                    isGranted = isAudioGranted,
                    actionLabel = "Allow Microphone",
                    onAction = {
                        activity?.let { PermissionUtils.requestRecordAudioPermission(it) }
                    }
                )
            }

            item {
                SetupPermissionItemCard(
                    title = "Clipboard Engine & Focus Helper",
                    description = "Android 10+ restricts background apps from reading clipboard data. TextMorph maintains an in-memory cache and uses a transparent focus helper to reliably dump ScreenAssist results and read copied text.",
                    icon = Icons.AutoMirrored.Filled.Assignment,
                    isGranted = true,
                    actionLabel = "Settings",
                    onAction = { PermissionUtils.openAppSettings(context) },
                    testActionLabel = if (isTestingClipboard) "Testing…" else "Test Clipboard Engine",
                    onTestAction = {
                        if (!isTestingClipboard) {
                            isTestingClipboard = true
                            clipboardTestStatus = "Writing & reading clipboard echo…"
                            ClipboardHelper.testClipboardEcho(context) { success, msg ->
                                isTestingClipboard = false
                                clipboardTestStatus = msg
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }

            if (clipboardTestStatus != null) {
                item {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF1E293B),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3B82F6).copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Diagnostic Result: $clipboardTestStatus",
                            color = Color(0xFF93C5FD),
                            fontSize = 11.5.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            // Section 2: Model Storage & Background Warmth
            item {
                Text(
                    text = "Model Storage & Engine Warmth",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF60A5FA),
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
            }

            item {
                SetupPermissionItemCard(
                    title = "Zero-Copy Downloads Storage Access",
                    description = "Allows TextMorph to memory-map (mmap) multi-gigabyte .litertlm and .gguf AI models straight from your Downloads folder with zero disk space duplication.",
                    icon = Icons.Default.FolderOpen,
                    isGranted = isStorageGranted,
                    actionLabel = "Grant All Files Access",
                    onAction = {
                        PermissionUtils.requestManageAllFilesAccess(context)
                        isStorageGranted = PermissionUtils.isExternalStorageManager()
                    }
                )
            }

            item {
                SetupPermissionItemCard(
                    title = "Unrestricted Background Engine (Battery)",
                    description = "Prevents Android Doze from killing the warm GPU model and embedded localhost API server (127.0.0.1:8080) when running in background.",
                    icon = Icons.Default.BatteryAlert,
                    isGranted = isBatteryGranted,
                    actionLabel = "Whitelist Battery",
                    onAction = {
                        PermissionUtils.requestIgnoreBatteryOptimizations(context)
                        isBatteryGranted = PermissionUtils.isIgnoringBatteryOptimizations(context)
                    }
                )
            }

            // Section 3: Device Automations & Tools
            item {
                Text(
                    text = "Device Automations & Tools",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF60A5FA),
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
            }

            item {
                SetupPermissionItemCard(
                    title = "Contacts Lookup",
                    description = "Allows automations like \"call brother\" or \"send SMS\" to match names directly to saved phone numbers on device.",
                    icon = Icons.Default.Phone,
                    isGranted = isContactsGranted,
                    actionLabel = "Allow Contacts",
                    onAction = {
                        activity?.let { PermissionUtils.requestContactsPermission(it) }
                    }
                )
            }

            item {
                SetupPermissionItemCard(
                    title = "Direct Phone Calling",
                    description = "Allows voice triggers like \"call my brother\" to directly initiate calls without requiring manual tap in the dialer.",
                    icon = Icons.Default.Phone,
                    isGranted = isPhoneGranted,
                    actionLabel = "Allow Direct Calls",
                    onAction = {
                        activity?.let { PermissionUtils.requestCallPhonePermission(it) }
                    }
                )
            }

            item {
                SetupPermissionItemCard(
                    title = "System Settings (Volume & Brightness)",
                    description = "Enables script actions and automations (e.g. \"mute volume\", \"set volume to 50%\") to adjust audio and display settings.",
                    icon = Icons.Default.Settings,
                    isGranted = isSettingsGranted,
                    actionLabel = "Allow Settings Write",
                    onAction = {
                        PermissionUtils.openWriteSettings(context)
                        isSettingsGranted = PermissionUtils.canWriteSystemSettings(context)
                    }
                )
            }

            item {
                SetupPermissionItemCard(
                    title = "Camera Input",
                    description = "Enables taking real-world photos directly from the companion toolbar for visual multimodal question answering.",
                    icon = Icons.Default.CameraAlt,
                    isGranted = isCameraGranted,
                    actionLabel = "Allow Camera",
                    onAction = {
                        activity?.let { PermissionUtils.requestCameraPermission(it) }
                    }
                )
            }

            item {
                SetupPermissionItemCard(
                    title = "Foreground Notifications",
                    description = "Displays persistent local server status, model download progress, and active voice mission notifications.",
                    icon = Icons.Default.Notifications,
                    isGranted = isNotificationGranted,
                    actionLabel = "Allow Notifications",
                    onAction = {
                        activity?.let { PermissionUtils.requestNotificationPermission(it) }
                    }
                )
            }
        }
    }
}
