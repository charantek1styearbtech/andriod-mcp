package com.agent.androidmcp.action

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import com.agent.androidmcp.accessibility.AccessibilityNodeHelper
import com.agent.androidmcp.accessibility.AccessibilityState
import com.agent.androidmcp.accessibility.AgentAccessibilityService
import com.agent.androidmcp.accessibility.GestureHelper
import com.agent.androidmcp.model.ScreenState
import com.agent.androidmcp.model.UiElement
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream

object ActionExecutor {

    private const val TAG = "ActionExecutor"

    suspend fun execute(action: AgentAction, context: Context, bypassSafety: Boolean = false): ActionResult {
        val startTime = System.currentTimeMillis()
        val service = AgentAccessibilityService.getInstance()
        val actionType = action::class.simpleName ?: "UNKNOWN"

        val (screenWidth, screenHeight) = getScreenDimensions(context)

        // Validate action constraints first
        val validationResult = ActionValidator.validate(action, screenWidth, screenHeight)
        if (validationResult.isFailure) {
            val err = validationResult.exceptionOrNull()?.message ?: "Validation failed"
            return ActionResult(
                success = false,
                actionType = actionType,
                message = "Validation error: $err",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }

        // Safety policy check
        if (!bypassSafety) {
            val safetyConfig = com.agent.androidmcp.safety.SafetyConfigRepository.loadConfig(context)
            val currentScreen = service?.refreshScreenState()
            val safetyEval = com.agent.androidmcp.safety.SafetyEvaluator.evaluate(action, currentScreen)

            if (safetyEval.level == com.agent.androidmcp.safety.SafetyLevel.CONFIRMATION_REQUIRED &&
                safetyConfig.isConfirmationRequired(safetyEval.riskCategory)
            ) {
                Log.w(TAG, "Action requires safety confirmation: ${safetyEval.reason}")
                val allowed = com.agent.androidmcp.safety.ConfirmationManager.requestConfirmation(action, safetyEval)
                if (!allowed) {
                    return ActionResult(
                        success = false,
                        actionType = actionType,
                        message = "Action blocked by safety policy: User denied confirmation on device (${safetyEval.preview})",
                        executionTimeMs = System.currentTimeMillis() - startTime
                    )
                }
            }
        }

        if (service == null && action !is AgentAction.OpenApp && action !is AgentAction.Wait) {
            return ActionResult(
                success = false,
                actionType = actionType,
                message = "AgentAccessibilityService is not connected. Enable it in Settings.",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }

        return try {
            val result = when (action) {
                is AgentAction.OpenApp -> executeOpenApp(action.packageName, context)
                is AgentAction.Click -> executeClick(action, service!!, screenWidth, screenHeight, context)
                is AgentAction.LongClick -> executeLongClick(action, service!!, screenWidth, screenHeight, context)
                is AgentAction.Type -> executeType(action, service!!)
                is AgentAction.ClearText -> executeClearText(action, service!!)
                is AgentAction.Scroll -> executeScroll(action, service!!, screenWidth, screenHeight)
                is AgentAction.Swipe -> executeSwipe(action, service!!)
                is AgentAction.KeyEvent -> executeKeyEvent(action.key, service!!)
                is AgentAction.Wait -> {
                    delay(action.ms)
                    ActionResult(true, actionType, "Waited for ${action.ms}ms")
                }
                is AgentAction.GetUi -> executeGetUi(service!!)
                is AgentAction.Screenshot -> executeScreenshot(service!!)
            }

            // After state-mutating actions, refresh screen state
            if (action !is AgentAction.GetUi && action !is AgentAction.Wait && action !is AgentAction.Screenshot) {
                service?.refreshScreenState()
            }

            result.copy(executionTimeMs = System.currentTimeMillis() - startTime)
        } catch (e: Exception) {
            Log.e(TAG, "Error executing action $actionType", e)
            ActionResult(
                success = false,
                actionType = actionType,
                message = "Execution failed: ${e.message}",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }

    private fun executeOpenApp(packageName: String, context: Context): ActionResult {
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                ActionResult(true, "OPEN_APP", "Launched app $packageName")
            } else {
                ActionResult(false, "OPEN_APP", "Could not find launcher activity for package: $packageName")
            }
        } catch (e: Exception) {
            ActionResult(false, "OPEN_APP", "Error launching $packageName: ${e.message}")
        }
    }

    private suspend fun executeClick(
        action: AgentAction.Click,
        service: AgentAccessibilityService,
        screenWidth: Int,
        screenHeight: Int,
        context: Context
    ): ActionResult {
        // 1. Direct coordinates
        if (action.x != null && action.y != null) {
            val success = GestureHelper.clickAt(service, action.x.toFloat(), action.y.toFloat())
            return ActionResult(
                success = success,
                actionType = "CLICK",
                message = if (success) "Clicked at (${action.x}, ${action.y})" else "Failed coordinate click at (${action.x}, ${action.y})"
            )
        }

        // 2. Resolve selector
        val selector = action.selector
            ?: return ActionResult(false, "CLICK", "No selector or coordinates provided")

        val screen = service.refreshScreenState()
        val matchedElement = findMatchingElement(screen, selector)

        if (matchedElement == null) {
            // Vision Fallback: if enabled and configured, visually locate target
            val aiConfig = com.agent.androidmcp.ai.AiConfigRepository.loadConfig(context)
            if (aiConfig.visionFallbackEnabled && aiConfig.isConfigured) {
                val targetDesc = selector.text?.ifBlank { null }
                    ?: selector.contentDescription?.ifBlank { null }
                    ?: selector.id
                    ?: selector.viewId
                    ?: "target UI element"

                Log.i(TAG, "No element in accessibility tree for selector $selector. Triggering Vision Fallback for: '$targetDesc'")
                val screenshot = service.takeScreenshotCompat()
                if (screenshot != null) {
                    val locateResult = com.agent.androidmcp.ai.VisionLocator.locate(
                        screenshot = screenshot,
                        targetDescription = targetDesc,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        config = aiConfig
                    )

                    if (locateResult.found && locateResult.x != null && locateResult.y != null) {
                        Log.i(TAG, "Vision Fallback located '$targetDesc' at (${locateResult.x}, ${locateResult.y})")
                        val gestureSuccess = GestureHelper.clickAt(service, locateResult.x.toFloat(), locateResult.y.toFloat())
                        return ActionResult(
                            success = gestureSuccess,
                            actionType = "CLICK",
                            message = if (gestureSuccess) {
                                "Clicked '$targetDesc' via Vision Fallback at (${locateResult.x}, ${locateResult.y})"
                            } else {
                                "Vision located '$targetDesc' at (${locateResult.x}, ${locateResult.y}) but tap gesture failed"
                            }
                        )
                    } else {
                        Log.w(TAG, "Vision Fallback could not locate '$targetDesc': ${locateResult.reason}")
                    }
                } else {
                    Log.w(TAG, "Screenshot capture failed for Vision Fallback")
                }
            }

            return ActionResult(false, "CLICK", "No element matched selector: $selector")
        }

        // Try semantic node click first
        val nodeClicked = service.clickElement(matchedElement)
        if (nodeClicked) {
            return ActionResult(true, "CLICK", "Clicked element '${matchedElement.displayLabel}' via Node Action")
        }

        // Fallback: coordinate gesture click on element center
        val centerX = matchedElement.bounds.centerX.toFloat()
        val centerY = matchedElement.bounds.centerY.toFloat()
        val gestureSuccess = GestureHelper.clickAt(service, centerX, centerY)
        return ActionResult(
            success = gestureSuccess,
            actionType = "CLICK",
            message = if (gestureSuccess) {
                "Clicked element '${matchedElement.displayLabel}' via Gesture Fallback at ($centerX, $centerY)"
            } else {
                "Failed to click element '${matchedElement.displayLabel}' via Node and Gesture"
            }
        )
    }

    private suspend fun executeLongClick(
        action: AgentAction.LongClick,
        service: AgentAccessibilityService,
        screenWidth: Int,
        screenHeight: Int,
        context: Context
    ): ActionResult {
        val duration = action.durationMs ?: 800L

        if (action.x != null && action.y != null) {
            val success = GestureHelper.longClickAt(service, action.x.toFloat(), action.y.toFloat(), duration)
            return ActionResult(
                success = success,
                actionType = "LONG_CLICK",
                message = if (success) "Long clicked at (${action.x}, ${action.y}) for ${duration}ms" else "Failed long click at (${action.x}, ${action.y})"
            )
        }

        val selector = action.selector
            ?: return ActionResult(false, "LONG_CLICK", "No selector or coordinates provided")

        val screen = service.refreshScreenState()
        val matchedElement = findMatchingElement(screen, selector)

        if (matchedElement == null) {
            val aiConfig = com.agent.androidmcp.ai.AiConfigRepository.loadConfig(context)
            if (aiConfig.visionFallbackEnabled && aiConfig.isConfigured) {
                val targetDesc = selector.text?.ifBlank { null }
                    ?: selector.contentDescription?.ifBlank { null }
                    ?: selector.id
                    ?: selector.viewId
                    ?: "target UI element"

                val screenshot = service.takeScreenshotCompat()
                if (screenshot != null) {
                    val locateResult = com.agent.androidmcp.ai.VisionLocator.locate(
                        screenshot = screenshot,
                        targetDescription = targetDesc,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        config = aiConfig
                    )
                    if (locateResult.found && locateResult.x != null && locateResult.y != null) {
                        val gestureSuccess = GestureHelper.longClickAt(service, locateResult.x.toFloat(), locateResult.y.toFloat(), duration)
                        return ActionResult(
                            success = gestureSuccess,
                            actionType = "LONG_CLICK",
                            message = if (gestureSuccess) {
                                "Long clicked '$targetDesc' via Vision Fallback at (${locateResult.x}, ${locateResult.y}) for ${duration}ms"
                            } else {
                                "Vision located '$targetDesc' at (${locateResult.x}, ${locateResult.y}) but long click gesture failed"
                            }
                        )
                    }
                }
            }
            return ActionResult(false, "LONG_CLICK", "No element matched selector: $selector")
        }

        // Try node long click
        val root = service.rootInActiveWindow
        val targetNode = root?.let { AccessibilityNodeHelper.findNodeByUiElement(it, matchedElement) }
        val nodeLongClicked = targetNode?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true

        if (nodeLongClicked) {
            return ActionResult(true, "LONG_CLICK", "Long clicked '${matchedElement.displayLabel}' via Node Action")
        }

        // Fallback: gesture long click at center
        val centerX = matchedElement.bounds.centerX.toFloat()
        val centerY = matchedElement.bounds.centerY.toFloat()
        val gestureSuccess = GestureHelper.longClickAt(service, centerX, centerY, duration)
        return ActionResult(
            success = gestureSuccess,
            actionType = "LONG_CLICK",
            message = if (gestureSuccess) {
                "Long clicked '${matchedElement.displayLabel}' via Gesture Fallback at ($centerX, $centerY)"
            } else {
                "Failed long click on '${matchedElement.displayLabel}'"
            }
        )
    }

    private fun executeType(action: AgentAction.Type, service: AgentAccessibilityService): ActionResult {
        val root = service.rootInActiveWindow
            ?: return ActionResult(false, "TYPE", "No active window root")

        val targetNode: AccessibilityNodeInfo? = if (action.selector != null) {
            val screen = service.refreshScreenState()
            val matched = findMatchingElement(screen, action.selector)
            matched?.let { AccessibilityNodeHelper.findNodeByUiElement(root, it) }
        } else {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: AccessibilityNodeHelper.extractScreenState(root).elements.firstOrNull { it.isEditable }?.let {
                    AccessibilityNodeHelper.findNodeByUiElement(root, it)
                }
        }

        if (targetNode == null) {
            return ActionResult(false, "TYPE", "Could not find target editable element for typing")
        }

        if (action.clearFirst) {
            val emptyBundle = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            }
            targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, emptyBundle)
        }

        val textBundle = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text)
        }
        val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, textBundle)

        return ActionResult(
            success = success,
            actionType = "TYPE",
            message = if (success) "Typed text successfully" else "Failed to set text on element"
        )
    }

    private fun executeClearText(action: AgentAction.ClearText, service: AgentAccessibilityService): ActionResult {
        val root = service.rootInActiveWindow
            ?: return ActionResult(false, "CLEAR_TEXT", "No active window root")

        val targetNode: AccessibilityNodeInfo? = if (action.selector != null) {
            val screen = service.refreshScreenState()
            val matched = findMatchingElement(screen, action.selector)
            matched?.let { AccessibilityNodeHelper.findNodeByUiElement(root, it) }
        } else {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }

        if (targetNode == null) {
            return ActionResult(false, "CLEAR_TEXT", "No target element found to clear text")
        }

        val emptyBundle = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        }
        val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, emptyBundle)
        return ActionResult(
            success = success,
            actionType = "CLEAR_TEXT",
            message = if (success) "Cleared text" else "Failed to clear text"
        )
    }

    private suspend fun executeScroll(
        action: AgentAction.Scroll,
        service: AgentAccessibilityService,
        screenWidth: Int,
        screenHeight: Int
    ): ActionResult {
        val targetBounds = if (action.selector != null) {
            val screen = service.refreshScreenState()
            findMatchingElement(screen, action.selector)?.bounds
        } else null

        // Try accessibility node scroll first if selector specified
        if (action.selector != null) {
            val root = service.rootInActiveWindow
            val screen = service.refreshScreenState()
            val matched = findMatchingElement(screen, action.selector)
            val node = matched?.let { AccessibilityNodeHelper.findNodeByUiElement(root, it) }
            if (node != null && node.isScrollable) {
                val scrollAction = when (action.direction) {
                    ScrollDirection.DOWN -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    ScrollDirection.UP -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
                val nodeScrollSuccess = node.performAction(scrollAction)
                if (nodeScrollSuccess) {
                    return ActionResult(true, "SCROLL", "Scrolled ${action.direction} via Node Action")
                }
            }
        }

        // Fallback to gesture swipe scroll
        val success = GestureHelper.scroll(service, action.direction, targetBounds, screenWidth, screenHeight)
        return ActionResult(
            success = success,
            actionType = "SCROLL",
            message = if (success) "Scrolled ${action.direction} via Gesture" else "Failed to scroll ${action.direction}"
        )
    }

    private suspend fun executeSwipe(action: AgentAction.Swipe, service: AgentAccessibilityService): ActionResult {
        val success = GestureHelper.swipe(
            service = service,
            startX = action.startX.toFloat(),
            startY = action.startY.toFloat(),
            endX = action.endX.toFloat(),
            endY = action.endY.toFloat(),
            durationMs = action.durationMs
        )
        return ActionResult(
            success = success,
            actionType = "SWIPE",
            message = if (success) {
                "Swiped (${action.startX}, ${action.startY}) -> (${action.endX}, ${action.endY})"
            } else {
                "Failed to execute swipe gesture"
            }
        )
    }

    private fun executeKeyEvent(key: SystemKey, service: AgentAccessibilityService): ActionResult {
        val globalAction = when (key) {
            SystemKey.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            SystemKey.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            SystemKey.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            SystemKey.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            SystemKey.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
        }
        val success = service.performGlobalAction(globalAction)
        return ActionResult(
            success = success,
            actionType = "KEY_EVENT",
            message = if (success) "Executed $key" else "Failed to execute $key"
        )
    }

    private fun executeGetUi(service: AgentAccessibilityService): ActionResult {
        val screen = service.refreshScreenState()
        val json = ActionValidator.jsonParser.encodeToString(ScreenState.serializer(), screen)
        return ActionResult(
            success = true,
            actionType = "GET_UI",
            message = "Extracted UI (${screen.elementCount} elements, app: ${screen.packageName})",
            data = json
        )
    }

    private suspend fun executeScreenshot(service: AgentAccessibilityService): ActionResult {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bitmap = service.takeScreenshotCompat()
            if (bitmap != null) {
                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
                val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                ActionResult(
                    success = true,
                    actionType = "SCREENSHOT",
                    message = "Screenshot captured (${bitmap.width}x${bitmap.height})",
                    data = base64
                )
            } else {
                ActionResult(false, "SCREENSHOT", "Failed to capture screenshot via Accessibility API")
            }
        } else {
            ActionResult(false, "SCREENSHOT", "Screenshot requires Android 11 (API 30) or above")
        }
    }

    private fun findMatchingElement(screen: ScreenState, selector: ElementSelector): UiElement? {
        return screen.elements.firstOrNull { el ->
            var matches = true
            selector.id?.let { if (el.id != it) matches = false }
            selector.text?.let { if (!el.text.contains(it, ignoreCase = true)) matches = false }
            selector.contentDescription?.let { if (!el.contentDescription.contains(it, ignoreCase = true)) matches = false }
            selector.viewId?.let { if (el.viewIdResourceName?.contains(it, ignoreCase = true) != true) matches = false }
            selector.className?.let { if (!el.className.contains(it, ignoreCase = true)) matches = false }
            matches
        }
    }

    fun getScreenDimensions(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val metrics = DisplayMetrics()
        wm?.defaultDisplay?.getRealMetrics(metrics)
        val w = if (metrics.widthPixels > 0) metrics.widthPixels else 1080
        val h = if (metrics.heightPixels > 0) metrics.heightPixels else 2400
        return Pair(w, h)
    }
}
