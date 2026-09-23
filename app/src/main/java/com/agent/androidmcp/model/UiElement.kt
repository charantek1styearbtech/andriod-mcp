package com.agent.androidmcp.model

import kotlinx.serialization.Serializable

@Serializable
data class RectBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2

    override fun toString(): String = "[$left, $top, $right, $bottom]"
}

@Serializable
data class UiElement(
    val id: String,
    val text: String,
    val contentDescription: String,
    val className: String,
    val packageName: String,
    val viewIdResourceName: String?,
    val bounds: RectBounds,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isCheckable: Boolean,
    val isChecked: Boolean,
    val isEnabled: Boolean,
    val isFocused: Boolean,
    val isVisibleToUser: Boolean,
    val depth: Int
) {
    val displayLabel: String
        get() = when {
            text.isNotBlank() -> text
            contentDescription.isNotBlank() -> contentDescription
            !viewIdResourceName.isNullOrBlank() -> viewIdResourceName.substringAfterLast('/')
            else -> className.substringAfterLast('.')
        }

    val simplifiedType: String
        get() = when {
            className.contains("Button", ignoreCase = true) -> "button"
            className.contains("EditText", ignoreCase = true) -> "input"
            className.contains("TextView", ignoreCase = true) -> "text"
            className.contains("ImageView", ignoreCase = true) -> "image"
            className.contains("CheckBox", ignoreCase = true) -> "checkbox"
            className.contains("Switch", ignoreCase = true) -> "switch"
            className.contains("RecyclerView", ignoreCase = true) || className.contains("ScrollView", ignoreCase = true) -> "list"
            else -> className.substringAfterLast('.')
        }
}
