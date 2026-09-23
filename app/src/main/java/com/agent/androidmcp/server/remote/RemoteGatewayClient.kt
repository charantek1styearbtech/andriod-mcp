package com.agent.androidmcp.server.remote

import android.content.Context
import android.os.Build
import android.util.Log
import com.agent.androidmcp.server.mcp.JsonRpcRequest
import com.agent.androidmcp.server.mcp.McpHandler
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object RemoteGatewayClient {

    private const val TAG = "RemoteGatewayClient"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var activeSocket: WebSocket? = null
    private var isManuallyStopped = AtomicBoolean(true)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var reconnectJob: Job? = null
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    private var currentContext: Context? = null
    private var currentUrl: String = ""
    private var currentDeviceId: String = ""
    private var currentToken: String = ""

    fun start(context: Context, url: String, deviceId: String, token: String) {
        currentContext = context.applicationContext
        currentUrl = url.trim()
        currentDeviceId = deviceId.trim()
        currentToken = token.trim()
        isManuallyStopped.set(false)

        RemoteGatewayConfigRepository.saveConfig(context, currentUrl, currentDeviceId, currentToken)
        RemoteGatewayState.updateConfig(currentUrl, currentDeviceId, currentToken)
        RemoteGatewayState.updateConnected(false, null)

        connectInternal()
    }

    fun stop() {
        isManuallyStopped.set(true)
        reconnectJob?.cancel()
        reconnectJob = null

        try {
            activeSocket?.close(1000, "User disconnected")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing socket: ${e.message}")
        }
        activeSocket = null
        RemoteGatewayState.updateConnected(false, null)
        RemoteGatewayState.setActivity("Disconnected by user")
    }

    private fun connectInternal() {
        if (isManuallyStopped.get()) return

        RemoteGatewayState.updateConnecting(true)
        val request = try {
            Request.Builder().url(currentUrl).build()
        } catch (e: Exception) {
            Log.e(TAG, "Invalid gateway URL: $currentUrl", e)
            RemoteGatewayState.updateConnected(false, "Invalid URL: ${e.message}")
            return
        }

        Log.i(TAG, "Connecting to Remote Gateway: $currentUrl (Device: $currentDeviceId)")

        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                activeSocket = webSocket
                Log.i(TAG, "WebSocket connected, sending AUTH handshake...")

                val savedEmail = currentContext?.let { com.agent.androidmcp.auth.GoogleAuthManager.getSavedEmail(it) }

                // Send AUTH handshake
                val authPayload = buildJsonObject {
                    put("type", "AUTH")
                    put("deviceId", currentDeviceId)
                    put("token", currentToken)
                    if (!savedEmail.isNullOrBlank()) {
                        put("email", savedEmail)
                    }
                    putJsonObject("metadata") {
                        put("model", Build.MODEL)
                        put("manufacturer", Build.MANUFACTURER)
                        put("osVersion", Build.VERSION.RELEASE)
                        put("sdkInt", Build.VERSION.SDK_INT)
                        put("appVersion", "1.0.0")
                        if (!savedEmail.isNullOrBlank()) {
                            put("email", savedEmail)
                        }
                    }
                }
                webSocket.send(authPayload.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                RemoteGatewayState.recordMessageReceived()
                scope.launch {
                    handleIncomingMessage(webSocket, text)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closing: $code / $reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code / $reason")
                activeSocket = null
                RemoteGatewayState.updateConnected(false)
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                activeSocket = null
                RemoteGatewayState.updateConnected(false, t.message ?: "Connection error")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (isManuallyStopped.get()) return

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            Log.i(TAG, "Scheduling reconnect in 5 seconds...")
            RemoteGatewayState.setActivity("Reconnecting in 5s...")
            delay(5000)
            if (!isManuallyStopped.get()) {
                connectInternal()
            }
        }
    }

    private suspend fun handleIncomingMessage(socket: WebSocket, text: String) {
        try {
            val jsonElement = json.parseToJsonElement(text)
            val jsonObject = jsonElement.jsonObject
            val type = jsonObject["type"]?.jsonPrimitive?.content ?: return

            when (type) {
                "AUTH_ACK" -> {
                    val success = jsonObject["success"]?.jsonPrimitive?.booleanOrNull ?: false
                    val error = jsonObject["error"]?.jsonPrimitive?.contentOrNull
                    if (success) {
                        Log.i(TAG, "Device authenticated successfully with backend gateway!")
                        RemoteGatewayState.updateConnected(true)
                    } else {
                        Log.e(TAG, "Device authentication failed: $error")
                        RemoteGatewayState.updateConnected(false, error ?: "Auth failed")
                    }
                }

                "PING" -> {
                    val timestamp = jsonObject["timestamp"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
                    val pong = buildJsonObject {
                        put("type", "PONG")
                        put("timestamp", timestamp)
                    }
                    socket.send(pong.toString())
                }

                "EXECUTE_ACTION" -> {
                    val requestId = jsonObject["requestId"]?.jsonPrimitive?.content ?: return
                    val actionName = jsonObject["action"]?.jsonPrimitive?.content ?: ""
                    val params = jsonObject["params"]?.jsonObject ?: buildJsonObject {}

                    Log.i(TAG, "Received EXECUTE_ACTION: $actionName [req=$requestId]")

                    // 1. Immediately send ACK
                    val ack = buildJsonObject {
                        put("type", "ACTION_ACK")
                        put("requestId", requestId)
                        put("deviceId", currentDeviceId)
                        put("status", "RUNNING")
                    }
                    socket.send(ack.toString())

                    // 2. Dispatch execution via McpHandler
                    executeAndSendResult(socket, requestId, actionName, params)
                }

                "CANCEL_ACTION" -> {
                    val requestId = jsonObject["requestId"]?.jsonPrimitive?.content
                    Log.w(TAG, "Server requested CANCEL_ACTION for $requestId")
                    RemoteGatewayState.setActivity("Action cancelled: $requestId")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse incoming message: $text", e)
        }
    }

    private suspend fun executeAndSendResult(
        socket: WebSocket,
        requestId: String,
        actionName: String,
        params: JsonObject
    ) {
        val context = currentContext ?: return
        RemoteGatewayState.recordActionExecuted("Executing $actionName")

        try {
            val isActionExecution = when (actionName) {
                "get_screen", "android_get_screen",
                "take_screenshot", "android_take_screenshot",
                "locate_element", "android_locate_element",
                "run_goal", "android_run_goal",
                "network_start_monitor", "android_network_start_monitor",
                "network_stop_monitor", "android_network_stop_monitor",
                "network_get_events", "android_network_get_events",
                "network_get_connections", "android_network_get_connections",
                "network_assert_traffic", "android_network_assert_traffic" -> false
                else -> true
            }

            val toolName = when {
                isActionExecution -> "android_execute_action"
                actionName.startsWith("android_") -> actionName
                else -> "android_$actionName"
            }

            val arguments = if (isActionExecution) {
                val existingAction = params["action"]
                if (existingAction is JsonObject) {
                    params
                } else {
                    val mergedParams = if (!params.containsKey("action") && actionName != "execute_action") {
                        JsonObject(params.toMutableMap().apply { put("action", JsonPrimitive(actionName)) })
                    } else {
                        params
                    }
                    buildJsonObject {
                        put("action", buildActionObject(mergedParams))
                    }
                }
            } else {
                params
            }

            val mcpHandler = McpHandler(context)
            val mcpRequest = JsonRpcRequest(
                id = JsonPrimitive(requestId),
                method = "tools/call",
                params = buildJsonObject {
                    put("name", JsonPrimitive(toolName))
                    put("arguments", arguments)
                }
            )

            val mcpResponse = mcpHandler.handleRequest(mcpRequest)

            if (mcpResponse.error != null) {
                sendActionResult(socket, requestId, false, null, mcpResponse.error.message)
                return
            }

            val resObj = mcpResponse.result?.jsonObject
            val isError = resObj?.get("isError")?.jsonPrimitive?.booleanOrNull ?: false
            val contentArray = resObj?.get("content")?.jsonArray

            var resultText: String? = null
            var screenshotBase64: String? = null

            if (contentArray != null) {
                for (item in contentArray) {
                    val itemObj = item.jsonObject
                    val contentType = itemObj["type"]?.jsonPrimitive?.content
                    if (contentType == "text") {
                        resultText = itemObj["text"]?.jsonPrimitive?.content
                    } else if (contentType == "image") {
                        screenshotBase64 = itemObj["data"]?.jsonPrimitive?.content
                    }
                }
            }

            if (isError) {
                sendActionResult(socket, requestId, false, null, resultText ?: "Tool execution failed")
            } else {
                val finalResult = buildJsonObject {
                    if (screenshotBase64 != null) {
                        put("screenshotBase64", screenshotBase64)
                    }
                    if (resultText != null) {
                        put("text", resultText)
                    }
                    put("raw", mcpResponse.result ?: buildJsonObject {})
                }
                sendActionResult(socket, requestId, true, finalResult, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing $actionName for req=$requestId", e)
            sendActionResult(socket, requestId, false, null, e.message ?: "Execution error")
        }
    }

    private fun buildActionObject(params: JsonObject): JsonObject {
        val actionType = params["action"]?.jsonPrimitive?.content?.lowercase() ?: "tap"
        val coords = params["coordinates"]?.jsonObject
        val coordX = coords?.get("x")?.jsonPrimitive?.intOrNull
        val coordY = coords?.get("y")?.jsonPrimitive?.intOrNull
        val text = params["text"]?.jsonPrimitive?.contentOrNull
        val elementId = params["element_id"]?.jsonPrimitive?.contentOrNull

        return when (actionType) {
            "tap", "click" -> buildJsonObject {
                put("type", "CLICK")
                if (coordX != null && coordY != null) {
                    put("x", coordX)
                    put("y", coordY)
                }
                if (elementId != null || text != null) {
                    putJsonObject("selector") {
                        elementId?.let { put("viewId", it) }
                        text?.let { put("text", it) }
                    }
                }
            }

            "long_press", "long_click" -> buildJsonObject {
                put("type", "LONG_CLICK")
                if (coordX != null && coordY != null) {
                    put("x", coordX)
                    put("y", coordY)
                }
                if (elementId != null || text != null) {
                    putJsonObject("selector") {
                        elementId?.let { put("viewId", it) }
                        text?.let { put("text", it) }
                    }
                }
                put("durationMs", params["duration_ms"]?.jsonPrimitive?.longOrNull ?: 800L)
            }

            "type" -> buildJsonObject {
                put("type", "TYPE")
                put("text", text ?: "")
                if (elementId != null) {
                    putJsonObject("selector") {
                        put("viewId", elementId)
                    }
                }
            }

            "scroll_forward", "scroll" -> buildJsonObject {
                put("type", "SCROLL")
                put("direction", "DOWN")
            }

            "scroll_backward" -> buildJsonObject {
                put("type", "SCROLL")
                put("direction", "UP")
            }

            "swipe" -> buildJsonObject {
                put("type", "SWIPE")
                put("startX", params["start_x"]?.jsonPrimitive?.intOrNull ?: 500)
                put("startY", params["start_y"]?.jsonPrimitive?.intOrNull ?: 1500)
                put("endX", params["end_x"]?.jsonPrimitive?.intOrNull ?: 500)
                put("endY", params["end_y"]?.jsonPrimitive?.intOrNull ?: 500)
                put("durationMs", params["duration_ms"]?.jsonPrimitive?.longOrNull ?: 300L)
            }

            "press_key" -> buildJsonObject {
                put("type", "KEY_EVENT")
                put("key", params["key_code"]?.jsonPrimitive?.content ?: "BACK")
            }

            "wait" -> buildJsonObject {
                put("type", "WAIT")
                put("ms", params["duration_ms"]?.jsonPrimitive?.longOrNull ?: 1000L)
            }

            "open_app" -> buildJsonObject {
                put("type", "OPEN_APP")
                put("packageName", params["package_name"]?.jsonPrimitive?.content ?: text ?: "")
            }

            else -> buildJsonObject {
                put("type", "WAIT")
                put("ms", 500L)
            }
        }
    }

    private fun sendActionResult(
        socket: WebSocket,
        requestId: String,
        success: Boolean,
        result: JsonElement?,
        error: String?
    ) {
        val payload = buildJsonObject {
            put("type", "ACTION_RESULT")
            put("requestId", requestId)
            put("deviceId", currentDeviceId)
            put("success", success)
            if (result != null) {
                put("result", result)
            }
            if (error != null) {
                put("error", error)
            }
        }
        socket.send(payload.toString())
        RemoteGatewayState.setActivity(if (success) "Completed $requestId" else "Failed $requestId")
    }
}
