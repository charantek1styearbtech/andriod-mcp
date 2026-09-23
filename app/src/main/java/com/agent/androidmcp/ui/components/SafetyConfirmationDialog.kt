package com.agent.androidmcp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.agent.androidmcp.safety.ConfirmationManager
import com.agent.androidmcp.safety.RiskCategory
import com.agent.androidmcp.ui.theme.AccentTeal
import com.agent.androidmcp.ui.theme.SurfaceDark
import com.agent.androidmcp.ui.theme.SurfaceLight
import com.agent.androidmcp.ui.theme.TextPrimary
import com.agent.androidmcp.ui.theme.TextSecondary

@Composable
fun SafetyConfirmationHost() {
    val activeRequest by ConfirmationManager.activeRequest.collectAsState()

    val request = activeRequest ?: return

    val riskColor = when (request.evaluation.riskCategory) {
        RiskCategory.FINANCIAL_PAYMENT -> Color(0xFFEF5350) // Red
        RiskCategory.DELETE_DATA -> Color(0xFFFFA726) // Amber/Orange
        RiskCategory.SEND_MESSAGE -> Color(0xFF42A5F5) // Blue
        RiskCategory.SYSTEM_SETTINGS -> Color(0xFFAB47BC) // Purple
        else -> Color(0xFFFFCA28) // Amber
    }

    Dialog(
        onDismissRequest = {
            ConfirmationManager.resolveConfirmation(request.id, false)
        },
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                // Header with icon and category
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(riskColor.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Security Alert",
                            tint = riskColor,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column {
                        Text(
                            text = request.evaluation.riskCategory?.title ?: "Confirmation Required",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "AI Agent Request",
                            fontSize = 12.sp,
                            color = riskColor,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Explanation & reason
                Text(
                    text = request.evaluation.reason,
                    fontSize = 14.sp,
                    color = TextSecondary,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Target Preview Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceLight)
                        .padding(14.dp)
                ) {
                    Column {
                        Text(
                            text = "Target Action",
                            fontSize = 11.sp,
                            color = TextSecondary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = request.evaluation.preview.ifBlank { request.action.toString() },
                            fontSize = 13.sp,
                            color = TextPrimary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            ConfirmationManager.resolveConfirmation(request.id, false)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFEF5350)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Block,
                            contentDescription = "Deny",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Deny", fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = {
                            ConfirmationManager.resolveConfirmation(request.id, true)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentTeal,
                            contentColor = Color.Black
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Allow",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Allow", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
