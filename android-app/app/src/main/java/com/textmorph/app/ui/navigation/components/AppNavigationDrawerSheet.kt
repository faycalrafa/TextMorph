package com.textmorph.app.ui.navigation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.model.AppSettings
import com.textmorph.app.ui.navigation.AppDestination

/**
 * Clean side navigation drawer sheet for TextMorph, extracted to keep MainAppScaffold under 500 lines.
 */
@Composable
fun AppNavigationDrawerSheet(
    currentDestination: AppDestination,
    installedVersionName: String,
    settings: AppSettings,
    isDrawerOpen: Boolean,
    onSelectDestination: (AppDestination) -> Unit
) {
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.width(300.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxHeight()
        ) {
            // Live Telemetry Header
            MainAppDrawerHeader(
                settings = settings,
                isDrawerOpen = isDrawerOpen
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Scrollable Nav Items
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                DrawerNavItem(
                    icon = Icons.Default.Widgets,
                    title = "Custom Prompts",
                    subtitle = "Manage prompt actions and ribbon",
                    isSelected = currentDestination == AppDestination.PROMPTS,
                    onClick = { onSelectDestination(AppDestination.PROMPTS) }
                )

                DrawerNavItem(
                    icon = Icons.Default.Terminal,
                    title = "Custom Commands",
                    subtitle = "Quick /commands for input fields",
                    isSelected = currentDestination == AppDestination.CUSTOM_COMMANDS,
                    onClick = { onSelectDestination(AppDestination.CUSTOM_COMMANDS) }
                )

                DrawerNavItem(
                    icon = Icons.Default.Memory,
                    title = "Model Hub",
                    subtitle = "LiteRT, GGUF models & downloads",
                    isSelected = currentDestination == AppDestination.MODEL_HUB,
                    onClick = { onSelectDestination(AppDestination.MODEL_HUB) }
                )

                DrawerNavItem(
                    icon = Icons.Default.AutoAwesome,
                    title = "AI Playground & Chat",
                    subtitle = "Interactive testbed & automation",
                    isSelected = currentDestination == AppDestination.CHAT,
                    onClick = { onSelectDestination(AppDestination.CHAT) }
                )
            }

            HorizontalDivider()

            // Footer: Settings
            DrawerNavItem(
                icon = Icons.Default.Settings,
                title = "Settings",
                subtitle = "Theme, floating button & localhost API",
                isSelected = currentDestination == AppDestination.SETTINGS,
                onClick = { onSelectDestination(AppDestination.SETTINGS) }
            )

            HorizontalDivider()

            // Footer: About TextMorph
            Surface(
                onClick = { onSelectDestination(AppDestination.ABOUT) },
                color = if (currentDestination == AppDestination.ABOUT) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = "About App",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "About TextMorph",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "v$installedVersionName",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = { onSelectDestination(AppDestination.DEV_LOGS) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.BugReport,
                            contentDescription = "Developer Diagnostics",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerNavItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontSize = 11.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
