package com.agent.androidmcp.server.remote

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RemoteGatewayStatus(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val serverUrl: String = "ws://192.168.0.100:3000/device/ws",
    val deviceId: String = "oneplus_nord_4",
    val token: String = "nord4_token_secure",
    val lastError: String? = null,
    val lastActivity: String = "Disconnected",
    val messagesReceived: Long = 0L,
    val actionsExecuted: Long = 0L
)

object RemoteGatewayConfigRepository {
    private const val PREFS_NAME = "remote_gateway_prefs"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_TOKEN = "device_token"
    private const val KEY_AUTO_CONNECT = "auto_connect"
    private const val DEFAULT_URL = "ws://192.168.0.100:3000/device/ws"

    private fun generateDefaultDeviceId(): String {
        val model = android.os.Build.MODEL.replace("[^a-zA-Z0-9]".toRegex(), "_").lowercase().take(12).ifEmpty { "device" }
        val shortId = java.util.UUID.randomUUID().toString().substring(0, 4)
        return "android_${model}_$shortId"
    }

    private fun generateDefaultToken(): String {
        return "tok_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun loadConfig(context: Context): RemoteGatewayStatus {
        val prefs = getPrefs(context)
        var deviceId = prefs.getString(KEY_DEVICE_ID, null)
        var token = prefs.getString(KEY_TOKEN, null)

        if (deviceId.isNullOrBlank() || token.isNullOrBlank()) {
            deviceId = deviceId ?: generateDefaultDeviceId()
            token = token ?: generateDefaultToken()
            prefs.edit()
                .putString(KEY_DEVICE_ID, deviceId)
                .putString(KEY_TOKEN, token)
                .apply()
        }

        return RemoteGatewayStatus(
            serverUrl = prefs.getString(KEY_SERVER_URL, DEFAULT_URL) ?: DEFAULT_URL,
            deviceId = deviceId,
            token = token
        )
    }

    fun saveConfig(context: Context, serverUrl: String, deviceId: String, token: String) {
        getPrefs(context).edit()
            .putString(KEY_SERVER_URL, serverUrl)
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_TOKEN, token)
            .apply()
    }

    fun isAutoConnect(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTO_CONNECT, false)
    }

    fun setAutoConnect(context: Context, autoConnect: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_CONNECT, autoConnect).apply()
    }
}

object RemoteGatewayState {
    private val _status = MutableStateFlow(RemoteGatewayStatus())
    val status: StateFlow<RemoteGatewayStatus> = _status.asStateFlow()

    fun updateConfig(url: String, deviceId: String, token: String) {
        _status.value = _status.value.copy(
            serverUrl = url,
            deviceId = deviceId,
            token = token
        )
    }

    fun updateConnecting(isConnecting: Boolean) {
        _status.value = _status.value.copy(
            isConnecting = isConnecting,
            lastActivity = if (isConnecting) "Connecting..." else _status.value.lastActivity
        )
    }

    fun updateConnected(connected: Boolean, error: String? = null) {
        _status.value = _status.value.copy(
            isConnected = connected,
            isConnecting = false,
            lastError = error,
            lastActivity = if (connected) "Connected & Authenticated" else "Disconnected"
        )
    }

    fun recordMessageReceived() {
        _status.value = _status.value.copy(
            messagesReceived = _status.value.messagesReceived + 1
        )
    }

    fun recordActionExecuted(activity: String) {
        _status.value = _status.value.copy(
            actionsExecuted = _status.value.actionsExecuted + 1,
            lastActivity = activity
        )
    }

    fun setActivity(activity: String) {
        _status.value = _status.value.copy(lastActivity = activity)
    }

    fun setError(error: String) {
        _status.value = _status.value.copy(lastError = error)
    }
}
