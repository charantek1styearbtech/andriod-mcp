package com.agent.androidmcp.action

import kotlinx.serialization.json.Json

object ActionValidator {

    val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    fun parseAction(jsonString: String): Result<AgentAction> {
        return runCatching {
            jsonParser.decodeFromString<AgentAction>(jsonString)
        }
    }

    fun validate(action: AgentAction, screenWidth: Int = 1080, screenHeight: Int = 2400): Result<Unit> {
        return runCatching {
            when (action) {
                is AgentAction.OpenApp -> {
                    require(action.packageName.isNotBlank()) { "packageName cannot be blank" }
                }
                is AgentAction.Click -> {
                    val hasSelector = action.selector != null
                    val hasCoords = action.x != null && action.y != null
                    require(hasSelector || hasCoords) { "Either selector or (x, y) coordinates must be provided for CLICK" }
                    if (hasCoords) {
                        require(action.x!! in 0..screenWidth && action.y!! in 0..screenHeight) {
                            "Coordinates (${action.x}, ${action.y}) are out of screen bounds ($screenWidth x $screenHeight)"
                        }
                    }
                }
                is AgentAction.LongClick -> {
                    val hasSelector = action.selector != null
                    val hasCoords = action.x != null && action.y != null
                    require(hasSelector || hasCoords) { "Either selector or (x, y) coordinates must be provided for LONG_CLICK" }
                    if (hasCoords) {
                        require(action.x!! in 0..screenWidth && action.y!! in 0..screenHeight) {
                            "Coordinates (${action.x}, ${action.y}) are out of screen bounds ($screenWidth x $screenHeight)"
                        }
                    }
                    action.durationMs?.let {
                        require(it in 100..10000) { "durationMs must be between 100 and 10000 ms" }
                    }
                }
                is AgentAction.Type -> {
                    require(action.text.isNotEmpty()) { "Text to type cannot be empty" }
                }
                is AgentAction.ClearText -> {
                    // Valid without selector (clears focused) or with selector
                }
                is AgentAction.Scroll -> {
                    // Valid with or without selector
                }
                is AgentAction.Swipe -> {
                    require(action.startX in 0..screenWidth && action.startY in 0..screenHeight) {
                        "Start coords (${action.startX}, ${action.startY}) out of bounds ($screenWidth x $screenHeight)"
                    }
                    require(action.endX in 0..screenWidth && action.endY in 0..screenHeight) {
                        "End coords (${action.endX}, ${action.endY}) out of bounds ($screenWidth x $screenHeight)"
                    }
                    require(action.durationMs in 50..10000) { "Swipe durationMs must be between 50 and 10000 ms" }
                }
                is AgentAction.KeyEvent -> {
                    // Enum is self-validating
                }
                is AgentAction.Wait -> {
                    require(action.ms in 50..60000) { "Wait duration must be between 50 and 60000 ms" }
                }
                is AgentAction.GetUi, is AgentAction.Screenshot -> {
                    // Always valid
                }
            }
        }
    }
}
