package com.agent.androidmcp.server

import android.content.Context
import android.os.Build
import android.util.Log
import com.agent.androidmcp.accessibility.AgentAccessibilityService
import com.agent.androidmcp.action.ActionExecutor
import com.agent.androidmcp.action.ActionValidator
import com.agent.androidmcp.action.AgentAction
import com.agent.androidmcp.agent.AutonomousAgent
import com.agent.androidmcp.ai.AiConfigRepository
import com.agent.androidmcp.model.ScreenState
import com.agent.androidmcp.server.mcp.JsonRpcRequest
import com.agent.androidmcp.server.mcp.McpHandler
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class AndroidServer(private val appContext: Context, private val port: Int = 8080) {

    private var serverEngine: ApplicationEngine? = null
    private val mcpHandler = McpHandler(appContext)
    private val activeConnections = AtomicInteger(0)
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "AndroidServer"
        private var instance: AndroidServer? = null

        fun getInstance(context: Context, port: Int = 8080): AndroidServer {
            return instance ?: synchronized(this) {
                instance ?: AndroidServer(context.applicationContext, port).also { instance = it }
            }
        }
    }

    fun start() {
        if (serverEngine != null) {
            Log.w(TAG, "Server already running")
            return
        }

        try {
            serverEngine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
                install(ContentNegotiation) {
                    json(ActionValidator.jsonParser)
                }

                install(CORS) {
                    anyHost()
                    allowHeader(HttpHeaders.ContentType)
                    allowHeader(HttpHeaders.Authorization)
                    allowMethod(HttpMethod.Options)
                    allowMethod(HttpMethod.Put)
                    allowMethod(HttpMethod.Patch)
                    allowMethod(HttpMethod.Delete)
                }

                install(WebSockets) {
                    pingPeriod = Duration.ofSeconds(15)
                    timeout = Duration.ofSeconds(30)
                    maxFrameSize = Long.MAX_VALUE
                    masking = false
                }

                routing {
                    // 1. Health check
                    get("/health") {
                        ServerState.incrementRequests("GET /health")
                        val serviceConnected = AgentAccessibilityService.isConnected()
                        call.respond(
                            HttpStatusCode.OK,
                            buildJsonObject {
                                put("status", "UP")
                                put("device", Build.MODEL)
                                put("manufacturer", Build.MANUFACTURER)
                                put("androidVersion", Build.VERSION.RELEASE)
                                put("accessibilityConnected", serviceConnected)
                            }
                        )
                    }

                    // 2. Screen inspection REST
                    get("/api/v1/screen") {
                        ServerState.incrementRequests("GET /api/v1/screen")
                        val service = AgentAccessibilityService.getInstance()
                        if (service == null) {
                            call.respond(HttpStatusCode.ServiceUnavailable, "Accessibility Service not active on device")
                            return@get
                        }
                        val screen = service.refreshScreenState()
                        call.respond(HttpStatusCode.OK, screen)
                    }

                    // 3. Screenshot REST
                    get("/api/v1/screenshot") {
                        ServerState.incrementRequests("GET /api/v1/screenshot")
                        val result = ActionExecutor.execute(AgentAction.Screenshot, appContext)
                        if (result.success && result.data != null) {
                            call.respond(HttpStatusCode.OK, buildJsonObject {
                                put("success", true)
                                put("data", result.data)
                                put("mimeType", "image/jpeg")
                            })
                        } else {
                            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                                put("success", false)
                                put("error", result.message)
                            })
                        }
                    }

                    // 4. Action execution REST
                    post("/api/v1/action") {
                        ServerState.incrementRequests("POST /api/v1/action")
                        val action = try {
                            call.receive<AgentAction>()
                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.BadRequest, "Invalid AgentAction body: ${e.message}")
                            return@post
                        }

                        val result = ActionExecutor.execute(action, appContext)
                        call.respond(HttpStatusCode.OK, result)
                    }

                    // 5. Autonomous Goal REST
                    post("/api/v1/goal") {
                        ServerState.incrementRequests("POST /api/v1/goal")
                        val body = try {
                            call.receive<Map<String, String>>()
                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.BadRequest, "Expected JSON { \"goal\": \"...\" }")
                            return@post
                        }

                        val goal = body["goal"]
                        if (goal.isNullOrBlank()) {
                            call.respond(HttpStatusCode.BadRequest, "Missing 'goal' parameter")
                            return@post
                        }

                        val config = AiConfigRepository.loadConfig(appContext)
                        if (!config.isConfigured) {
                            call.respond(HttpStatusCode.PreconditionFailed, "AI API Key not configured on device")
                            return@post
                        }

                        val agent = AutonomousAgent(appContext, config)
                        val deferred = CompletableDeferred<Pair<Boolean, String>>()
                        agent.startGoal(goal, serverScope) { success, msg ->
                            deferred.complete(Pair(success, msg))
                        }

                        val (success, summary) = deferred.await()
                        call.respond(
                            HttpStatusCode.OK,
                            buildJsonObject {
                                put("success", success)
                                put("summary", summary)
                            }
                        )
                    }

                    // 6. MCP Protocol JSON-RPC 2.0 Endpoint
                    post("/mcp") {
                        ServerState.incrementRequests("POST /mcp")
                        val rpcRequest = try {
                            call.receive<JsonRpcRequest>()
                        } catch (e: Exception) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                buildJsonObject {
                                    put("jsonrpc", "2.0")
                                    put("error", buildJsonObject {
                                        put("code", -32700)
                                        put("message", "Parse error: ${e.message}")
                                    })
                                }
                            )
                            return@post
                        }

                        val rpcResponse = mcpHandler.handleRequest(rpcRequest)
                        call.respond(HttpStatusCode.OK, rpcResponse)
                    }

                    // 7. WebSocket streaming endpoint
                    webSocket("/ws/agent") {
                        val connectionId = activeConnections.incrementAndGet()
                        ServerState.updateConnections(activeConnections.get())
                        ServerState.setLog("WebSocket client #$connectionId connected")
                        Log.i(TAG, "WebSocket client #$connectionId connected")

                        try {
                            // Send welcome screen
                            val service = AgentAccessibilityService.getInstance()
                            if (service != null) {
                                val initialScreen = service.refreshScreenState()
                                send(ActionValidator.jsonParser.encodeToString(ScreenState.serializer(), initialScreen))
                            }

                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    val text = frame.readText()
                                    // Parse as action
                                    val parseResult = ActionValidator.parseAction(text)
                                    if (parseResult.isSuccess) {
                                        val result = ActionExecutor.execute(parseResult.getOrThrow(), appContext)
                                        send(ActionValidator.jsonParser.encodeToString(com.agent.androidmcp.action.ActionResult.serializer(), result))
                                    } else {
                                        send("""{"error": "Failed to parse action JSON"}""")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "WebSocket error client #$connectionId", e)
                        } finally {
                            activeConnections.decrementAndGet()
                            ServerState.updateConnections(activeConnections.get())
                            ServerState.setLog("WebSocket client #$connectionId disconnected")
                        }
                    }
                }
            }.start(wait = false)

            ServerState.updateRunning(true, port)
            Log.i(TAG, "Embedded Ktor server started on 0.0.0.0:$port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start embedded server", e)
            ServerState.updateRunning(false, port)
            ServerState.setLog("Error: ${e.message}")
        }
    }

    fun stop() {
        try {
            serverEngine?.stop(1000, 2000)
            serverEngine = null
            ServerState.updateRunning(false, port)
            Log.i(TAG, "Embedded Ktor server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
    }
}
