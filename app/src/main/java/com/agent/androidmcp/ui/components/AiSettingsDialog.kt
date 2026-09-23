package com.agent.androidmcp.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.androidmcp.ai.AiConfig
import com.agent.androidmcp.ai.AiConfigRepository
import com.agent.androidmcp.ai.AiProvider
import com.agent.androidmcp.ai.ModelFetcher
import kotlinx.coroutines.launch

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext

@Composable
fun AiSettingsDialog(
    initialConfig: AiConfig,
    onDismiss: () -> Unit,
    onSave: (AiConfig) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var provider by remember { mutableStateOf(initialConfig.provider) }
    var apiKey by remember { mutableStateOf(initialConfig.apiKey) }
    var model by remember { mutableStateOf(initialConfig.model) }
    var baseUrl by remember { mutableStateOf(initialConfig.baseUrl) }
    var maxSteps by remember { mutableStateOf(initialConfig.maxSteps.toString()) }
    var visionFallbackEnabled by remember { mutableStateOf(initialConfig.visionFallbackEnabled) }

    var availableModels by remember(provider) {
        mutableStateOf(
            AiConfigRepository.getCachedModels(context, provider) ?: provider.models
        )
    }
    var isFetchingModels by remember { mutableStateOf(false) }

    fun refreshModels() {
        if (isFetchingModels) return
        coroutineScope.launch {
            isFetchingModels = true
            val tempConfig = AiConfig(
                provider = provider,
                apiKey = apiKey,
                model = model,
                baseUrl = baseUrl
            )
            val res = ModelFetcher.fetchModels(context, tempConfig, forceRefresh = true)
            res.onSuccess { models ->
                availableModels = models
            }
            isFetchingModels = false
        }
    }

    fun onSelectProvider(newProvider: AiProvider) {
        provider = newProvider
        model = newProvider.defaultModel
        baseUrl = newProvider.defaultBaseUrl
        // Load key saved for this provider
        val prefs = context.getSharedPreferences("ai_config_prefs", android.content.Context.MODE_PRIVATE)
        apiKey = prefs.getString("ai_api_key_" + newProvider.name, "") ?: ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI Brain Configuration", fontSize = 18.sp) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Select AI Provider",
                    fontSize = 12.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Scrollable Provider Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AiProvider.values().forEach { p ->
                        FilterChip(
                            selected = provider == p,
                            onClick = { onSelectProvider(p) },
                            label = { Text(p.displayName, fontSize = 12.sp) }
                        )
                    }
                }

                // API Key (Ollama doesn't strictly need one)
                if (provider != AiProvider.OLLAMA) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("${provider.displayName} API Key") },
                        placeholder = {
                            Text(
                                when (provider) {
                                    AiProvider.GEMINI -> "AIzaSy..."
                                    AiProvider.OPENAI -> "sk-proj-..."
                                    AiProvider.ANTHROPIC -> "sk-ant-..."
                                    AiProvider.GROQ -> "gsk_..."
                                    else -> "API Key"
                                }
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp)
                    )
                }

                // Model Selection Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(
                        text = "Available Models (${provider.displayName})",
                        fontSize = 12.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isFetchingModels) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(
                            onClick = { refreshModels() },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh Models",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    availableModels.forEach { m ->
                        SuggestionChip(
                            onClick = { model = m },
                            label = {
                                Text(
                                    text = m,
                                    fontSize = 11.sp,
                                    fontWeight = if (model == m) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal
                                )
                            },
                            colors = if (model == m) {
                                SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            } else {
                                SuggestionChipDefaults.suggestionChipColors()
                            }
                        )
                    }
                }

                // Model name (editable)
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model Identifier") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp)
                )

                // Base URL
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("API Base URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp)
                )

                // Max Steps
                OutlinedTextField(
                    value = maxSteps,
                    onValueChange = { maxSteps = it },
                    label = { Text("Max Steps per Goal") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp)
                )

                // Multimodal Vision Fallback Switch
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Multimodal Vision Fallback",
                                fontSize = 13.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                            )
                            Text(
                                text = "Visually locate canvas/unlabelled buttons when accessibility fails",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = visionFallbackEnabled,
                            onCheckedChange = { visionFallbackEnabled = it }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val stepsInt = maxSteps.toIntOrNull()?.coerceIn(1, 30) ?: 10
                    onSave(
                        AiConfig(
                            provider = provider,
                            apiKey = apiKey.trim(),
                            model = model.trim(),
                            baseUrl = baseUrl.trim(),
                            maxSteps = stepsInt,
                            visionFallbackEnabled = visionFallbackEnabled
                        )
                    )
                    onDismiss()
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
