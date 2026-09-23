package com.agent.androidmcp.model

import kotlinx.serialization.Serializable

@Serializable
data class ScreenState(
    val packageName: String = "",
    val windowTitle: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val elements: List<UiElement> = emptyList()
) {
    val elementCount: Int get() = elements.size
    val interactiveCount: Int get() = elements.count { it.isClickable || it.isEditable }
}
