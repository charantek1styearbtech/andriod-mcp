package com.agent.androidmcp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.androidmcp.safety.SafetyConfig
import com.agent.androidmcp.safety.SafetyConfigRepository
import com.agent.androidmcp.ui.theme.AccentTeal
import com.agent.androidmcp.ui.theme.SurfaceDark
import com.agent.androidmcp.ui.theme.SurfaceLight
import com.agent.androidmcp.ui.theme.TextPrimary
import com.agent.androidmcp.ui.theme.TextSecondary

@Composable
fun SafetySettingsDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val currentConfig = remember { SafetyConfigRepository.loadConfig(context) }

    var masterEnabled by remember { mutableStateOf(currentConfig.masterSafetyEnabled) }
    var confirmSend by remember { mutableStateOf(currentConfig.confirmSendMessage) }
    var confirmDelete by remember { mutableStateOf(currentConfig.confirmDelete) }
    var confirmPay by remember { mutableStateOf(currentConfig.confirmPayments) }
    var confirmSettings by remember { mutableStateOf(currentConfig.confirmSystemSettings) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Safety Policy",
                    tint = AccentTeal,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text("Safety & Confirmation", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Configure sensitive actions that pause the autonomous agent and require your explicit confirmation.",
                    fontSize = 13.sp,
                    color = TextSecondary
                )

                // Master Switch Card
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (masterEnabled) AccentTeal.copy(alpha = 0.15f) else SurfaceLight
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Safety Interception",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = TextPrimary
                            )
                            Text(
                                text = if (masterEnabled) "Active: Prompts on high-risk actions" else "Disabled: Agent acts autonomously without prompt",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = masterEnabled,
                            onCheckedChange = { masterEnabled = it }
                        )
                    }
                }

                if (masterEnabled) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    SafetyToggleRow(
                        icon = Icons.Default.Send,
                        title = "Send Messages & Posts",
                        subtitle = "Clicks to 'Send', 'Post', 'Submit', 'Share'",
                        checked = confirmSend,
                        onCheckedChange = { confirmSend = it }
                    )

                    SafetyToggleRow(
                        icon = Icons.Default.Delete,
                        title = "Delete & Erase Content",
                        subtitle = "Clicks to 'Delete', 'Remove', 'Trash', 'Wipe'",
                        checked = confirmDelete,
                        onCheckedChange = { confirmDelete = it }
                    )

                    SafetyToggleRow(
                        icon = Icons.Default.Payment,
                        title = "Payments & Purchases",
                        subtitle = "Banking apps, UPI, 'Pay', 'Buy', 'Checkout'",
                        checked = confirmPay,
                        onCheckedChange = { confirmPay = it }
                    )

                    SafetyToggleRow(
                        icon = Icons.Default.Settings,
                        title = "Critical System Settings",
                        subtitle = "Factory reset, developer options, security",
                        checked = confirmSettings,
                        onCheckedChange = { confirmSettings = it }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val updated = SafetyConfig(
                        masterSafetyEnabled = masterEnabled,
                        confirmSendMessage = confirmSend,
                        confirmDelete = confirmDelete,
                        confirmPayments = confirmPay,
                        confirmSystemSettings = confirmSettings,
                        confirmOtherSensitive = true
                    )
                    SafetyConfigRepository.saveConfig(context, updated)
                    onDismiss()
                }
            ) {
                Text("Save Policy")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun SafetyToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
