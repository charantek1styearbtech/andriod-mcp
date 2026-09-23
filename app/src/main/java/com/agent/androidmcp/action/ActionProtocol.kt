package com.agent.androidmcp.action

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ElementSelector(
    val id: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val viewId: String? = null,
    val className: String? = null
)

@Serializable
enum class ScrollDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT
}

@Serializable
enum class SystemKey {
    BACK,
    HOME,
    RECENTS,
    NOTIFICATIONS,
    QUICK_SETTINGS
}

@Serializable
sealed interface AgentAction {

    @Serializable
    @SerialName("OPEN_APP")
    data class OpenApp(val packageName: String) : AgentAction

    @Serializable
    @SerialName("CLICK")
    data class Click(
        val selector: ElementSelector? = null,
        val x: Int? = null,
        val y: Int? = null
    ) : AgentAction

    @Serializable
    @SerialName("LONG_CLICK")
    data class LongClick(
        val selector: ElementSelector? = null,
        val x: Int? = null,
        val y: Int? = null,
        val durationMs: Long? = 800L
    ) : AgentAction

    @Serializable
    @SerialName("TYPE")
    data class Type(
        val text: String,
        val selector: ElementSelector? = null,
        val clearFirst: Boolean = false
    ) : AgentAction

    @Serializable
    @SerialName("CLEAR_TEXT")
    data class ClearText(
        val selector: ElementSelector? = null
    ) : AgentAction

    @Serializable
    @SerialName("SCROLL")
    data class Scroll(
        val direction: ScrollDirection = ScrollDirection.DOWN,
        val selector: ElementSelector? = null
    ) : AgentAction

    @Serializable
    @SerialName("SWIPE")
    data class Swipe(
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int,
        val durationMs: Long = 300L
    ) : AgentAction

    @Serializable
    @SerialName("KEY_EVENT")
    data class KeyEvent(
        val key: SystemKey
    ) : AgentAction

    @Serializable
    @SerialName("WAIT")
    data class Wait(
        val ms: Long = 1000L
    ) : AgentAction

    @Serializable
    @SerialName("GET_UI")
    data object GetUi : AgentAction

    @Serializable
    @SerialName("SCREENSHOT")
    data object Screenshot : AgentAction
}

@Serializable
data class ActionResult(
    val success: Boolean,
    val actionType: String,
    val message: String,
    val data: String? = null,
    val executionTimeMs: Long = 0L
)
