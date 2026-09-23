package com.agent.androidmcp.agent

import android.content.Context
import android.util.Log
import com.agent.androidmcp.accessibility.AgentAccessibilityService
import com.agent.androidmcp.action.ActionExecutor
import com.agent.androidmcp.action.ActionResult
import com.agent.androidmcp.action.ActionValidator
import com.agent.androidmcp.action.AgentAction
import com.agent.androidmcp.ai.AiConfig
import com.agent.androidmcp.ai.LlmClient
import com.agent.androidmcp.model.ScreenState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface AgentProgressEvent {
    data class Started(val goal: String) : AgentProgressEvent
    data class StepStarted(val stepIndex: Int, val totalSteps: Int) : AgentProgressEvent
    data class Thought(val thought: String) : AgentProgressEvent
    data class ActionExecuting(val actionSummary: String, val actionJson: String) : AgentProgressEvent
    data class ActionExecuted(val result: ActionResult) : AgentProgressEvent
    data class AwaitingConfirmation(val evaluation: com.agent.androidmcp.safety.SafetyEvaluation) : AgentProgressEvent
    data class VisionLocating(val target: String) : AgentProgressEvent
    data class Completed(val summary: String) : AgentProgressEvent
    data class Failed(val error: String) : AgentProgressEvent
    data object Cancelled : AgentProgressEvent
}

