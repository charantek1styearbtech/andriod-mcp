package com.agent.androidmcp.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.agent.androidmcp.model.RectBounds
import com.agent.androidmcp.model.ScreenState
import com.agent.androidmcp.model.UiElement

object AccessibilityNodeHelper {

    /**
     * Traverses the AccessibilityNodeInfo tree and extracts a normalized list of UiElements.
     */
    fun extractScreenState(
        rootNode: AccessibilityNodeInfo?,
        defaultPackage: String = ""
    ): ScreenState {
        if (rootNode == null) {
            return ScreenState(packageName = defaultPackage)
        }

        val packageName = rootNode.packageName?.toString() ?: defaultPackage
        val windowTitle = rootNode.paneTitle?.toString() ?: ""
        val elements = mutableListOf<UiElement>()
        var counter = 0

        fun traverse(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null) return

            val isVisible = node.isVisibleToUser
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val viewId = node.viewIdResourceName
            val isClickable = node.isClickable
            val isEditable = node.isEditable
            val isScrollable = node.isScrollable
            val isCheckable = node.isCheckable
            val isChecked = node.isChecked
            val isEnabled = node.isEnabled
            val isFocused = node.isFocused

            val hasContent = text.isNotEmpty() || desc.isNotEmpty() || !viewId.isNullOrEmpty()
            val isInteractive = isClickable || isEditable || isScrollable || isCheckable

            // Filter out empty, non-interactive layout containers to keep tree clean for AI
            if (isVisible && (hasContent || isInteractive)) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)

                // Only include elements with valid positive dimensions
                if (bounds.width() > 0 && bounds.height() > 0) {
                    val elementId = "e_${counter++}"
                    elements.add(
                        UiElement(
                            id = elementId,
                            text = text,
                            contentDescription = desc,
                            className = node.className?.toString() ?: "",
                            packageName = node.packageName?.toString() ?: packageName,
                            viewIdResourceName = viewId,
                            bounds = RectBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
                            isClickable = isClickable,
                            isEditable = isEditable,
                            isScrollable = isScrollable,
                            isCheckable = isCheckable,
                            isChecked = isChecked,
                            isEnabled = isEnabled,
                            isFocused = isFocused,
                            isVisibleToUser = isVisible,
                            depth = depth
                        )
                    )
                }
            }

            // Recurse into children (max depth 20 for fast scanning)
            if (depth < 20 && elements.size < 120) {
                val childCount = node.childCount
                for (i in 0 until childCount) {
                    val child = try {
                        node.getChild(i)
                    } catch (e: Exception) {
                        null
                    }
                    if (child != null) {
                        traverse(child, depth + 1)
                    }
                }
            }
        }

        traverse(rootNode, 0)

        return ScreenState(
            packageName = packageName,
            windowTitle = windowTitle,
            elements = elements
        )
    }

    /**
     * Recursively searches for an element matching a given text or content description.
     */
    fun findNodeByText(rootNode: AccessibilityNodeInfo?, targetText: String): AccessibilityNodeInfo? {
        if (rootNode == null || targetText.isBlank()) return null

        val nodeText = rootNode.text?.toString()?.trim() ?: ""
        val nodeDesc = rootNode.contentDescription?.toString()?.trim() ?: ""

        if (nodeText.contains(targetText, ignoreCase = true) ||
            nodeDesc.contains(targetText, ignoreCase = true)
        ) {
            return findClickableParentOrSelf(rootNode)
        }

        for (i in 0 until rootNode.childCount) {
            val child = try {
                rootNode.getChild(i)
            } catch (e: Exception) {
                null
            }
            val match = findNodeByText(child, targetText)
            if (match != null) return match
        }

        return null
    }

    /**
     * Searches for a node matching the target viewId or text and bounds of a UiElement.
     */
    fun findNodeByUiElement(rootNode: AccessibilityNodeInfo?, element: UiElement): AccessibilityNodeInfo? {
        if (rootNode == null) return null

        val bounds = Rect()
        rootNode.getBoundsInScreen(bounds)

        val sameBounds = bounds.left == element.bounds.left &&
                bounds.top == element.bounds.top &&
                bounds.right == element.bounds.right &&
                bounds.bottom == element.bounds.bottom

        val sameText = (rootNode.text?.toString()?.trim() ?: "") == element.text
        val sameDesc = (rootNode.contentDescription?.toString()?.trim() ?: "") == element.contentDescription
        val sameViewId = rootNode.viewIdResourceName == element.viewIdResourceName

        if (sameBounds && (sameText || sameDesc || sameViewId)) {
            return findClickableParentOrSelf(rootNode)
        }

        for (i in 0 until rootNode.childCount) {
            val child = try {
                rootNode.getChild(i)
            } catch (e: Exception) {
                null
            }
            val match = findNodeByUiElement(child, element)
            if (match != null) return match
        }

        return null
    }

    /**
     * Finds the nearest clickable ancestor if the current node itself is not clickable.
     */
    fun findClickableParentOrSelf(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        if (node.isClickable) return node

        var current: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (current != null && depth < 4) {
            if (current.isClickable) {
                return current
            }
            current = current.parent
            depth++
        }
        return node
    }
}
