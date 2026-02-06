package com.secondsense.access

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.time.Instant

class ScreenContextProvider(
    private val service: AccessibilityService,
    private val maxNodes: Int = 40,
    private val maxTextLength: Int = 80
) {

    fun capture(): ScreenContext? {
        val root = service.rootInActiveWindow ?: return null
        return buildContext(root)
    }

    private fun buildContext(root: AccessibilityNodeInfo): ScreenContext {
        val currentPackage = root.packageName?.toString()
        val nodes = mutableListOf<NodeInfo>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        var nodeIdCounter = 1

        stack.add(root)

        while (stack.isNotEmpty() && nodes.size < maxNodes) {
            val node = stack.removeLast()
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let(stack::addLast)
            }

            if (!shouldInclude(node)) continue

            nodes += NodeInfo(
                id = "n${nodeIdCounter++}",
                text = node.text?.toString()?.normalizedText(),
                contentDesc = node.contentDescription?.toString()?.normalizedText(),
                viewId = node.viewIdResourceName?.normalizedText(),
                className = node.className?.toString(),
                clickable = node.isClickable,
                editable = node.isEditable,
                enabled = node.isEnabled,
                focusable = node.isFocusable,
                bounds = node.bounds()
            )
        }

        return ScreenContext(
            currentAppPackage = currentPackage,
            timestamp = Instant.now().toString(),
            nodes = nodes
        )
    }

    private fun shouldInclude(node: AccessibilityNodeInfo): Boolean {
        val hasText = !node.text.isNullOrBlank()
        val hasDesc = !node.contentDescription.isNullOrBlank()
        return node.isClickable || node.isEditable || hasText || hasDesc
    }

    private fun AccessibilityNodeInfo.bounds(): Bounds {
        val rect = Rect()
        getBoundsInScreen(rect)
        return Bounds(
            left = rect.left,
            top = rect.top,
            right = rect.right,
            bottom = rect.bottom
        )
    }

    private fun String.normalizedText(): String? {
        val compact = trim().replace(Regex("\\s+"), " ")
        if (compact.isEmpty()) return null
        return compact.take(maxTextLength)
    }
}
