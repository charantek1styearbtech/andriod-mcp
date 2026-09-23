package com.agent.androidmcp.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.androidmcp.server.AndroidServer
import com.agent.androidmcp.server.ServerState
import com.agent.androidmcp.ui.components.ServiceStatusBanner
import com.agent.androidmcp.ui.theme.*

@Composable
fun ServerScreen(
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val serverStatus by ServerState.status.collectAsState()
    val scrollState = rememberScrollState()

    var portInput by remember { mutableStateOf("8080") }

    val claudeConfigJson = remember(serverStatus.ipAddress, serverStatus.port) {
        """
{
  "mcpServers": {
    "android": {
      "command": "npx",
      "args": [
        "-y",
        "mcp-remote-client",
        "--url",
        "http://${serverStatus.ipAddress}:${serverStatus.port}/mcp"
      ]
    }
  }
}
""".trimIndent()
    }

    val adbCommand = remember(serverStatus.port) {
        "adb forward tcp:${serverStatus.port} tcp:${serverStatus.port}"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(scrollState)
    ) {
        ServiceStatusBanner(isConnected = isConnected)

        Column(modifier = Modifier.padding(16.dp)) {
            // Header card with Start/Stop
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = BorderStroke(1.dp, BorderSubtle),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = "Embedded Server & MCP",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (serverStatus.isRunning) "Running on http://${serverStatus.ipAddress}:${serverStatus.port}" else "Server Stopped",
                                fontSize = 12.sp,
                                color = if (serverStatus.isRunning) SuccessGreen else TextSecondary
                            )
                        }

                        Button(
                            onClick = {
                                val server = AndroidServer.getInstance(context, portInput.toIntOrNull() ?: 8080)
                                if (serverStatus.isRunning) {
                                    server.stop()
                                    Toast.makeText(context, "Server stopped", Toast.LENGTH_SHORT).show()
                                } else {
                                    server.start()
                                    Toast.makeText(context, "Server started on port ${serverStatus.port}", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (serverStatus.isRunning) ErrorRed else AccentTeal
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = if (serverStatus.isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (serverStatus.isRunning) "Stop" else "Start", maxLines = 1)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = SurfaceLight)
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Active WS Clients", fontSize = 11.sp, color = TextSecondary)
                            Text("${serverStatus.activeConnections}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        }
                        Column {
                            Text("Total Requests", fontSize = 11.sp, color = TextSecondary)
                            Text("${serverStatus.totalRequests}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AccentTeal)
                        }
                        Column {
                            Text("Port", fontSize = 11.sp, color = TextSecondary)
                            Text("${serverStatus.port}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Status: ${serverStatus.lastLog}",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // MCP Protocol Info Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = BorderStroke(1.dp, BorderSubtle),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Model Context Protocol (MCP)",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Endpoint: http://${serverStatus.ipAddress}:${serverStatus.port}/mcp\nExposes tools: android_get_screen, android_take_screenshot, android_execute_action, android_run_goal.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Claude Desktop Config",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AccentTeal
                        )
                        IconButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(claudeConfigJson))
                                Toast.makeText(context, "Copied Claude Desktop Config", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp), tint = TextSecondary)
                        }
                    }

                    Surface(
                        color = Color.Black.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = claudeConfigJson,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextPrimary,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Remote Fleet Gateway Card
            val gatewayStatus by com.agent.androidmcp.server.remote.RemoteGatewayState.status.collectAsState()
            var gatewayUrlInput by remember { mutableStateOf(gatewayStatus.serverUrl) }
            var gatewayDeviceIdInput by remember { mutableStateOf(gatewayStatus.deviceId) }
            var gatewayTokenInput by remember { mutableStateOf(gatewayStatus.token) }
            var showCustomServer by remember { mutableStateOf(false) }

            val effectiveGatewayUrl = if (showCustomServer && gatewayUrlInput.isNotBlank()) {
                gatewayUrlInput.trim()
            } else {
                com.agent.androidmcp.server.remote.RemoteGatewayConfigRepository.DEFAULT_URL
            }

            val googleAccount by com.agent.androidmcp.auth.GoogleAuthManager.accountState.collectAsState()
            var showManualEmailDialog by remember { mutableStateOf(false) }
            var manualEmailText by remember { mutableStateOf("") }

            val accountPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
                    val accountName = result.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)
                    if (!accountName.isNullOrBlank()) {
                        com.agent.androidmcp.auth.GoogleAuthManager.saveAccount(context, accountName)
                        Toast.makeText(context, "Linked: $accountName", Toast.LENGTH_SHORT).show()
                        if (gatewayStatus.isConnected) {
                            com.agent.androidmcp.server.remote.RemoteGatewayClient.start(
                                context = context,
                                url = effectiveGatewayUrl,
                                deviceId = gatewayDeviceIdInput,
                                token = gatewayTokenInput
                            )
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = BorderStroke(1.dp, BorderSubtle),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = "Remote Fleet Gateway",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = when {
                                    gatewayStatus.isConnected -> "Ready for remote MCP commands"
                                    gatewayStatus.isConnecting -> "Connecting to backend..."
                                    gatewayStatus.lastError != null -> "Error: ${gatewayStatus.lastError}"
                                    else -> "Outbound persistent WebSocket"
                                },
                                fontSize = 12.sp,
                                color = when {
                                    gatewayStatus.isConnected -> SuccessGreen
                                    gatewayStatus.isConnecting -> WarningAmber
                                    gatewayStatus.lastError != null -> ErrorRed
                                    else -> TextSecondary
                                }
                            )
                        }

                        // Status badge pill
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = when {
                                gatewayStatus.isConnected -> SuccessGreen.copy(alpha = 0.12f)
                                gatewayStatus.isConnecting -> WarningAmber.copy(alpha = 0.12f)
                                gatewayStatus.lastError != null -> ErrorRed.copy(alpha = 0.12f)
                                else -> SurfaceLight
                            },
                            border = BorderStroke(
                                1.dp,
                                when {
                                    gatewayStatus.isConnected -> SuccessGreen.copy(alpha = 0.3f)
                                    gatewayStatus.isConnecting -> WarningAmber.copy(alpha = 0.3f)
                                    gatewayStatus.lastError != null -> ErrorRed.copy(alpha = 0.3f)
                                    else -> BorderSubtle
                                }
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(
                                            when {
                                                gatewayStatus.isConnected -> SuccessGreen
                                                gatewayStatus.isConnecting -> WarningAmber
                                                gatewayStatus.lastError != null -> ErrorRed
                                                else -> TextSecondary
                                            },
                                            CircleShape
                                        )
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = when {
                                        gatewayStatus.isConnected -> "Connected"
                                        gatewayStatus.isConnecting -> "Connecting"
                                        gatewayStatus.lastError != null -> "Error"
                                        else -> "Disconnected"
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = when {
                                        gatewayStatus.isConnected -> SuccessGreen
                                        gatewayStatus.isConnecting -> WarningAmber
                                        gatewayStatus.lastError != null -> ErrorRed
                                        else -> TextSecondary
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Google Identity Banner
                    Surface(
                        color = Color.Black.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            if (googleAccount.isLoggedIn && !googleAccount.email.isNullOrBlank()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Google Account Linked", fontSize = 11.sp, color = SuccessGreen)
                                        Text(
                                            text = googleAccount.email ?: "",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary,
                                            maxLines = 1
                                        )
                                    }
                                    Row {
                                        TextButton(
                                            onClick = {
                                                try {
                                                    accountPickerLauncher.launch(com.agent.androidmcp.auth.GoogleAuthManager.createChooseAccountIntent())
                                                } catch (e: Exception) {
                                                    showManualEmailDialog = true
                                                }
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text("Switch", fontSize = 12.sp, color = AccentTeal)
                                        }
                                        TextButton(
                                            onClick = {
                                                com.agent.androidmcp.auth.GoogleAuthManager.signOut(context)
                                                if (gatewayStatus.isConnected) {
                                                    com.agent.androidmcp.server.remote.RemoteGatewayClient.start(
                                                        context = context,
                                                        url = effectiveGatewayUrl,
                                                        deviceId = gatewayDeviceIdInput,
                                                        token = gatewayTokenInput
                                                    )
                                                }
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text("Sign Out", fontSize = 12.sp, color = ErrorRed)
                                        }
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Google Account", fontSize = 11.sp, color = TextSecondary)
                                        Text("No Gmail account linked", fontSize = 12.sp, color = TextSecondary)
                                    }
                                    Button(
                                        onClick = {
                                            try {
                                                accountPickerLauncher.launch(com.agent.androidmcp.auth.GoogleAuthManager.createChooseAccountIntent())
                                            } catch (e: Exception) {
                                                showManualEmailDialog = true
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceLight),
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text("Sign In with Google", fontSize = 12.sp, color = TextPrimary)
                                    }
                                }
                            }
                        }
                    }

                    if (showManualEmailDialog) {
                        AlertDialog(
                            onDismissRequest = { showManualEmailDialog = false },
                            title = { Text("Link Google / Gmail Account", color = TextPrimary, fontSize = 16.sp) },
                            text = {
                                OutlinedTextField(
                                    value = manualEmailText,
                                    onValueChange = { manualEmailText = it },
                                    label = { Text("Enter Gmail address") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        if (manualEmailText.contains("@")) {
                                            com.agent.androidmcp.auth.GoogleAuthManager.saveAccount(context, manualEmailText)
                                            showManualEmailDialog = false
                                            if (gatewayStatus.isConnected) {
                                                com.agent.androidmcp.server.remote.RemoteGatewayClient.start(
                                                    context = context,
                                                    url = effectiveGatewayUrl,
                                                    deviceId = gatewayDeviceIdInput,
                                                    token = gatewayTokenInput
                                                )
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentTeal)
                                ) {
                                    Text("Save")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showManualEmailDialog = false }) {
                                    Text("Cancel", color = TextSecondary)
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Cloud Gateway Badge Card (Hardcoded Production URL)
                    Surface(
                        color = Color.Black.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, BorderSubtle),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f).padding(end = 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Dns,
                                    contentDescription = null,
                                    tint = AccentTeal,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Cloud Backend",
                                        fontSize = 11.sp,
                                        color = TextSecondary
                                    )
                                    Text(
                                        text = "andriod-mcp-gateway.onrender.com",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary
                                    )
                                }
                            }
                            TextButton(
                                onClick = { showCustomServer = !showCustomServer },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (showCustomServer) "Default" else "Custom",
                                    fontSize = 11.sp,
                                    color = if (showCustomServer) AccentTeal else TextSecondary
                                )
                            }
                        }
                    }

                    if (showCustomServer) {
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = gatewayUrlInput,
                            onValueChange = { gatewayUrlInput = it },
                            label = { Text("Custom Gateway WebSocket URL", color = TextSecondary, fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentTeal,
                                unfocusedBorderColor = BorderSubtle,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            enabled = !gatewayStatus.isConnected && !gatewayStatus.isConnecting
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = gatewayDeviceIdInput,
                            onValueChange = { gatewayDeviceIdInput = it },
                            label = { Text("Device ID", color = TextSecondary, fontSize = 12.sp) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentTeal,
                                unfocusedBorderColor = BorderSubtle,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            enabled = !gatewayStatus.isConnected && !gatewayStatus.isConnecting
                        )

                        OutlinedTextField(
                            value = gatewayTokenInput,
                            onValueChange = { gatewayTokenInput = it },
                            label = { Text("Device Token", color = TextSecondary, fontSize = 12.sp) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentTeal,
                                unfocusedBorderColor = BorderSubtle,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            enabled = !gatewayStatus.isConnected && !gatewayStatus.isConnecting
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Dedicated Connect / Disconnect Action Button
                    Button(
                        onClick = {
                            if (gatewayStatus.isConnected || gatewayStatus.isConnecting) {
                                com.agent.androidmcp.server.remote.RemoteGatewayClient.stop()
                            } else {
                                com.agent.androidmcp.server.remote.RemoteGatewayClient.start(
                                    context = context,
                                    url = effectiveGatewayUrl,
                                    deviceId = gatewayDeviceIdInput,
                                    token = gatewayTokenInput
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = when {
                                gatewayStatus.isConnected -> ErrorRed
                                gatewayStatus.isConnecting -> WarningAmber
                                else -> AccentTeal
                            }
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (gatewayStatus.isConnecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Connecting to Fleet...",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        } else if (gatewayStatus.isConnected) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Disconnect Fleet Gateway",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Connect Fleet Gateway",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = BorderSubtle)
                    Spacer(modifier = Modifier.height(12.dp))

                    // Gateway status metrics
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Activity", fontSize = 11.sp, color = TextSecondary)
                            Text(gatewayStatus.lastActivity, fontSize = 12.sp, color = TextPrimary, maxLines = 1)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Actions Run", fontSize = 11.sp, color = TextSecondary)
                            Text("${gatewayStatus.actionsExecuted}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AccentTeal)
                        }
                    }
                }
            }
        }
    }
}
