package com.textmorph.app.ui.chat.components

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.textmorph.app.util.AppLogger
import com.textmorph.app.util.PermissionUtils

/**
 * Floating in-chat dismissible tooltip warning users of missing system permissions.
 * Persists dismiss state for the current app session only, reminding the user on next boot.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MissingPermissionsTooltip(
    onOpenAccessibilityDisclosure: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current

    var isAccessibilityGranted by remember { mutableStateOf(PermissionUtils.isAccessibilityServiceEnabled(context)) }
    var isBatteryGranted by remember { mutableStateOf(PermissionUtils.isIgnoringBatteryOptimizations(context)) }
    var isStorageGranted by remember { mutableStateOf(PermissionUtils.isExternalStorageManager()) }
    var isNotificationGranted by remember { mutableStateOf(PermissionUtils.hasNotificationPermission(context)) }

    // Session-only dismiss state (resets on app process restart)
    var isDismissed by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                try {
                    isAccessibilityGranted = PermissionUtils.isAccessibilityServiceEnabled(context)
                    isBatteryGranted = PermissionUtils.isIgnoringBatteryOptimizations(context)
                    isStorageGranted = PermissionUtils.isExternalStorageManager()
                    isNotificationGranted = PermissionUtils.hasNotificationPermission(context)
                } catch (e: Exception) {
                    AppLogger.e("MissingPermissionsTooltip", "Error refreshing permissions status", e)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val hasMissingPermissions = !isAccessibilityGranted || !isBatteryGranted || !isStorageGranted || !isNotificationGranted

    AnimatedVisibility(
        visible = hasMissingPermissions && !isDismissed,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xF21F2937), // Elevated Charcoal Slate
            border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.45f)), // Warm amber border
            shadowElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(0x33F59E0B)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = Color(0xFFFBBF24),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Setup Required",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF9FAFB)
                            )
                            Text(
                                text = "Grant permissions for full floating & agent abilities",
                                fontSize = 11.sp,
                                color = Color(0xFF9CA3AF)
                            )
                        }
                    }

                    // Dismiss button
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color(0x22FFFFFF))
                            .clickable { isDismissed = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss reminder",
                            tint = Color(0xFFE5E7EB),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Missing Permission Action Chips
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (!isAccessibilityGranted) {
                        PermissionActionChip(
                            label = "Accessibility",
                            icon = Icons.Default.Widgets,
                            onClick = onOpenAccessibilityDisclosure
                        )
                    }
                    if (!isStorageGranted) {
                        PermissionActionChip(
                            label = "Storage Access",
                            icon = Icons.Default.FolderOpen,
                            onClick = {
                                try {
                                    PermissionUtils.requestManageAllFilesAccess(context)
                                } catch (e: Exception) {
                                    AppLogger.e("MissingPermissionsTooltip", "Failed to request storage", e)
                                }
                            }
                        )
                    }
                    if (!isBatteryGranted) {
                        PermissionActionChip(
                            label = "Battery Unrestricted",
                            icon = Icons.Default.BatteryAlert,
                            onClick = {
                                try {
                                    PermissionUtils.requestIgnoreBatteryOptimizations(context)
                                } catch (e: Exception) {
                                    AppLogger.e("MissingPermissionsTooltip", "Failed to request battery", e)
                                }
                            }
                        )
                    }
                    if (!isNotificationGranted) {
                        PermissionActionChip(
                            label = "Notifications",
                            icon = Icons.Default.Notifications,
                            onClick = {
                                try {
                                    activity?.let { PermissionUtils.requestNotificationPermission(it) }
                                } catch (e: Exception) {
                                    AppLogger.e("MissingPermissionsTooltip", "Failed to request notifications", e)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionActionChip(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0x333B82F6),
        border = BorderStroke(1.dp, Color(0x663B82F6)),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color(0xFF60A5FA),
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF93C5FD)
            )
        }
    }
}
