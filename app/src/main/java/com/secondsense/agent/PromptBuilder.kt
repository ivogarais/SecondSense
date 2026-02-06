package com.secondsense.agent

import com.secondsense.access.NodeInfo
import com.secondsense.access.ScreenContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PromptBuilder(
    private val maxNodesInPrompt: Int = 28,
    private val maxFeedbackItems: Int = 4
) {

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    fun build(
        goal: String,
        step: Int,
        screenContext: ScreenContext?,
        recentFeedback: List<String>,
        lastError: String?
    ): String {
        val compactContext = toCompactContext(screenContext)
        val contextJson = json.encodeToString(compactContext)
        val feedbackText = recentFeedback.takeLast(maxFeedbackItems).joinToString("\n")

        return """
            You are an Android UI agent.
            Goal: ${goal.trim()}
            Step: $step

            Current compact screen context (JSON):
            $contextJson

            Recent execution feedback:
            ${if (feedbackText.isBlank()) "none" else feedbackText}

            Last planner/execution error:
            ${lastError ?: "none"}

            Return only JSON matching this shape:
            {
              "status": "in_progress|needs_clarification|done|error",
              "actions": [
                {"type":"open_app","app":"..."},
                {"type":"click","selector":{"textContains":"..."}},
                {"type":"type_text","text":"..."},
                {"type":"scroll","direction":"up|down","amount":1},
                {"type":"back"},
                {"type":"home"},
                {"type":"wait","ms":500}
              ],
              "question": "optional",
              "result": "optional",
              "needsUserConfirmation": false,
              "confirmationPrompt": null
            }

            Rules:
            - Output JSON only. No markdown.
            - Use at most 2 actions per response.
            - Prefer selectors using text/contentDesc/viewId from context nodes.
            - If uncertain, return needs_clarification with a question.
            - When task is complete, return status=done with a short result.
        """.trimIndent()
    }

    private fun toCompactContext(screenContext: ScreenContext?): PromptScreenContext {
        if (screenContext == null) {
            return PromptScreenContext(
                currentAppPackage = null,
                timestamp = null,
                nodes = emptyList()
            )
        }

        return PromptScreenContext(
            currentAppPackage = screenContext.currentAppPackage,
            timestamp = screenContext.timestamp,
            nodes = screenContext.nodes
                .asSequence()
                .take(maxNodesInPrompt)
                .map { it.toPromptNode() }
                .toList()
        )
    }

    private fun NodeInfo.toPromptNode(): PromptNode {
        return PromptNode(
            id = id,
            text = text,
            contentDesc = contentDesc,
            viewId = viewId,
            clickable = clickable,
            editable = editable,
            enabled = enabled,
            focusable = focusable
        )
    }
}

@Serializable
private data class PromptScreenContext(
    val currentAppPackage: String?,
    val timestamp: String?,
    val nodes: List<PromptNode>
)

@Serializable
private data class PromptNode(
    val id: String,
    val text: String? = null,
    val contentDesc: String? = null,
    val viewId: String? = null,
    val clickable: Boolean,
    val editable: Boolean,
    val enabled: Boolean,
    val focusable: Boolean
)
