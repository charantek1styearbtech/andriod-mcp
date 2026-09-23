package com.agent.androidmcp.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
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
import com.agent.androidmcp.accessibility.AccessibilityState
import com.agent.androidmcp.accessibility.AgentAccessibilityService
import com.agent.androidmcp.accessibility.GestureHelper
import com.agent.androidmcp.action.ActionValidator
import com.agent.androidmcp.action.ScrollDirection
import com.agent.androidmcp.model.ScreenState
import com.agent.androidmcp.model.UiElement
import com.agent.androidmcp.ui.components.ServiceStatusBanner
import com.agent.androidmcp.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun InspectorScreen(
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val currentScreen by AccessibilityState.currentScreen.collectAsState()
    val statusMessage by AccessibilityState.statusMessage.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var showJsonDialog by remember { mutableStateOf(false) }

    val filteredElements = remember(currentScreen, searchQuery) {
        if (searchQuery.isBlank()) {
            currentScreen.elements
        } else {
            currentScreen.elements.filter { el ->
                el.text.contains(searchQuery, ignoreCase = true) ||
                        el.contentDescription.contains(searchQuery, ignoreCase = true) ||
                        el.id.contains(searchQuery, ignoreCase = true) ||
                        el.simplifiedType.contains(searchQuery, ignoreCase = true) ||
                        (el.viewIdResourceName?.contains(searchQuery, ignoreCase = true) == true)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        ServiceStatusBanner(isConnected = isConnected)

        // Screen metadata card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            border = BorderStroke(1.dp, BorderSubtle),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Target Package",
                            fontSize = 11.sp,
                            color = TextSecondary,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = currentScreen.packageName.ifEmpty { "No active app detected" },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentTeal
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilledTonalButton(
                            onClick = { showJsonDialog = true },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("JSON", fontSize = 11.sp)
                        }

                        FilledTonalButton(
                            onClick = {
                                val service = AgentAccessibilityService.getInstance()
                                if (service != null) {
                                    service.refreshScreenState()
                                    Toast.makeText(context, "Refreshed screen tree", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Service not connected", Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Refresh", fontSize = 11.sp)
                        }
                    }
                }

                if (currentScreen.windowTitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Window: ${currentScreen.windowTitle}",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Elements: ${currentScreen.elementCount}",
                        fontSize = 12.sp,
                        color = TextPrimary
                    )
                    Text(
                        text = "Interactive: ${currentScreen.interactiveCount}",
                        fontSize = 12.sp,
                        color = SuccessGreen
                    )
                }

                if (statusMessage.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Log: $statusMessage",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Gesture quick controls row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    coroutineScope.launch {
                        val service = AgentAccessibilityService.getInstance()
                        if (service != null) {
                            GestureHelper.scroll(service, ScrollDirection.DOWN)
                            Toast.makeText(context, "Scrolled Down", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceLight),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            ) {
                Text("Scroll Down", fontSize = 11.sp)
            }

            Button(
                onClick = {
                    coroutineScope.launch {
                        val service = AgentAccessibilityService.getInstance()
                        if (service != null) {
                            GestureHelper.scroll(service, ScrollDirection.UP)
                            Toast.makeText(context, "Scrolled Up", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceLight),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            ) {
                Text("Scroll Up", fontSize = 11.sp)
            }

            IconButton(
                onClick = { AgentAccessibilityService.getInstance()?.pressBack() },
                modifier = Modifier
                    .size(34.dp)
                    .background(SurfaceDark, RoundedCornerShape(8.dp))
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = TextPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }

            IconButton(
                onClick = { AgentAccessibilityService.getInstance()?.pressHome() },
                modifier = Modifier
                    .size(34.dp)
                    .background(SurfaceDark, RoundedCornerShape(8.dp))
            ) {
                Icon(
                    imageVector = Icons.Default.Home,
                    contentDescription = "Home",
                    tint = TextPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Search Filter Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Filter elements by text, ID, class...", fontSize = 13.sp) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(10.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = SurfaceDark,
                    unfocusedContainerColor = SurfaceDark,
                    focusedBorderColor = AccentTeal,
                    unfocusedBorderColor = SurfaceLight
                )
            )
        }

        // List of extracted UI elements
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (filteredElements.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isConnected) "No elements matching filter" else "Enable Accessibility Service to inspect screen",
                            color = TextSecondary,
                            fontSize = 14.sp
                        )
                    }
                }
            } else {
                items(filteredElements, key = { it.id }) { element ->
                    UiElementCard(
                        element = element,
                        onNodeClick = {
                            val service = AgentAccessibilityService.getInstance()
                            if (service != null) {
                                val ok = service.clickElement(element)
                                Toast.makeText(
                                    context,
                                    if (ok) "Node Click: '${element.displayLabel}'" else "Node Click failed",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onGestureClick = {
                            coroutineScope.launch {
                                val service = AgentAccessibilityService.getInstance()
                                if (service != null) {
                                    val ok = GestureHelper.clickAt(
                                        service,
                                        element.bounds.centerX.toFloat(),
                                        element.bounds.centerY.toFloat()
                                    )
                                    Toast.makeText(
                                        context,
                                        if (ok) "Gesture Tap: (${element.bounds.centerX}, ${element.bounds.centerY})" else "Gesture tap failed",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    // JSON Dialog
    if (showJsonDialog) {
        val screenJson = remember(currentScreen) {
            ActionValidator.jsonParser.encodeToString(ScreenState.serializer(), currentScreen)
        }
        AlertDialog(
            onDismissRequest = { showJsonDialog = false },
            title = { Text("Normalized UI Tree JSON", fontSize = 16.sp) },
            text = {
                OutlinedTextField(
                    value = screenJson,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(screenJson))
                        Toast.makeText(context, "Copied JSON to clipboard", Toast.LENGTH_SHORT).show()
                        showJsonDialog = false
                    }
                ) {
                    Text("Copy")
                }
            },
            dismissButton = {
                TextButton(onClick = { showJsonDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
fun UiElementCard(
    element: UiElement,
    onNodeClick: () -> Unit,
    onGestureClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, BorderSubtle)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        color = AccentTeal.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = element.id,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentTeal,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Surface(
                        color = SurfaceLight,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = element.simplifiedType.uppercase(),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextSecondary
                        )
                    }

                    if (element.isClickable) {
                        Surface(
                            color = SuccessGreen.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "CLICKABLE",
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = SuccessGreen
                            )
                        }
                    }

                    if (element.isEditable) {
                        Surface(
                            color = Purple80.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "EDITABLE",
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Purple80
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (element.isClickable) {
                        FilledTonalButton(
                            onClick = onNodeClick,
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("Node", fontSize = 10.sp)
                        }
                    }
                    FilledTonalButton(
                        onClick = onGestureClick,
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("Tap", fontSize = 10.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = element.displayLabel,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )

            if (!element.viewIdResourceName.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "ID: ${element.viewIdResourceName}",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Bounds: ${element.bounds}",
                    fontSize = 10.sp,
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Center: (${element.bounds.centerX}, ${element.bounds.centerY})",
                    fontSize = 10.sp,
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
