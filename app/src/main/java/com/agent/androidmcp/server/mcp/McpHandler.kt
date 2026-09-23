package com.agent.androidmcp.server.mcp

import android.content.Context
import android.util.Log
import com.agent.androidmcp.accessibility.AgentAccessibilityService
import com.agent.androidmcp.action.*
import com.agent.androidmcp.agent.AutonomousAgent
import com.agent.androidmcp.ai.AiConfigRepository
import com.agent.androidmcp.model.ScreenState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import com.agent.androidmcp.network.*

class McpHandler(private val context: Context) {

    private val json = ActionValidator.jsonParser
    private val scope = CoroutineScope(Dispatchers.Default)

    companion object {
        private const val TAG = "McpHandler"
    }

    suspend fun handleRequest(request: JsonRpcRequest): JsonRpcResponse {
        return try {
            when (request.method) {
                "initialize" -> handleInitialize(request)
                "notifications/initialized" -> JsonRpcResponse(id = request.id, result = buildJsonObject {})
                "tools/list" -> handleToolsList(request)
                "tools/call" -> handleToolCall(request)
                "ping" -> JsonRpcResponse(id = request.id, result = buildJsonObject { put("status", "pong") })
                else -> JsonRpcResponse(
                    id = request.id,
                    error = JsonRpcError(code = -32601, message = "Method not found: ${request.method}")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling MCP request ${request.method}", e)
            JsonRpcResponse(
                id = request.id,
                error = JsonRpcError(code = -32603, message = "Internal error: ${e.message}")
            )
        }
    }

    private fun handleInitialize(request: JsonRpcRequest): JsonRpcResponse {
        val result = buildJsonObject {
            put("protocolVersion", "2024-11-05")
            putJsonObject("serverInfo") {
                put("name", "android-mcp-agent")
                put("version", "1.0.0")
            }
            putJsonObject("capabilities") {
                putJsonObject("tools") {
                    put("listChanged", false)
                }
            }
        }
        return JsonRpcResponse(id = request.id, result = result)
    }

    private fun handleToolsList(request: JsonRpcRequest): JsonRpcResponse {
        val tools = buildJsonArray {
            // 1. android_get_screen
            add(buildJsonObject {
                put("name", "android_get_screen")
                put("description", "Inspects current Android screen and returns normalized UI hierarchy tree with element IDs, classes, bounds, and interactive flags.")
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {}
                }
            })

            // 2. android_take_screenshot
            add(buildJsonObject {
                put("name", "android_take_screenshot")
                put("description", "Captures an instantaneous full-screen screenshot of the Android device returning base64 JPEG image.")
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {}
                }
            })

            // 3. android_execute_action
            add(buildJsonObject {
                put("name", "android_execute_action")
                put("description", "Executes an action directly on the device. Supported types: CLICK, LONG_CLICK, TYPE, CLEAR_TEXT, SCROLL, SWIPE, OPEN_APP, KEY_EVENT, WAIT.")
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("action") {
                            put("type", "object")
                            put("description", "The AgentAction JSON object (e.g. {\"type\": \"CLICK\", \"selector\": {\"text\": \"Settings\"}} or {\"type\": \"OPEN_APP\", \"packageName\": \"com.android.settings\"})")
                        }
                    }
                    putJsonArray("required") {
                        add("action")
                    }
                }
            })

