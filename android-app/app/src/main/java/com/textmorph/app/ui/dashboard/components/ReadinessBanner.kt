package com.textmorph.app.ui.dashboard.components

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.textmorph.app.util.PermissionUtils

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReadinessBanner(
    onOpenAccessibilityDisclosure: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity

    var isAccessibilityGranted by remember { mutableStateOf(PermissionUtils.isAccessibilityServiceEnabled(context)) }
    var isBatteryGranted by remember { mutableStateOf(PermissionUtils.isIgnoringBatteryOptimizations(context)) }
    var isStorageGranted by remember { mutableStateOf(PermissionUtils.isExternalStorageManager()) }
    var isNotificationGranted by remember { mutableStateOf(PermissionUtils.hasNotificationPermission(context)) }

    val allReady = isAccessibilityGranted && isBatteryGranted && isStorageGranted && isNotificationGranted

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (allReady) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (allReady) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (allReady) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (allReady) "System Ready for AI" else "System Readiness Checklist",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Accessibility Check Card
            ReadinessItem(
                title = "Floating Toolbar (Accessibility)",
                description = "Required to anchor above input boxes and replace text in-place.",
                icon = Icons.Default.Widgets,
                isGranted = isAccessibilityGranted,
                actionLabel = "Enable",
                onAction = onOpenAccessibilityDisclosure
            )

            // Battery Optimization Check Card
            ReadinessItem(
                title = "Unrestricted Background Engine",
                description = "Prevents OS from killing the local GPU/CPU model in memory.",
                icon = Icons.Default.BatteryAlert,
                isGranted = isBatteryGranted,
                actionLabel = "Allow",
                onAction = {
                    PermissionUtils.requestIgnoreBatteryOptimizations(context)
                    isBatteryGranted = PermissionUtils.isIgnoringBatteryOptimizations(context)
                }
            )

            // Storage / Downloads Check Card
            ReadinessItem(
                title = "Zero-Copy Downloads/ Access",
                description = "Allows direct mmap of .litertlm models without copying 4GB files.",
                icon = Icons.Default.FolderOpen,
                isGranted = isStorageGranted,
                actionLabel = "Grant",
                onAction = {
                    PermissionUtils.requestManageAllFilesAccess(context)
                    isStorageGranted = PermissionUtils.isExternalStorageManager()
                }
            )

            // Notifications Check Card
            if (!isNotificationGranted) {
                ReadinessItem(
                    title = "Engine Status Notification",
                    description = "Required to keep the warm AI inference service persistent.",
                    icon = Icons.Default.Notifications,
                    isGranted = isNotificationGranted,
                    actionLabel = "Allow",
                    onAction = {
                        activity?.let { PermissionUtils.requestNotificationPermission(it) }
                        isNotificationGranted = PermissionUtils.hasNotificationPermission(context)
                    }
                )
            }
        }
    }
}

@Composable
private fun ReadinessItem(
    title: String,
    description: String,
    icon: ImageVector,
    isGranted: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isGranted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (isGranted) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Ready",
                tint = Color(0xFF4CAF50),
                modifier = Modifier.size(20.dp)
            )
        } else {
            Button(
                onClick = onAction,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text(text = actionLabel, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
