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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
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
import com.agent.androidmcp.ui.components.ServiceStatusBanner
import com.agent.androidmcp.ui.theme.*

@Composable
fun ServerScreen(
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(scrollState)
    ) {
        ServiceStatusBanner(isConnected = isConnected)

        Column(modifier = Modifier.padding(16.dp)) {
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

            Spacer(modifier = Modifier.height(16.dp))

            // AI Client Quick Setup Card
            val emailForConfig = googleAccount.email ?: "your-gmail@gmail.com"
            val claudeCodeCmd = "claude mcp add android -- https://andriod-mcp-gateway.onrender.com/sse?email=$emailForConfig"
            val cursorSseUrl = "https://andriod-mcp-gateway.onrender.com/sse?email=$emailForConfig"

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = BorderStroke(1.dp, BorderSubtle),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Connect AI Clients",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Use this endpoint in Claude Code or Cursor to control your phone via Google Account.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Claude Code setup
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Claude Code Command",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AccentTeal
                        )
                        IconButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(claudeCodeCmd))
                                Toast.makeText(context, "Copied Claude Code command", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(15.dp), tint = TextSecondary)
                        }
                    }

                    Surface(
                        color = Color.Black.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = claudeCodeCmd,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextPrimary,
                            modifier = Modifier.padding(10.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Cursor SSE URL setup
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Cursor SSE Gateway URL",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AccentTeal
                        )
                        IconButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(cursorSseUrl))
                                Toast.makeText(context, "Copied Cursor SSE URL", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(15.dp), tint = TextSecondary)
                        }
                    }

                    Surface(
                        color = Color.Black.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = cursorSseUrl,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextPrimary,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ==========================================
            // In-App Auto Update & Version Information Card
            // ==========================================
            val updateUiState by com.agent.androidmcp.update.AppUpdateManager.uiState.collectAsState()
            val currentVersionName = remember { com.agent.androidmcp.update.AppUpdateManager.getCurrentVersionName(context) }
            val currentVersionCode = remember { com.agent.androidmcp.update.AppUpdateManager.getCurrentVersionCode(context) }

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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.SystemUpdate,
                                contentDescription = null,
                                tint = AccentTeal,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "App Updates & Version",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }

                        // Version badge
                        Surface(
                            color = AccentTeal.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "v$currentVersionName ($currentVersionCode)",
                                color = AccentTeal,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Check and automatically install over-the-air APK updates directly from the gateway.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Dynamic State Rendering
                    when (val state = updateUiState) {
                        is com.agent.androidmcp.update.UpdateUiState.Idle -> {
                            Button(
                                onClick = { com.agent.androidmcp.update.AppUpdateManager.checkForUpdate(context, effectiveGatewayUrl) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentTeal),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = Color.White
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Check for Updates",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                            }
                        }

                        is com.agent.androidmcp.update.UpdateUiState.Checking -> {
                            Surface(
                                color = Color.Black.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = AccentTeal,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "Checking for new version...",
                                        fontSize = 13.sp,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }

                        is com.agent.androidmcp.update.UpdateUiState.UpToDate -> {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Surface(
                                    color = SuccessGreen.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = SuccessGreen,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "App is up to date (${state.currentVersion})",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = SuccessGreen
                                        )
                                    }
                                }

                                OutlinedButton(
                                    onClick = { com.agent.androidmcp.update.AppUpdateManager.checkForUpdate(context, effectiveGatewayUrl) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(38.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, BorderSubtle)
                                ) {
                                    Text("Check Again", fontSize = 12.sp, color = TextPrimary)
                                }
                            }
                        }

                        is com.agent.androidmcp.update.UpdateUiState.Available -> {
                            val info = state.info
                            val mbSize = if (info.apkSize > 0) String.format("%.1f MB", info.apkSize / (1024f * 1024f)) else "APK"
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(AccentTeal.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "New Update: v${info.latestVersionName}",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AccentTeal
                                    )
                                    Text(
                                        text = mbSize,
                                        fontSize = 11.sp,
                                        color = TextSecondary
                                    )
                                }

                                if (info.changelog.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = info.changelog,
                                        fontSize = 12.sp,
                                        color = TextPrimary
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Button(
                                    onClick = { com.agent.androidmcp.update.AppUpdateManager.startDownload(context, info) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(40.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentTeal),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudDownload,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = Color.White
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Download & Install Update", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            }
                        }

                        is com.agent.androidmcp.update.UpdateUiState.Downloading -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Downloading Update...",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "${state.progressPercent}%",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AccentTeal
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                LinearProgressIndicator(
                                    progress = { state.progressPercent / 100f },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp),
                                    color = AccentTeal,
                                    trackColor = BorderSubtle
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                val downloadedMb = String.format("%.1f", state.downloadedBytes / (1024f * 1024f))
                                val totalMb = String.format("%.1f MB", state.totalBytes / (1024f * 1024f))
                                Text(
                                    text = "$downloadedMb MB / $totalMb",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }
                        }

                        is com.agent.androidmcp.update.UpdateUiState.ReadyToInstall -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(SuccessGreen.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = "Update downloaded successfully!",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SuccessGreen
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Tap below if the package installer prompt did not appear automatically.",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = { com.agent.androidmcp.update.AppUpdateManager.installApk(context, state.apkFile) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(38.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Open Installer", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            }
                        }

                        is com.agent.androidmcp.update.UpdateUiState.Error -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(ErrorRed.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = "Update Check Failed",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ErrorRed
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = state.message,
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = { com.agent.androidmcp.update.AppUpdateManager.checkForUpdate(context, effectiveGatewayUrl) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(36.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, BorderSubtle)
                                ) {
                                    Text("Retry Check", fontSize = 12.sp, color = TextPrimary)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
