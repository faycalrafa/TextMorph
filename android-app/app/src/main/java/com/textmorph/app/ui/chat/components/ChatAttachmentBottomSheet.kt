package com.textmorph.app.ui.chat.components

import android.Manifest
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.ui.camera.CameraCaptureActivity
import com.textmorph.app.util.AppLogger
import com.textmorph.app.util.PermissionUtils

/**
 * Responsive Charcoal Slate styled attachment bottom sheet offering
 * photo capture and file browsing options with robust permission handling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatAttachmentBottomSheet(
    onDismiss: () -> Unit,
    onPhotoCaptured: (AttachedMediaItem) -> Unit,
    onPickFiles: () -> Unit
) {
    val context = LocalContext.current
    val TAG = "ChatAttachmentBottomSheet"
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        AppLogger.i(TAG, "Camera permission result: granted=$granted")
        if (granted) {
            CameraCaptureActivity.launch(context) { bytes ->
                if (bytes != null && bytes.isNotEmpty()) {
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val item = AttachedMediaItem(
                        uri = Uri.EMPTY,
                        name = "Photo_${System.currentTimeMillis()}.png",
                        mimeType = "image/png",
                        isImage = true,
                        bitmap = bmp
                    )
                    onPhotoCaptured(item)
                    onDismiss()
                }
            }
        } else {
            Toast.makeText(context, "Camera permission is required to capture photos.", Toast.LENGTH_SHORT).show()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF111827),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = "Add Attachments",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF9FAFB)
            )
            Text(
                text = "Capture photos or attach files to augment your prompt",
                fontSize = 12.5.sp,
                color = Color(0xFF9CA3AF)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 1. Take Photo
            AttachmentActionRow(
                icon = Icons.Default.CameraAlt,
                iconTint = Color(0xFF60A5FA),
                title = "Take Photo",
                subtitle = "Capture a real-world photo using your camera",
                onClick = {
                    if (PermissionUtils.hasCameraPermission(context)) {
                        CameraCaptureActivity.launch(context) { bytes ->
                            if (bytes != null && bytes.isNotEmpty()) {
                                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                val item = AttachedMediaItem(
                                    uri = Uri.EMPTY,
                                    name = "Photo_${System.currentTimeMillis()}.png",
                                    mimeType = "image/png",
                                    isImage = true,
                                    bitmap = bmp
                                )
                                onPhotoCaptured(item)
                                onDismiss()
                            }
                        }
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 2. Attach Media / Files
            AttachmentActionRow(
                icon = Icons.Default.Image,
                iconTint = Color(0xFF34D399),
                title = "Choose Files or Images",
                subtitle = "Browse your device storage for images and text documents",
                onClick = {
                    onPickFiles()
                    onDismiss()
                }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AttachmentActionRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1F2937),
        border = BorderStroke(1.dp, Color(0xFF374151)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(14.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = iconTint.copy(alpha = 0.15f),
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier
                        .size(22.dp)
                        .padding(8.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFF9FAFB)
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = Color(0xFF9CA3AF)
                )
            }
        }
    }
}