class AutonomousAgent(
    private val context: Context,
    private val config: AiConfig
) {

    private val llmClient = LlmClient(config)
    private var agentJob: Job? = null

    private val _progressEvents = MutableStateFlow<AgentProgressEvent?>(null)
    val progressEvents: StateFlow<AgentProgressEvent?> = _progressEvents.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    companion object {
        private const val TAG = "AutonomousAgent"
    }

    fun startGoal(goal: String, scope: CoroutineScope, onFinish: (success: Boolean, message: String) -> Unit) {
        if (_isRunning.value) {
            Log.w(TAG, "Agent is already running a goal.")
            return
        }

        _isRunning.value = true
        agentJob = scope.launch {
            try {
                executeGoalLoop(goal, onFinish)
            } catch (e: CancellationException) {
                Log.i(TAG, "Autonomous agent goal cancelled by user")
                _progressEvents.value = AgentProgressEvent.Cancelled
                onFinish(false, "Task cancelled by user")
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during autonomous goal execution", e)
                _progressEvents.value = AgentProgressEvent.Failed("Error: ${e.message}")
                onFinish(false, "Failed: ${e.message}")
            } finally {
                _isRunning.value = false
            }
        }
    }

    fun stop() {
        if (_isRunning.value) {
            agentJob?.cancel()
            com.agent.androidmcp.safety.ConfirmationManager.cancelAll()
            _isRunning.value = false
        }
    }

    private suspend fun executeGoalLoop(
        goal: String,
        onFinish: (success: Boolean, message: String) -> Unit
    ) {
        _progressEvents.value = AgentProgressEvent.Started(goal)
        val history = mutableListOf<String>()
        val maxSteps = config.maxSteps.coerceIn(1, 25)

        val service = AgentAccessibilityService.getInstance()
        if (service == null) {
            val err = "Accessibility Service is not enabled. Please enable it in Settings."
            _progressEvents.value = AgentProgressEvent.Failed(err)
            onFinish(false, err)
            return
        }

        for (step in 1..maxSteps) {
            currentCoroutineContext().ensureActive()
            _progressEvents.value = AgentProgressEvent.StepStarted(step, maxSteps)

            // 1. OBSERVE: capture screen state & build compact UI hierarchy
            val screenState = service.refreshScreenState()
            val currentApp = screenState.packageName.ifBlank { "unknown" }
            val compactUi = formatCompactUi(screenState)

            // Multimodal Vision support: if screen tree is sparse (< 6 elements) or previous action failed, attach screenshot
            val shouldAttachScreenshot = config.visionFallbackEnabled && (
                screenState.elements.size < 6 || history.lastOrNull()?.contains("FAILED") == true
            )
            val screenshotBase64 = if (shouldAttachScreenshot) {
                val bitmap = service.takeScreenshotCompat()
                bitmap?.let { com.agent.androidmcp.ai.VisionImageUtils.toBase64Jpeg(it, quality = 75, maxDimension = 1024) }
            } else {
                null
            }

            // 2. DECIDE: Query LLM
            val responseResult = llmClient.queryNextStep(
                userGoal = goal,
                history = history,
                compactUiTree = compactUi,
                currentApp = currentApp,
                screenshotBase64 = screenshotBase64
            )

            if (responseResult.isFailure) {
                val err = responseResult.exceptionOrNull()?.message ?: "LLM query failed"
                _progressEvents.value = AgentProgressEvent.Failed("AI error: $err")
                onFinish(false, "AI error at step $step: $err")
                return
            }

            val stepResponse = responseResult.getOrThrow()
            _progressEvents.value = AgentProgressEvent.Thought(stepResponse.thought)

            // Check if model concluded goal is done
            if (stepResponse.isDone) {
                val summary = stepResponse.finalAnswer ?: stepResponse.thought
                _progressEvents.value = AgentProgressEvent.Completed(summary)
                onFinish(true, summary)
                return
            }

            // 3. ACT: Parse and execute action
            val parseResult = ActionValidator.parseAction(stepResponse.actionJson)
            val action = parseResult.getOrElse {
                Log.w(TAG, "Failed to parse action JSON: ${stepResponse.actionJson}", it)
                AgentAction.Wait(1000)
            }

            val actionDesc = action::class.simpleName ?: "ACTION"
            _progressEvents.value = AgentProgressEvent.ActionExecuting(actionDesc, stepResponse.actionJson)

            // Safety Policy Evaluation
            val safetyConfig = com.agent.androidmcp.safety.SafetyConfigRepository.loadConfig(context)
            val safetyEvaluation = com.agent.androidmcp.safety.SafetyEvaluator.evaluate(action, screenState)

            if (safetyEvaluation.level == com.agent.androidmcp.safety.SafetyLevel.CONFIRMATION_REQUIRED &&
                safetyConfig.isConfirmationRequired(safetyEvaluation.riskCategory)
            ) {
                Log.w(TAG, "Sensitive action detected (${safetyEvaluation.riskCategory}): ${safetyEvaluation.preview}. Awaiting confirmation...")
                _progressEvents.value = AgentProgressEvent.AwaitingConfirmation(safetyEvaluation)
                val allowed = com.agent.androidmcp.safety.ConfirmationManager.requestConfirmation(action, safetyEvaluation)
                if (!allowed) {
                    val deniedMsg = "User denied action: ${safetyEvaluation.preview} (${safetyEvaluation.reason})"
                    Log.i(TAG, deniedMsg)
                    val deniedResult = ActionResult(
                        success = false,
                        actionType = actionDesc,
                        message = deniedMsg
                    )
                    _progressEvents.value = AgentProgressEvent.ActionExecuted(deniedResult)
                    history.add("Step $step: Action $actionDesc was REJECTED by the user because: ${safetyEvaluation.reason}. Please replan an alternative safe action or conclude the task.")
                    delay(300L)
                    continue
                }
            }

            val actionResult = ActionExecutor.execute(action, context, bypassSafety = true)
            _progressEvents.value = AgentProgressEvent.ActionExecuted(actionResult)

            if (actionResult.message.contains("Vision Fallback")) {
                _progressEvents.value = AgentProgressEvent.VisionLocating(actionResult.message)
            }

            // Record into step history for subsequent prompts
            val historyRecord = "Step $step: Thought='${stepResponse.thought}' -> Action=$actionDesc -> Result=${if (actionResult.success) "SUCCESS" else "FAILED"} (${actionResult.message})"
            history.add(historyRecord)

            // Fast inter-step settling delay for UI animations
            delay(350L)
        }

        // Reached max steps
        val timeoutMsg = "Reached maximum step limit ($maxSteps) without full completion."
        _progressEvents.value = AgentProgressEvent.Completed(timeoutMsg)
        onFinish(true, timeoutMsg)
    }

    private fun formatCompactUi(screen: ScreenState): String {
        if (screen.elements.isEmpty()) return "(No visible elements detected on current screen)"

        val sb = StringBuilder()
        sb.append("App: ${screen.packageName} | Window: ${screen.windowTitle.ifEmpty { "Main" }}\n")

        // Include meaningful elements (interactive, text, or inputs)
        val filtered = screen.elements.filter { el ->
            el.text.isNotBlank() || el.contentDescription.isNotBlank() || el.isClickable || el.isEditable
        }.take(35) // keep token count small and fast

        for (el in filtered) {
            val flags = mutableListOf<String>()
            if (el.isClickable) flags.add("clickable")
            if (el.isEditable) flags.add("editable")
            if (el.isChecked) flags.add("checked")

            val flagStr = if (flags.isNotEmpty()) "[${flags.joinToString(",")}]" else ""
            val idStr = el.viewIdResourceName?.substringAfterLast('/') ?: el.id
            val label = el.displayLabel.take(40)

            sb.append("- [${el.id}] ${el.simplifiedType.uppercase()}: \"$label\" id=$idStr center=(${el.bounds.centerX},${el.bounds.centerY}) $flagStr\n")
        }

        return sb.toString()
    }
}
