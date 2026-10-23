package com.textmorph.app.ui.about

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ReleaseItem(
    val version: String,
    val tag: String,
    val isLatest: Boolean = false,
    val changes: List<String>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionHistoryScreen(
    currentVersionName: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler {
        onNavigateBack()
    }

    val releaseLog = listOf(
        ReleaseItem(
            version = "v2.1.1",
            tag = "Latest Hotfix",
            isLatest = true,
            changes = listOf(
                "Resolved fdsan SIGABRT Crash: Stripped ownership tags using ParcelFileDescriptor.detachFd() before passing file descriptors to librnllama.so, preventing native close() aborts.",
                "Robust Detached FD Cleanup: Safely closes unused descriptors with adoptFd() without risking descriptor leaks or double-close exceptions.",
                "Vision Projector Detached Handling: Detached temporary image projection file descriptors during multimodal inference."
            )
        ),
        ReleaseItem(
            version = "v2.0.0",
            tag = "Major Release (v2)",
            isLatest = false,
            changes = listOf(
                "Native GGUF Support via llama.cpp: Full on-device inference for quantized GGUF models alongside LiteRT-LM.",
                "Automatic Model Format Detection: Instant identification of .gguf and .litertlm models with automatic engine routing.",
                "Multimodal Vision Projector (mmproj): Attach optional mmproj-*.gguf files to GGUF models for image understanding.",
                "Zero-Copy SAF & Mmap Streaming: High-speed direct mapping from device storage without copying gigabytes to internal memory.",
                "Assistant Bar Enhancements: Action buttons remain always active and responsive, quick execute pinned next to close button, and clipboard context action.",
                "Adaptive Screen Layouts: Half-screen quick edit sheet and persistent keyboard alignment."
            )
        ),
        ReleaseItem(
            version = "v1.13.0",
            tag = "Previous Release",
            isLatest = false,
            changes = listOf(
                "Smooth Hardware-Digitizer Drag & Drop: Switched window repositioning to raw display coordinates (rawX, rawY), eliminating all jitter and coordinate drift.",
                "Tactile Long-Press Activation: 260ms hold triggers tactile haptic feedback and elevates the floating button into 120Hz smooth dragging mode.",
                "Reliable Single-Tap Toggle: Instantaneous tap disambiguation reliably toggles the radial circle menu open/closed.",
                "Persistent Position Memory: Floating button coordinates are saved to persistent storage on drop and restored upon app restart, device reboot, or service reconnection.",
                "Viewport Edge Clamping: Safe bounds checking prevents the floating button from getting clipped by screen edges or hidden beneath status and navigation bars."
            )
        ),
        ReleaseItem(
            version = "v1.12.0",
            tag = "Recent Release",
            isLatest = false,
            changes = listOf(
                "Configurable Hardware Liveliness Probe Timeout: Added user input and quick preset chips (6s, 15s, 30s, 60s) in Settings under On-Device LiteRT-LM.",
                "Extended default probe timeout to 60,000 ms on both GPU and CPU backends to prevent false timeouts on heavy local models with long time-to-first-token.",
                "Real-time timeout display in model loading logs and diagnostic export metadata."
            )
        ),
        ReleaseItem(
            version = "v1.11.0",
            tag = "Recent Release",
            isLatest = false,
            changes = listOf(
                "Redesigned Floating Toolbar in Forest Clay theme palette with warm clay terracotta & gold accents.",
                "Smooth long-press drag & drop: user can hold and move the floating button anywhere across the screen.",
                "Single-tap circle menu toggle: tapping opens or closes the radial bubble menu instead of executing direct command.",
                "Execute Command (⚡) prominently placed as the first action at the top of the circle.",
                "Cloud API Model Search: Browse button on Model ID input opens a searchable dialog with live filtering over OpenRouter/OpenAI endpoints.",
                "Clean Screen Capture: Floating toolbar and its contents are temporarily hidden during screenshot capture for multimodal AI.",
                "Dedicated Version History route separated cleanly from the About screen.",
                "Modern new application icon integrated across all mipmap densities and in-app branding.",
                "Accelerated Software Update: 64KB buffered stream downloads with multi-hop redirect handling and throttled UI emissions."
            )
        ),
        ReleaseItem(
            version = "v1.10.0",
            tag = "Previous",
            changes = listOf(
                "CPU liveliness auto-recovery & model health verification probe on Samsung Galaxy foldables.",
                "Optimized fallback memory allocation when switching between GPU and CPU backends."
            )
        ),
        ReleaseItem(
            version = "v1.5.0",
            tag = "Previous",
            changes = listOf(
                "Automated model liveliness test with direct console response feedback.",
                "Guarded floating button against unauthenticated OpenAI 401 calls.",
                "Eliminated per-event accessibility debug log flooding.",
                "Fixed SAF persistent ParcelFileDescriptor reuse across app restarts."
            )
        ),
        ReleaseItem(
            version = "v1.4.0",
            tag = "Previous",
            changes = listOf(
                "Redesigned sidebar with bottom-pinned Settings & About matching BookReader.",
                "Real-time monospace Inference Console & loading progress bar in Model Hub.",
                "Full-screen Dev Logs inspector with search, level filters, and export."
            )
        ),
        ReleaseItem(
            version = "v1.3.0",
            tag = "Previous",
            changes = listOf(
                "Native LiteRT-LM C++ JNI integration (liblitertlm_jni.so) on GPU & CPU.",
                "Interactive AI Playground with live tok/s speed metrics and photo analysis."
            )
        ),
        ReleaseItem(
            version = "v1.2.0",
            tag = "Previous",
            changes = listOf(
                "Draggable floating assistant with animated laser loading ring and stop button.",
                "5-second revert undo window and soft keyboard auto-dismissal."
            )
        ),
        ReleaseItem(
            version = "v1.1.0",
            tag = "Previous",
            changes = listOf(
                "Forest Clay Material 3 theme palette and responsive full-width dialogs.",
                "Direct GitHub Releases In-App updater with background APK installer."
            )
        ),
        ReleaseItem(
            version = "v1.0.0",
            tag = "Initial",
            changes = listOf(
                "Initial release: Zero-copy mmap model loading, 127.0.0.1:8080 Ktor server, and prompt repository."
            )
        )
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Version History",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Navigate Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(releaseLog, key = { it.version }) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (item.isLatest) {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = if (item.isLatest) 4.dp else 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (item.isLatest) {
                                    Icon(
                                        Icons.Default.NewReleases,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(
                                    text = item.version,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (item.isLatest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Surface(
                                shape = CircleShape,
                                color = if (item.isLatest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = item.tag,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = if (item.isLatest) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            thickness = 1.dp
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            item.changes.forEach { change ->
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "•",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                    Text(
                                        text = change,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        lineHeight = 20.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
