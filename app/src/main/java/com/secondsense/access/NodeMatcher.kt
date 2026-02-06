package com.secondsense.access

import android.view.accessibility.AccessibilityNodeInfo
import com.secondsense.agent.Selector

class NodeMatcher {

    fun findBestMatch(
        root: AccessibilityNodeInfo,
        selector: Selector
    ): MatchResult? {
        if (!selector.hasAnySignal()) return null

        val candidates = mutableListOf<MatchCandidate>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        var includedNodeCounter = 0

        stack.add(root)

        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let(stack::addLast)
            }

            val generatedId = if (shouldIncludeInContext(node)) "n${++includedNodeCounter}" else null
            val score = scoreMatch(node, selector, generatedId) ?: continue
            val actionableNode = findClickableSelfOrAncestor(node) ?: continue

            candidates += MatchCandidate(
                node = actionableNode,
                score = score,
                matchedNodeId = generatedId,
                usedParentFallback = actionableNode != node
            )
        }

        return candidates
            .maxWithOrNull(
                compareBy<MatchCandidate> { it.score }
                    .thenBy { if (it.usedParentFallback) 0 else 1 }
            )
            ?.toMatchResult()
    }

    private fun scoreMatch(
        node: AccessibilityNodeInfo,
        selector: Selector,
        generatedId: String?
    ): Int? {
        var score = 0

        selector.nodeId?.let { targetId ->
            if (generatedId != targetId) return null
            score += 100
        }

        selector.textContains?.let { textPart ->
            val text = node.text?.toString() ?: return null
            if (!text.contains(textPart, ignoreCase = true)) return null
            score += 40
        }

        selector.contentDescContains?.let { descPart ->
            val desc = node.contentDescription?.toString() ?: return null
            if (!desc.contains(descPart, ignoreCase = true)) return null
            score += 35
        }

        selector.viewIdContains?.let { idPart ->
            val viewId = node.viewIdResourceName ?: return null
            if (!viewId.contains(idPart, ignoreCase = true)) return null
            score += 30
        }

        return if (score > 0) score else null
    }

    private fun findClickableSelfOrAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEnabled && node.isClickable) return node

        var currentParent = node.parent
        var depth = 0
        while (currentParent != null && depth < MAX_PARENT_DEPTH) {
            if (currentParent.isEnabled && currentParent.isClickable) {
                return currentParent
            }
            currentParent = currentParent.parent
            depth += 1
        }
        return null
    }

    private fun shouldIncludeInContext(node: AccessibilityNodeInfo): Boolean {
        val hasText = !node.text.isNullOrBlank()
        val hasDesc = !node.contentDescription.isNullOrBlank()
        return node.isClickable || node.isEditable || hasText || hasDesc
    }

    private fun Selector.hasAnySignal(): Boolean {
        return !nodeId.isNullOrBlank() ||
            !textContains.isNullOrBlank() ||
            !contentDescContains.isNullOrBlank() ||
            !viewIdContains.isNullOrBlank()
    }

    private data class MatchCandidate(
        val node: AccessibilityNodeInfo,
        val score: Int,
        val matchedNodeId: String?,
        val usedParentFallback: Boolean
    ) {
        fun toMatchResult(): MatchResult {
            return MatchResult(
                node = node,
                matchedNodeId = matchedNodeId,
                usedParentFallback = usedParentFallback
            )
        }
    }

    companion object {
        private const val MAX_PARENT_DEPTH = 8
    }
}

data class MatchResult(
    val node: AccessibilityNodeInfo,
    val matchedNodeId: String?,
    val usedParentFallback: Boolean
)
