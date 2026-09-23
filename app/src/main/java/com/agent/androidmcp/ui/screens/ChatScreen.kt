package com.agent.androidmcp.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.androidmcp.accessibility.AgentAccessibilityService
import com.agent.androidmcp.action.*
import com.agent.androidmcp.agent.AgentProgressEvent
import com.agent.androidmcp.agent.AutonomousAgent
import com.agent.androidmcp.ai.AiConfig
import com.agent.androidmcp.ai.AiConfigRepository
import com.agent.androidmcp.ai.AiProvider
import com.agent.androidmcp.ai.ModelFetcher
import com.agent.androidmcp.model.ChatMessage
import com.agent.androidmcp.model.MessageSender
import com.agent.androidmcp.ui.components.AiSettingsDialog
import com.agent.androidmcp.ui.components.ServiceStatusBanner
import com.agent.androidmcp.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    isConnected: Boolean,
    onNavigateToInspector: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var aiConfig by remember { mutableStateOf(AiConfigRepository.loadConfig(context)) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showSafetyDialog by remember { mutableStateOf(false) }

    var providerMenuExpanded by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var showQuickKeyDialog by remember { mutableStateOf(false) }
    var quickKeyInput by remember { mutableStateOf("") }
    var showCustomModelDialog by remember { mutableStateOf(false) }
    var customModelInput by remember { mutableStateOf("") }

    var availableModels by remember(aiConfig.provider) {
        mutableStateOf(
            AiConfigRepository.getCachedModels(context, aiConfig.provider) ?: aiConfig.provider.models
        )
    }
    var isFetchingModels by remember { mutableStateOf(false) }

    fun refreshModels(force: Boolean = true) {
        if (isFetchingModels) return
        coroutineScope.launch {
            isFetchingModels = true
            val result = ModelFetcher.fetchModels(context, aiConfig, forceRefresh = force)
            result.onSuccess { models ->
                availableModels = models
                if (force) {
                    Toast.makeText(context, "Loaded ${models.size} models from ${aiConfig.provider.displayName}", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { err ->
                if (force) {
                    Toast.makeText(context, "Fetch failed: ${err.message}", Toast.LENGTH_SHORT).show()
                }
            }
            isFetchingModels = false
        }
    }

    LaunchedEffect(aiConfig.provider, aiConfig.apiKey) {
        val cached = AiConfigRepository.getCachedModels(context, aiConfig.provider)
        if (cached != null) {
            availableModels = cached
        } else if (aiConfig.isConfigured || aiConfig.provider == AiProvider.OLLAMA) {
            refreshModels(force = false)
        } else {
            availableModels = aiConfig.provider.models
        }
    }

    val autonomousAgent = remember(aiConfig) {
        AutonomousAgent(context, aiConfig)
    }

    val isAgentRunning by autonomousAgent.isRunning.collectAsState()
    val progressEvent by autonomousAgent.progressEvents.collectAsState()

    var inputText by remember { mutableStateOf("") }
    val messages = remember {
        mutableStateListOf(
            ChatMessage(
                sender = MessageSender.AGENT,
                text = "Hi! I am your Autonomous Android AI Agent.\nEquipped with ReAct Reasoning Loop."
            ),
            ChatMessage(
                sender = MessageSender.SYSTEM,
                text = "Enter any high-level goal (e.g. 'Open Settings and scroll down') or configure your Gemini / OpenAI API key."
            )
        )
    }

    // React to agent progress updates
    LaunchedEffect(progressEvent) {
        when (val ev = progressEvent) {
            is AgentProgressEvent.Started -> {
                messages.add(
                    ChatMessage(
                        sender = MessageSender.AGENT,
                        text = "🚀 Starting Autonomous Goal: \"${ev.goal}\"",
                        actionSummary = "AGENT_START"
                    )
                )
                listState.animateScrollToItem(messages.size - 1)
            }
            is AgentProgressEvent.StepStarted -> {
                // optional step marker
            }
            is AgentProgressEvent.Thought -> {
                messages.add(
                    ChatMessage(
                        sender = MessageSender.AGENT,
                        text = "🧠 Thought:\n${ev.thought}",
                        actionSummary = "REASONING"
                    )
                )
                listState.animateScrollToItem(messages.size - 1)
            }
            is AgentProgressEvent.ActionExecuting -> {
                messages.add(
                    ChatMessage(
                        sender = MessageSender.AGENT,
                        text = "⚡ Executing ${ev.actionSummary}:\n${ev.actionJson}",
                        actionSummary = "ACTION_DISPATCH"
                    )
                )
                listState.animateScrollToItem(messages.size - 1)
            }
            is AgentProgressEvent.ActionExecuted -> {
                messages.add(
                    ChatMessage(
                        sender = MessageSender.SYSTEM,
                        text = "Result: ${if (ev.result.success) "SUCCESS" else "FAILED"} — ${ev.result.message} (${ev.result.executionTimeMs}ms)"
                    )
                )
                listState.animateScrollToItem(messages.size - 1)
            }
            is AgentProgressEvent.AwaitingConfirmation -> {
                messages.add(
                    ChatMessage(
                        sender = MessageSender.AGENT,
                        text = "🛡️ Sensitive Action Detected:\n${ev.evaluation.reason}\n\nTarget: ${ev.evaluation.preview}\n\nWaiting for your confirmation...",
                        actionSummary = "SECURITY_CONFIRMATION"
                    )
                )
                listState.animateScrollToItem(messages.size - 1)
            }
            is AgentProgressEvent.VisionLocating -> {
                messages.add(
                    ChatMessage(
                        sender = MessageSender.AGENT,
                        text = "👁️ Vision Fallback:\n${ev.target}",
                        actionSummary = "VISION_LOCATE"
                    )
                )
                listState.animateScrollToItem(messages.size - 1)
            }
            is AgentProgressEvent.Completed -> {
                messages.add(
                    ChatMessage(
                        sender = MessageSender.AGENT,
                        text = "🎯 Goal Completed:\n${ev.summary}",
                        actionSummary = "GOAL_DONE"
                    )
                )
                listState.animateScrollToItem(messages.size - 1)
            }
            is AgentProgressEvent.Failed -> {
                messages.add(
                    ChatMessage(
                        sender = MessageSender.AGENT,
                        text = "❌ Agent Stopped:\n${ev.error}",
                        actionSummary = "GOAL_FAILED"
                    )
                )
                listState.animateScrollToItem(messages.size - 1)
            }
            is AgentProgressEvent.Cancelled -> {
                messages.add(
                    ChatMessage(
                        sender = MessageSender.SYSTEM,
                        text = "Agent stopped by user."
                    )
                )
                listState.animateScrollToItem(messages.size - 1)
            }
            null -> {}
        }
    }

    val handleSend: (String) -> Unit = { query ->
        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) {
            messages.add(ChatMessage(sender = MessageSender.USER, text = trimmed))
            inputText = ""

            coroutineScope.launch {
                listState.animateScrollToItem(messages.size - 1)

                // 1. Direct JSON Action check
                val parseResult = ActionValidator.parseAction(trimmed)
                if (parseResult.isSuccess) {
                    val action = parseResult.getOrThrow()
                    val result = ActionExecutor.execute(action, context)
                    messages.add(
                        ChatMessage(
                            sender = MessageSender.AGENT,
                            text = if (result.success) "Action Succeeded:\n${result.message}" else "Action Failed:\n${result.message}",
                            actionSummary = "${result.actionType} (${result.executionTimeMs}ms)"
                        )
                    )
                    listState.animateScrollToItem(messages.size - 1)
                    return@launch
                }

                // 2. Shorthand single actions
                val lower = trimmed.lowercase()
                val mappedAction: AgentAction? = when {
                    lower == "inspect" || lower == "screen" -> AgentAction.GetUi
                    lower == "screenshot" -> AgentAction.Screenshot
                    lower == "back" -> AgentAction.KeyEvent(SystemKey.BACK)
                    lower == "home" -> AgentAction.KeyEvent(SystemKey.HOME)
                    lower == "recents" -> AgentAction.KeyEvent(SystemKey.RECENTS)
                    lower == "scroll down" || lower == "swipe up" -> AgentAction.Scroll(ScrollDirection.DOWN)
                    lower == "scroll up" || lower == "swipe down" -> AgentAction.Scroll(ScrollDirection.UP)
                    lower.startsWith("open ") && trimmed.split(" ").size == 2 -> {
                        val target = trimmed.substring(5).trim()
                        val pkg = if (target.contains('.')) target else when (target.lowercase()) {
                            "settings" -> "com.android.settings"
                            "chrome" -> "com.android.chrome"
                            "telegram" -> "org.telegram.messenger"
                            "whatsapp" -> "com.whatsapp"
                            else -> target
                        }
                        AgentAction.OpenApp(pkg)
                    }
                    lower.startsWith("click ") && trimmed.split(" ").size <= 3 -> {
                        val target = trimmed.substring(6).trim()
                        AgentAction.Click(selector = ElementSelector(text = target))
                    }
                    else -> null
                }

                if (mappedAction != null) {
                    val result = ActionExecutor.execute(mappedAction, context)
                    val extraData = if (result.data != null && result.actionType == "GET_UI") {
                        "\n\nUI Snapshot: ${result.data.take(150)}..."
                    } else ""
                    messages.add(
                        ChatMessage(
                            sender = MessageSender.AGENT,
                            text = "${result.message}$extraData",
                            actionSummary = "${result.actionType} (${result.executionTimeMs}ms)"
                        )
                    )
                } else {
                    // 3. Autonomous AI Goal Runner
                    if (!aiConfig.isConfigured) {
                        messages.add(
                            ChatMessage(
                                sender = MessageSender.AGENT,
                                text = "⚠️ AI API Key is not configured yet.\n\nTap the 'Configure AI' chip below or the Brain icon to set your Gemini or OpenAI key, and I will autonomously complete: \"$trimmed\".",
                                actionSummary = "AI_KEY_REQUIRED"
                            )
                        )
                    } else {
                        autonomousAgent.startGoal(
                            goal = trimmed,
                            scope = coroutineScope,
                            onFinish = { success, msg ->
                                // handled by flow events
                            }
                        )
                    }
                }
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    if (showSafetyDialog) {
        com.agent.androidmcp.ui.components.SafetySettingsDialog(
            onDismiss = { showSafetyDialog = false }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        ServiceStatusBanner(isConnected = isConnected)

        // Provider & Model Modern Floating Selector Bar
        Surface(
            color = SurfaceDark,
            border = BorderStroke(1.dp, BorderSubtle),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Provider Selector Chip & Dropdown
                Box {
                    Surface(
                        onClick = { providerMenuExpanded = true },
                        shape = RoundedCornerShape(10.dp),
                        color = SurfaceLight,
                        border = BorderStroke(1.dp, BorderSubtle),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = aiConfig.provider.displayName,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AccentTeal,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 120.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Select Provider",
                                tint = AccentTeal,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = providerMenuExpanded,
                        onDismissRequest = { providerMenuExpanded = false }
                    ) {
                        Text(
                            text = "Select Provider",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextMuted,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                        HorizontalDivider(color = BorderSubtle)
                        AiProvider.values().forEach { prov ->
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = prov.displayName,
                                            fontWeight = if (prov == aiConfig.provider) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (prov == aiConfig.provider) AccentTeal else TextPrimary,
                                            fontSize = 13.sp
                                        )
                                        if (prov == aiConfig.provider) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = AccentTeal,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    providerMenuExpanded = false
                                    aiConfig = AiConfigRepository.switchProvider(context, prov)
                                    Toast.makeText(context, "Switched to ${prov.displayName}", Toast.LENGTH_SHORT).show()
                                    if (!aiConfig.isConfigured && prov != AiProvider.OLLAMA) {
                                        quickKeyInput = ""
                                        showQuickKeyDialog = true
                                    }
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Model Selector Chip & Dropdown
                Box(modifier = Modifier.weight(1f)) {
                    Surface(
                        onClick = { modelMenuExpanded = true },
                        shape = RoundedCornerShape(10.dp),
                        color = SurfaceLight,
                        border = BorderStroke(1.dp, BorderSubtle),
                        modifier = Modifier
                            .height(30.dp)
                            .fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = aiConfig.model,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Normal,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Select Model",
                                tint = TextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = modelMenuExpanded,
                        onDismissRequest = { modelMenuExpanded = false }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Models (${aiConfig.provider.displayName})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextMuted
                            )
                            if (isFetchingModels) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = AccentTeal
                                )
                            } else {
                                IconButton(
                                    onClick = { refreshModels(force = true) },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Refresh Models",
                                        tint = AccentTeal,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = BorderSubtle)
                        availableModels.forEach { mdl ->
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = mdl,
                                            fontWeight = if (mdl == aiConfig.model) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (mdl == aiConfig.model) AccentTeal else TextPrimary,
                                            fontSize = 13.sp
                                        )
                                        if (mdl == aiConfig.model) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = AccentTeal,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    modelMenuExpanded = false
                                    val updated = aiConfig.copy(model = mdl)
                                    AiConfigRepository.saveConfig(context, updated)
                                    aiConfig = updated
                                    Toast.makeText(context, "Model: $mdl", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                        HorizontalDivider(color = BorderSubtle)
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "Custom",
                                        tint = AccentTeal,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = "Custom Model...",
                                        fontSize = 13.sp,
                                        color = AccentTeal
                                    )
                                }
                            },
                            onClick = {
                                modelMenuExpanded = false
                                customModelInput = aiConfig.model
                                showCustomModelDialog = true
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Actions: API Key & Full Settings
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    IconButton(
                        onClick = {
                            quickKeyInput = aiConfig.apiKey
                            showQuickKeyDialog = true
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Box(contentAlignment = Alignment.TopEnd) {
                            Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = "API Key",
                                tint = TextSecondary,
                                modifier = Modifier.size(15.dp)
                            )
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .background(if (aiConfig.isConfigured) SuccessGreen else WarningAmber, CircleShape)
                            )
                        }
                    }

                    IconButton(
                        onClick = { showSettingsDialog = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Full AI Settings",
                            tint = TextSecondary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }

        // Agent running active banner
        if (isAgentRunning) {
            Surface(
                color = AccentTeal.copy(alpha = 0.15f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = AccentTeal,
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Autonomous Reasoning Loop Active...",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = AccentTeal
                        )
                    }
                    Button(
                        onClick = { autonomousAgent.stop() },
                        colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Stop, contentDescription = "Stop", modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Stop", fontSize = 11.sp)
                    }
                }
            }
        }

        // Chat messages list
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                ChatMessageItem(message = message)
            }
        }

        // Quick action suggestions
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                SuggestionChip(
                    onClick = { handleSend("Open YouTube and search and play family plan trailer in 1080p") },
                    label = { Text("Play YouTube in 1080p", fontSize = 11.sp) },
                    border = BorderStroke(1.dp, BorderSubtle),
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = SurfaceDark,
                        labelColor = TextSecondary
                    )
                )
            }
            item {
                SuggestionChip(
                    onClick = { handleSend("Open Settings and scroll down") },
                    label = { Text("Scroll Settings", fontSize = 11.sp) },
                    border = BorderStroke(1.dp, BorderSubtle),
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = SurfaceDark,
                        labelColor = TextSecondary
                    )
                )
            }
            item {
                SuggestionChip(
                    onClick = { handleSend("screenshot") },
                    label = { Text("Screenshot", fontSize = 11.sp) },
                    border = BorderStroke(1.dp, BorderSubtle),
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = SurfaceDark,
                        labelColor = TextSecondary
                    )
                )
            }
            item {
                SuggestionChip(
                    onClick = { handleSend("inspect") },
                    label = { Text("Inspect Screen", fontSize = 11.sp) },
                    border = BorderStroke(1.dp, BorderSubtle),
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = SurfaceDark,
                        labelColor = TextSecondary
                    )
                )
            }
            item {
                SuggestionChip(
                    onClick = { handleSend("scroll down") },
                    label = { Text("Scroll Down", fontSize = 11.sp) },
                    border = BorderStroke(1.dp, BorderSubtle),
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = SurfaceDark,
                        labelColor = TextSecondary
                    )
                )
            }
            item {
                SuggestionChip(
                    onClick = { handleSend("back") },
                    label = { Text("Back", fontSize = 11.sp) },
                    border = BorderStroke(1.dp, BorderSubtle),
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = SurfaceDark,
                        labelColor = TextSecondary
                    )
                )
            }
            item {
                SuggestionChip(
                    onClick = { handleSend("home") },
                    label = { Text("Home", fontSize = 11.sp) },
                    border = BorderStroke(1.dp, BorderSubtle),
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = SurfaceDark,
                        labelColor = TextSecondary
                    )
                )
            }
        }

        // Modern Floating Input Bar
        Surface(
            color = SurfaceDark,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, BorderSubtle),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = {
                        Text(
                            text = if (isAgentRunning) "Agent is executing..." else "Ask AI or enter goal...",
                            fontSize = 13.5.sp,
                            color = TextMuted
                        )
                    },
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    maxLines = 4,
                    enabled = !isAgentRunning
                )
                IconButton(
                    onClick = { handleSend(inputText) },
                    modifier = Modifier
                        .size(38.dp)
                        .background(
                            if (inputText.isNotBlank() && !isAgentRunning) AccentTeal else SurfaceLight,
                            CircleShape
                        ),
                    enabled = !isAgentRunning && inputText.isNotBlank()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (inputText.isNotBlank() && !isAgentRunning) Color.White else TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }

    if (showSettingsDialog) {
        AiSettingsDialog(
            initialConfig = aiConfig,
            onDismiss = { showSettingsDialog = false },
            onSave = { newConfig ->
                AiConfigRepository.saveConfig(context, newConfig)
                aiConfig = newConfig
                Toast.makeText(context, "AI Configuration Saved (${newConfig.provider.displayName})", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showQuickKeyDialog) {
        AlertDialog(
            onDismissRequest = { showQuickKeyDialog = false },
            title = { Text("${aiConfig.provider.displayName} API Key", fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Enter API Key for ${aiConfig.provider.displayName}:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = quickKeyInput,
                        onValueChange = { quickKeyInput = it },
                        placeholder = {
                            Text(
                                when (aiConfig.provider) {
                                    AiProvider.GEMINI -> "AIzaSy..."
                                    AiProvider.OPENAI -> "sk-..."
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
            },
            confirmButton = {
                Button(
                    onClick = {
                        aiConfig = AiConfigRepository.setKeyForProvider(context, aiConfig.provider, quickKeyInput.trim())
                        showQuickKeyDialog = false
                        Toast.makeText(context, "Key saved for ${aiConfig.provider.displayName}", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Save Key")
                }
            },
            dismissButton = {
                TextButton(onClick = { showQuickKeyDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showCustomModelDialog) {
        AlertDialog(
            onDismissRequest = { showCustomModelDialog = false },
            title = { Text("Custom Model Identifier", fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Enter model name for ${aiConfig.provider.displayName}:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = customModelInput,
                        onValueChange = { customModelInput = it },
                        placeholder = { Text("e.g. gpt-4.5-preview, mistral-large") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (customModelInput.isNotBlank()) {
                            val updated = aiConfig.copy(model = customModelInput.trim())
                            AiConfigRepository.saveConfig(context, updated)
                            aiConfig = updated
                            showCustomModelDialog = false
                            Toast.makeText(context, "Model set to ${updated.model}", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Apply Model")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomModelDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ChatMessageItem(message: ChatMessage) {
    val isUser = message.sender == MessageSender.USER
    val isSystem = message.sender == MessageSender.SYSTEM

    if (isSystem) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = SurfaceDark,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, BorderSubtle)
            ) {
                Text(
                    text = message.text,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    fontSize = 11.sp,
                    color = TextMuted,
                    lineHeight = 15.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = if (isUser) BubbleUser else BubbleAgent,
            border = if (isUser) null else BorderStroke(1.dp, BorderSubtle),
            modifier = Modifier.widthIn(max = 330.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                if (!isUser) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(AccentTeal, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (message.actionSummary == "REASONING") "Reasoning" else "Agent",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AccentTeal
                        )
                    }
                }

                Text(
                    text = message.text,
                    fontSize = 13.5.sp,
                    color = TextPrimary,
                    lineHeight = 19.sp,
                    fontFamily = if (message.text.startsWith("{") || message.text.startsWith("⚡")) FontFamily.Monospace else FontFamily.Default
                )

                if (message.actionSummary != null && message.actionSummary != "REASONING") {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        color = SurfaceLight,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, BorderSubtle)
                    ) {
                        Text(
                            text = message.actionSummary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = AccentTeal,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}
