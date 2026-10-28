package com.textmorph.app.ui.wakeword.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.model.WakeWordEntity

/**
 * Responsive card displaying a wake-word trigger, its phonetic tokens,
 * calibration verification status, test action, and active state toggle.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WakeWordItemCard(
    wakeWord: WakeWordEntity,
    onToggleActive: () -> Unit,
    onCalibrationRequired: () -> Unit,
    onTestCalibrate: () -> Unit,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isEnabled = wakeWord.isEnabled
    val isCalibrated = wakeWord.isCalibrated

    Surface(
        color = Color(0xFF1F2937),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            1.dp,
            if (isEnabled) Color(0xFF3B82F6).copy(alpha = 0.6f) else Color(0xFF374151)
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Header Row: Title, Badges, and Active Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = wakeWord.displayName,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        if (wakeWord.isBuiltIn) {
                            Surface(
                                color = Color(0xFF374151),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "Built-in",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFFD1D5DB),
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Alias: ${wakeWord.aliasTag}",
                        fontSize = 11.5.sp,
                        color = Color(0xFF9CA3AF)
                    )
                }

                Switch(
                    checked = isEnabled,
                    onCheckedChange = {
                        if (!isCalibrated) {
                            onCalibrationRequired()
                        } else {
                            onToggleActive()
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF2563EB),
                        uncheckedThumbColor = Color(0xFF9CA3AF),
                        uncheckedTrackColor = Color(0xFF111827)
                    )
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Phonetic Representation
            Surface(
                color = Color(0xFF111827),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = wakeWord.phonemes,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFF93C5FD),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Status Badge and Action Buttons (Anti-squish FlowRow)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Calibration Status Chip
                Surface(
                    color = if (isCalibrated) Color(0xFF065F46).copy(alpha = 0.4f) else Color(0xFF78350F).copy(alpha = 0.4f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, if (isCalibrated) Color(0xFF059669) else Color(0xFFD97706))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isCalibrated) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = if (isCalibrated) Color(0xFF34D399) else Color(0xFFFBBF24),
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isCalibrated) "Verified (3/3 Tests)" else "Calibration Required (0/3)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isCalibrated) Color(0xFF34D399) else Color(0xFFFBBF24)
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Test / Calibrate Button
                OutlinedButton(
                    onClick = onTestCalibrate,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFF3B82F6)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFF1D4ED8).copy(alpha = 0.15f)
                    ),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Hearing,
                        contentDescription = null,
                        tint = Color(0xFF60A5FA),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isCalibrated) "Re-test Word" else "Test & Calibrate (3x)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF93C5FD)
                    )
                }

                if (!wakeWord.isBuiltIn && onDelete != null) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
