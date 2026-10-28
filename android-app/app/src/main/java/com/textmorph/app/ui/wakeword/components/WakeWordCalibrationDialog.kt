package com.textmorph.app.ui.wakeword.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.textmorph.app.data.model.WakeWordEntity
import com.textmorph.app.voice.WakeWordCalibrationTester

/**
 * Interactive 3-attempt acoustic calibration testing dialog.
 * Requires the user to test the wake-word 3 times in a live microphone session.
 * If all 3 attempts pass, marks the wake-word as certified/calibrated and auto-enables
 * it if fewer than 2 triggers are currently active.
 */
@Composable
fun WakeWordCalibrationDialog(
    targetWakeWord: WakeWordEntity,
    onCalibrationSuccess: (autoEnabled: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var attemptsPassed by remember { mutableIntStateOf(0) }
    var isCompleted by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var wasAutoEnabled by remember { mutableStateOf(false) }

    val tester = remember(targetWakeWord.id) {
        WakeWordCalibrationTester(
            context = context,
            targetEntity = targetWakeWord,
            onAttemptPassed = { passed ->
                attemptsPassed = passed
                if (passed >= 3) {
                    isCompleted = true
                }
            },
            onCalibrationFinished = {
                isCompleted = true
            },
            onError = { msg ->
                errorMessage = msg
            }
        )
    }

    DisposableEffect(tester) {
        tester.start()
        onDispose {
            tester.release()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF111827),
            border = BorderStroke(1.dp, Color(0xFF374151)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Hearing,
                        contentDescription = null,
                        tint = Color(0xFF3B82F6),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Acoustic Calibration",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color(0xFF9CA3AF)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Target Phrase Card
                Surface(
                    color = Color(0xFF1F2937),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFF374151)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Say clearly into your microphone:",
                            fontSize = 12.sp,
                            color = Color(0xFF9CA3AF)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "\"${targetWakeWord.displayName}\"",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF60A5FA)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = targetWakeWord.phonemes,
                            fontSize = 11.sp,
                            color = Color(0xFF93C5FD)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // 3-Attempt Progress Indicators
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CalibrationAttemptRow(
                        attemptNumber = 1,
                        isPassed = attemptsPassed >= 1,
                        isCurrent = attemptsPassed == 0 && !isCompleted
                    )
                    CalibrationAttemptRow(
                        attemptNumber = 2,
                        isPassed = attemptsPassed >= 2,
                        isCurrent = attemptsPassed == 1 && !isCompleted
                    )
                    CalibrationAttemptRow(
                        attemptNumber = 3,
                        isPassed = attemptsPassed >= 3,
                        isCurrent = attemptsPassed == 2 && !isCompleted
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Live Acoustic Status
                if (errorMessage != null) {
                    Surface(
                        color = Color(0xFF7F1D1D).copy(alpha = 0.4f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFFDC2626)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = errorMessage ?: "Acoustic error",
                            fontSize = 12.sp,
                            color = Color(0xFFFCA5A5),
                            modifier = Modifier.padding(10.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                } else if (isCompleted) {
                    Surface(
                        color = Color(0xFF065F46).copy(alpha = 0.4f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFF059669)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "🎉 Calibration Verified! (3/3 Passed)",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF34D399)
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "Trigger '${targetWakeWord.displayName}' is now certified for offline detection.",
                                fontSize = 11.5.sp,
                                color = Color(0xFFA7F3D0),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = Color(0xFF3B82F6),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Listening for attempt ${attemptsPassed + 1} of 3...",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF9CA3AF)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            attemptsPassed = 0
                            isCompleted = false
                            errorMessage = null
                            tester.resetAttempts()
                        },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFF374151)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Reset", fontSize = 13.sp, color = Color(0xFFE5E7EB))
                    }

                    Button(
                        onClick = {
                            if (isCompleted) {
                                onCalibrationSuccess(wasAutoEnabled)
                            }
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isCompleted) Color(0xFF059669) else Color(0xFF2563EB)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isCompleted) "Done" else "Cancel", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun CalibrationAttemptRow(
    attemptNumber: Int,
    isPassed: Boolean,
    isCurrent: Boolean
) {
    Surface(
        color = when {
            isPassed -> Color(0xFF065F46).copy(alpha = 0.35f)
            isCurrent -> Color(0xFF1D4ED8).copy(alpha = 0.25f)
            else -> Color(0xFF1F2937)
        },
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            1.dp,
            when {
                isPassed -> Color(0xFF059669)
                isCurrent -> Color(0xFF3B82F6)
                else -> Color(0xFF374151)
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(
                        when {
                            isPassed -> Color(0xFF059669)
                            isCurrent -> Color(0xFF2563EB)
                            else -> Color(0xFF374151)
                        },
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isPassed) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Text(
                        text = "$attemptNumber",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Attempt $attemptNumber of 3",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isPassed) Color(0xFF34D399) else Color.White
                )
                Text(
                    text = when {
                        isPassed -> "Verified acoustic detection"
                        isCurrent -> "Speak phrase clearly now"
                        else -> "Pending"
                    },
                    fontSize = 11.sp,
                    color = if (isPassed) Color(0xFFA7F3D0) else Color(0xFF9CA3AF)
                )
            }
        }
    }
}
