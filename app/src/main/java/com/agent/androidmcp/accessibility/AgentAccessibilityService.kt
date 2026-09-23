package com.agent.androidmcp.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.agent.androidmcp.model.ScreenState
import com.agent.androidmcp.model.UiElement

class AgentAccessibilityService : AccessibilityService() {

    private var currentPackageName: String = ""

    companion object {
        private const val TAG = "AgentAccessibility"
        private var instance: AgentAccessibilityService? = null

        fun getInstance(): AgentAccessibilityService? = instance

        fun isConnected(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AgentAccessibilityService connected")
        AccessibilityState.setConnected(true)
        refreshScreenState()
    }

    private var lastContentChangeTime: Long = 0L
    private val debounceContentMs: Long = 250L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString()
        if (!pkg.isNullOrEmpty() && pkg != currentPackageName) {
            currentPackageName = pkg
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                refreshScreenState()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val now = System.currentTimeMillis()
                if (now - lastContentChangeTime >= debounceContentMs) {
                    lastContentChangeTime = now
                    refreshScreenState()
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AgentAccessibilityService interrupted")
        AccessibilityState.setStatus("Service interrupted by system")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "AgentAccessibilityService destroyed")
        instance = null
        AccessibilityState.setConnected(false)
    }

    /**
     * Captures and updates current screen state.
     */
    fun refreshScreenState(): ScreenState {
        val root = try {
            rootInActiveWindow
        } catch (e: Exception) {
            Log.e(TAG, "Error obtaining root node", e)
            null
        }

        val pkg = root?.packageName?.toString() ?: currentPackageName
        val screenState = AccessibilityNodeHelper.extractScreenState(root, pkg)
        AccessibilityState.updateScreen(screenState)
        return screenState
    }

    /**
     * Finds and clicks an element matching the given text or description.
     */
    fun clickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val targetNode = AccessibilityNodeHelper.findNodeByText(root, text) ?: return false
        val clickable = AccessibilityNodeHelper.findClickableParentOrSelf(targetNode)
        val success = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        AccessibilityState.setStatus("Click '$text' result: $success")
        if (success) {
            refreshScreenState()
        }
        return success
    }

    /**
     * Clicks a specific UiElement by matching its properties.
     */
    fun clickElement(element: UiElement): Boolean {
        val root = rootInActiveWindow ?: return false
        val targetNode = AccessibilityNodeHelper.findNodeByUiElement(root, element) ?: return false
        val clickable = AccessibilityNodeHelper.findClickableParentOrSelf(targetNode)
        val success = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        AccessibilityState.setStatus("Click element '${element.displayLabel}' result: $success")
        if (success) {
            refreshScreenState()
        }
        return success
    }

    /**
     * Types text into the currently focused editable input field.
     */
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: AccessibilityNodeHelper.extractScreenState(root).elements.firstOrNull { it.isEditable }?.let {
                AccessibilityNodeHelper.findNodeByUiElement(root, it)
            }
            ?: return false

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val success = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        AccessibilityState.setStatus("Type text result: $success")
        if (success) {
            refreshScreenState()
        }
        return success
    }

    fun pressBack(): Boolean {
        val success = performGlobalAction(GLOBAL_ACTION_BACK)
        AccessibilityState.setStatus("Global Back result: $success")
        return success
    }

    fun pressHome(): Boolean {
        val success = performGlobalAction(GLOBAL_ACTION_HOME)
        AccessibilityState.setStatus("Global Home result: $success")
        return success
    }

    suspend fun takeScreenshotCompat(): android.graphics.Bitmap? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return null
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val callback = object : TakeScreenshotCallback {
                override fun onSuccess(screenshotResult: ScreenshotResult) {
                    val hardwareBitmap = android.graphics.Bitmap.wrapHardwareBuffer(
                        screenshotResult.hardwareBuffer,
                        screenshotResult.colorSpace
                    )
                    val copy = hardwareBitmap?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    screenshotResult.hardwareBuffer.close()
                    if (continuation.isActive) continuation.resumeWith(Result.success(copy))
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "takeScreenshot onFailure errorCode=$errorCode")
                    if (continuation.isActive) continuation.resumeWith(Result.success(null))
                }
            }
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, applicationContext.mainExecutor, callback)
        }
    }
}
