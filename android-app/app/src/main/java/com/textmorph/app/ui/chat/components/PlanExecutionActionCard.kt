package com.textmorph.app.ui.chat.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Interactive card asking user confirmation to execute a generated plan autonomously.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanExecutionActionCard(
    planSummary: String,
    onExecutePlan: () -> Unit,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1E293B), // Charcoal Slate
        border = BorderStroke(1.dp, Color(0xFF3B82F6)),
        shadowElevation = 2.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Proceed with executing this plan?",
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF8FAFC)
                )
            }

            if (planSummary.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = planSummary,
                    fontSize = 12.5.sp,
                    color = Color(0xFF94A3B8),
                    lineHeight = 17.sp
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onExecutePlan,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2563EB),
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Execute Plan", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }

                if (onDismiss != null) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFF475569)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF94A3B8))
                    ) {
                        Text("Dismiss", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
