package com.agent.androidmcp.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager
import com.agent.androidmcp.model.ScreenState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AccessibilityState {
    private val _isServiceConnected = MutableStateFlow(false)
    val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

    private val _currentScreen = MutableStateFlow(ScreenState())
    val currentScreen: StateFlow<ScreenState> = _currentScreen.asStateFlow()

    private val _statusMessage = MutableStateFlow("Service initialized")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    fun setConnected(connected: Boolean) {
        _isServiceConnected.value = connected
        if (!connected) {
            _statusMessage.value = "Accessibility Service disconnected"
        } else {
            _statusMessage.value = "Accessibility Service active and connected"
        }
    }

    fun updateScreen(screenState: ScreenState) {
        _currentScreen.value = screenState
        _statusMessage.value = "Updated screen: ${screenState.packageName} (${screenState.elementCount} elements)"
    }

    fun setStatus(message: String) {
        _statusMessage.value = message
    }

    /**
     * Checks if our AccessibilityService is enabled in Android System Settings.
     */
    fun isServiceEnabledInSettings(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val expectedServiceId = "${context.packageName}/${AgentAccessibilityService::class.java.canonicalName}"
        return enabledServices.any { service ->
            val serviceId = service.id
            serviceId.equals(expectedServiceId, ignoreCase = true) ||
                    service.resolveInfo?.serviceInfo?.let {
                        it.packageName == context.packageName && it.name == AgentAccessibilityService::class.java.name
                    } == true
        }
    }
}