            // 4. android_run_goal
            add(buildJsonObject {
                put("name", "android_run_goal")
                put("description", "Triggers the Autonomous ReAct Agent to accomplish a high-level goal across multiple steps (e.g. 'Open Settings and scroll down').")
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("goal") {
                            put("type", "string")
                            put("description", "The user's high-level instruction/goal.")
                        }
                    }
                    putJsonArray("required") {
                        add("goal")
                    }
                }
            })

            // 5. android_locate_element
            add(buildJsonObject {
                put("name", "android_locate_element")
                put("description", "Uses Multimodal Vision AI to locate an element on the physical device screen and return its pixel center coordinates (x, y). Useful when elements are missing from the accessibility tree (e.g. Flutter, Canvas, WebViews).")
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("description") {
                            put("type", "string")
                            put("description", "Visual description or label of the UI element to locate (e.g. 'Refresh button', 'Blue shopping cart icon').")
                        }
                    }
                    putJsonArray("required") {
                        add("description")
                    }
                }
            })
        }

        return JsonRpcResponse(id = request.id, result = buildJsonObject { put("tools", tools) })
    }

    private suspend fun handleToolCall(request: JsonRpcRequest): JsonRpcResponse {
        val params = request.params ?: return JsonRpcResponse(
            id = request.id,
            error = JsonRpcError(-32602, "Invalid params: missing params object")
        )

        val toolName = params["name"]?.jsonPrimitive?.content ?: return JsonRpcResponse(
            id = request.id,
            error = JsonRpcError(-32602, "Missing tool name")
        )

        val arguments = params["arguments"]?.jsonObject ?: buildJsonObject {}

        val service = AgentAccessibilityService.getInstance()
        val isNetworkTool = toolName.startsWith("network_") || toolName.startsWith("android_network_")
        if (service == null && toolName != "android_run_goal" && !isNetworkTool) {
            return JsonRpcResponse(
                id = request.id,
                result = buildJsonObject {
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", "Error: AgentAccessibilityService is not connected on the device. Enable it in Android Settings.")
                        })
                    }
                    put("isError", true)
                }
            )
        }

        return when (toolName) {
            "android_get_screen" -> {
                val screen = service!!.refreshScreenState()
                val jsonTree = json.encodeToString(ScreenState.serializer(), screen)
                JsonRpcResponse(
                    id = request.id,
                    result = buildJsonObject {
                        putJsonArray("content") {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", jsonTree)
                            })
                        }
                    }
                )
            }

            "android_take_screenshot" -> {
                val actionResult = ActionExecutor.execute(AgentAction.Screenshot, context)
                if (actionResult.success && actionResult.data != null) {
                    JsonRpcResponse(
                        id = request.id,
                        result = buildJsonObject {
                            putJsonArray("content") {
                                add(buildJsonObject {
                                    put("type", "image")
                                    put("data", actionResult.data)
                                    put("mimeType", "image/jpeg")
                                })
                            }
                        }
                    )
                } else {
                    JsonRpcResponse(
                        id = request.id,
                        result = buildJsonObject {
                            putJsonArray("content") {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", "Failed to capture screenshot: ${actionResult.message}")
                                })
                            }
                            put("isError", true)
                        }
                    )
                }
            }

            "android_execute_action" -> {
                val actionObj = arguments["action"]?.jsonObject
                    ?: return JsonRpcResponse(
                        id = request.id,
                        error = JsonRpcError(-32602, "Missing action object in arguments")
                    )

                val parseResult = ActionValidator.parseAction(actionObj.toString())
                if (parseResult.isFailure) {
                    return JsonRpcResponse(
                        id = request.id,
                        result = buildJsonObject {
                            putJsonArray("content") {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", "Invalid action format: ${parseResult.exceptionOrNull()?.message}")
                                })
                            }
                            put("isError", true)
                        }
                    )
                }

                val actionResult = ActionExecutor.execute(parseResult.getOrThrow(), context)
                JsonRpcResponse(
                    id = request.id,
                    result = buildJsonObject {
                        putJsonArray("content") {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", "Action ${actionResult.actionType}: ${actionResult.message} (${actionResult.executionTimeMs}ms)")
                            })
                        }
                        put("isError", !actionResult.success)
                    }
                )
            }

            "android_run_goal" -> {
                val goal = arguments["goal"]?.jsonPrimitive?.content
                    ?: return JsonRpcResponse(
                        id = request.id,
                        error = JsonRpcError(-32602, "Missing goal string in arguments")
                    )

                val config = AiConfigRepository.loadConfig(context)
                if (!config.isConfigured) {
                    return JsonRpcResponse(
                        id = request.id,
                        result = buildJsonObject {
                            putJsonArray("content") {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", "Error: AI API key is not configured on device. Please open the Android Agent app and configure it.")
                                })
                            }
                            put("isError", true)
                        }
                    )
                }

                val agent = AutonomousAgent(context, config)
                val deferred = CompletableDeferred<Pair<Boolean, String>>()
                agent.startGoal(goal, scope) { success, msg ->
                    deferred.complete(Pair(success, msg))
                }

                val (success, summary) = deferred.await()
                JsonRpcResponse(
                    id = request.id,
                    result = buildJsonObject {
                        putJsonArray("content") {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", summary)
                            })
                        }
                        put("isError", !success)
                    }
                )
            }

            "android_locate_element" -> {
                val desc = arguments["description"]?.jsonPrimitive?.content
                    ?: return JsonRpcResponse(id = request.id, error = JsonRpcError(-32602, "Missing 'description' parameter"))

                val (w, h) = ActionExecutor.getScreenDimensions(context)
                val aiConfig = AiConfigRepository.loadConfig(context)
                val screenshot = service!!.takeScreenshotCompat()
                if (screenshot == null) {
                    return JsonRpcResponse(
                        id = request.id,
                        result = buildJsonObject {
                            putJsonArray("content") {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", "Error: Failed to capture device screenshot for vision localization.")
                                })
                            }
                            put("isError", true)
                        }
                    )
                }

                val result = com.agent.androidmcp.ai.VisionLocator.locate(
                    screenshot = screenshot,
                    targetDescription = desc,
                    screenWidth = w,
                    screenHeight = h,
                    config = aiConfig
                )

                val resultJson = buildJsonObject {
                    put("found", result.found)
                    if (result.found && result.x != null && result.y != null) {
                        put("x", result.x)
                        put("y", result.y)
                        put("confidence", result.confidence)
                        put("label", result.label ?: desc)
                    } else {
                        put("reason", result.reason ?: "Element not located by vision model")
                    }
                }

                JsonRpcResponse(
                    id = request.id,
                    result = buildJsonObject {
                        putJsonArray("content") {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", resultJson.toString())
                            })
                        }
                        put("isError", !result.found)
                    }
                )
            }

            "network_start_monitor", "android_network_start_monitor" -> {
                val testId = arguments["test_id"]?.jsonPrimitive?.contentOrNull
                val isPrepared = McpVpnMonitorService.isVpnPrepared(context)
                if (!isPrepared) {
                    JsonRpcResponse(
                        id = request.id,
                        result = buildJsonObject {
                            putJsonArray("content") {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", """{"actionRequired":"VPN_PERMISSION_REQUIRED","message":"Android VPN permission is required to observe network traffic. Please approve the VPN dialog in the app on your phone."}""")
                                })
                            }
                            put("isError", true)
                        }
                    )
                } else {
                    McpVpnMonitorService.start(context, testId)
                    JsonRpcResponse(
                        id = request.id,
                        result = buildJsonObject {
                            putJsonArray("content") {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", """{"success":true,"status":"MONITORING","testId":"${testId ?: "default"}","message":"Network observation active."}""")
                                })
                            }
                            put("isError", false)
                        }
                    )
                }
            }

            "network_stop_monitor", "android_network_stop_monitor" -> {
                McpVpnMonitorService.stop(context)
                val summary = NetworkFlowTracker.stopCapture()
                val summaryJson = buildJsonObject {
                    put("success", true)
                    put("status", "STOPPED")
                    put("totalRequests", (summary["totalRequests"] as? Int) ?: 0)
                    put("failedRequests", (summary["failedRequests"] as? Int) ?: 0)
                    put("http5xxErrors", (summary["http5xxErrors"] as? Int) ?: 0)
                    put("totalBytesSent", (summary["totalBytesSent"] as? Long) ?: 0L)
                    put("totalBytesReceived", (summary["totalBytesReceived"] as? Long) ?: 0L)
                    put("avgDurationMs", (summary["avgDurationMs"] as? Long) ?: 0L)
                }
                JsonRpcResponse(
                    id = request.id,
                    result = buildJsonObject {
                        putJsonArray("content") {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", summaryJson.toString())
                            })
                        }
                        put("isError", false)
                    }
                )
            }

            "network_get_events", "android_network_get_events" -> {
                val testId = arguments["test_id"]?.jsonPrimitive?.contentOrNull
                val host = arguments["host"]?.jsonPrimitive?.contentOrNull
                val limit = arguments["limit"]?.jsonPrimitive?.intOrNull ?: 50
                val events = NetworkFlowTracker.getEvents(testId, host, limit)
                val eventsJson = buildJsonObject {
                    put("count", events.size)
                    put("testId", testId ?: "all")
                    putJsonArray("events") {
                        events.forEach { e ->
                            add(buildJsonObject {
                                put("eventId", e.eventId)
                                put("timestamp", e.timestamp)
                                put("protocol", e.protocol)
                                put("host", e.host)
                                put("port", e.port)
                                if (e.method != null) put("method", e.method)
                                if (e.path != null) put("path", e.path)
                                if (e.statusCode != null) put("statusCode", e.statusCode)
                                put("durationMs", e.durationMs)
                                put("bytesSent", e.bytesSent)
                                put("bytesReceived", e.bytesReceived)
                                put("status", e.status)
                                if (e.error != null) put("error", e.error)
                            })
                        }
                    }
                }
                JsonRpcResponse(
                    id = request.id,
                    result = buildJsonObject {
                        putJsonArray("content") {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", eventsJson.toString())
                            })
                        }
                        put("isError", false)
                    }
                )
            }

            "network_get_connections", "android_network_get_connections" -> {
                val active = NetworkFlowTracker.getActiveConnections()
                val connectionsJson = buildJsonObject {
                    put("activeCount", active.size)
                    putJsonArray("connections") {
                        active.forEach { e ->
                            add(buildJsonObject {
                                put("host", e.host)
                                put("port", e.port)
                                put("protocol", e.protocol)
                                put("bytesSent", e.bytesSent)
                                put("durationMs", e.durationMs)
                            })
                        }
                    }
                }
                JsonRpcResponse(
                    id = request.id,
                    result = buildJsonObject {
                        putJsonArray("content") {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", connectionsJson.toString())
                            })
                        }
                        put("isError", false)
                    }
                )
            }

            "network_assert_traffic", "android_network_assert_traffic" -> {
                val testId = arguments["test_id"]?.jsonPrimitive?.contentOrNull
                val expectedHost = arguments["expected_host"]?.jsonPrimitive?.contentOrNull
                val maxFailed = arguments["max_failed_requests"]?.jsonPrimitive?.intOrNull ?: 0
                val events = NetworkFlowTracker.getEvents(testId, expectedHost, 500)

                val hostSeen = expectedHost == null || events.any { it.host.contains(expectedHost, ignoreCase = true) }
                val failedCount = events.count { it.status == "FAILED" || (it.statusCode != null && it.statusCode >= 400) }
                val passed = hostSeen && failedCount <= maxFailed

                val assertResult = buildJsonObject {
                    put("passed", passed)
                    put("testId", testId ?: "default")
                    put("matchedRequests", events.size)
                    put("failedRequests", failedCount)
                    if (expectedHost != null) {
                        put("expectedHost", expectedHost)
                        put("expectedHostSeen", hostSeen)
                    }
                    put("failureReason", if (!passed) {
                        if (!hostSeen) "Expected host '$expectedHost' was not contacted"
                        else "Failed requests ($failedCount) exceeded threshold ($maxFailed)"
                    } else "All network assertions passed.")
                }

                JsonRpcResponse(
                    id = request.id,
                    result = buildJsonObject {
                        putJsonArray("content") {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", assertResult.toString())
                            })
                        }
                        put("isError", !passed)
                    }
                )
            }

            else -> JsonRpcResponse(
                id = request.id,
                error = JsonRpcError(-32601, "Tool not recognized: $toolName")
            )
        }
    }
}
