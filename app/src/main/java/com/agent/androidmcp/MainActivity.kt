package com.agent.androidmcp

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.androidmcp.accessibility.AccessibilityState
import com.agent.androidmcp.accessibility.AgentAccessibilityService
import com.agent.androidmcp.ui.screens.ChatScreen
import com.agent.androidmcp.ui.screens.InspectorScreen
import com.agent.androidmcp.ui.screens.ServerScreen
import com.agent.androidmcp.ui.theme.AccentTeal
import com.agent.androidmcp.ui.theme.AndroidAgentTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.agent.androidmcp.ui.theme.*

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Automatically start embedded server for MCP & REST access
        com.agent.androidmcp.server.AndroidServer.getInstance(this, 8080).start()

        // Initialize Remote Gateway Config from preferences
        val gatewayConfig = com.agent.androidmcp.server.remote.RemoteGatewayConfigRepository.loadConfig(this)
        com.agent.androidmcp.server.remote.RemoteGatewayState.updateConfig(
            gatewayConfig.serverUrl,
            gatewayConfig.deviceId,
            gatewayConfig.token
        )

        // Initialize Google Account identity
        com.agent.androidmcp.auth.GoogleAuthManager.init(this)

        // Automatically connect to Remote Fleet Gateway
        if (gatewayConfig.serverUrl.isNotBlank() && gatewayConfig.deviceId.isNotBlank()) {
            com.agent.androidmcp.server.remote.RemoteGatewayClient.start(
                context = this,
                url = gatewayConfig.serverUrl,
                deviceId = gatewayConfig.deviceId,
                token = gatewayConfig.token
            )
        }

        setContent {
            AndroidAgentTheme {
                MainAppScaffold()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Check if service is enabled and trigger screen capture
        val service = AgentAccessibilityService.getInstance()
        if (service != null) {
            AccessibilityState.setConnected(true)
            service.refreshScreenState()
        } else {
            val enabledInSettings = AccessibilityState.isServiceEnabledInSettings(this)
            AccessibilityState.setConnected(enabledInSettings)
        }

        // Request VPN preparation if not already approved
        val vpnIntent = android.net.VpnService.prepare(this)
        if (vpnIntent != null) {
            try {
                startActivityForResult(vpnIntent, 1002)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to launch VPN prepare intent", e)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScaffold() {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var showSafetySettings by remember { mutableStateOf(false) }
    val isConnected by AccessibilityState.isServiceConnected.collectAsState()

    if (showSafetySettings) {
        com.agent.androidmcp.ui.components.SafetySettingsDialog(
            onDismiss = { showSafetySettings = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Android Agent",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.2).sp,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isConnected) SuccessGreen.copy(alpha = 0.12f) else WarningAmber.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, if (isConnected) SuccessGreen.copy(alpha = 0.25f) else WarningAmber.copy(alpha = 0.25f))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(if (isConnected) SuccessGreen else WarningAmber, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = if (isConnected) "Active" else "Inactive",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isConnected) SuccessGreen else WarningAmber
                                )
                            }
                        }
                    }
                },
                actions = {
                    val context = androidx.compose.ui.platform.LocalContext.current

                    IconButton(onClick = { showSafetySettings = true }) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Safety Policy",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Accessibility Settings",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = SurfaceDark,
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    icon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Chat,
                            contentDescription = "Chat",
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    label = { Text("Agent Chat", fontSize = 11.sp, fontWeight = if (selectedTabIndex == 0) FontWeight.SemiBold else FontWeight.Normal) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AccentTeal,
                        indicatorColor = AccentTeal.copy(alpha = 0.15f),
                        selectedTextColor = AccentTeal,
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted
                    )
                )

                NavigationBarItem(
                    selected = selectedTabIndex == 1,
                    onClick = {
                        selectedTabIndex = 1
                        AgentAccessibilityService.getInstance()?.refreshScreenState()
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.DeveloperMode,
                            contentDescription = "Inspector",
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    label = { Text("Inspector", fontSize = 11.sp, fontWeight = if (selectedTabIndex == 1) FontWeight.SemiBold else FontWeight.Normal) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AccentTeal,
                        indicatorColor = AccentTeal.copy(alpha = 0.15f),
                        selectedTextColor = AccentTeal,
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted
                    )
                )

                NavigationBarItem(
                    selected = selectedTabIndex == 2,
                    onClick = { selectedTabIndex = 2 },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Dns,
                            contentDescription = "Server",
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    label = { Text("Server & MCP", fontSize = 11.sp, fontWeight = if (selectedTabIndex == 2) FontWeight.SemiBold else FontWeight.Normal) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AccentTeal,
                        indicatorColor = AccentTeal.copy(alpha = 0.15f),
                        selectedTextColor = AccentTeal,
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted
                    )
                )
            }
        },
        containerColor = DarkBackground
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(DarkBackground)
        ) {
            when (selectedTabIndex) {
                0 -> ChatScreen(
                    isConnected = isConnected,
                    onNavigateToInspector = {
                        selectedTabIndex = 1
                        AgentAccessibilityService.getInstance()?.refreshScreenState()
                    }
                )
                1 -> InspectorScreen(isConnected = isConnected)
                2 -> ServerScreen(isConnected = isConnected)
            }

            // Global safety confirmation dialog
            com.agent.androidmcp.ui.components.SafetyConfirmationHost()
        }
    }
}
