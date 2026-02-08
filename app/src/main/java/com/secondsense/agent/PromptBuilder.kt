package com.secondsense.agent

import com.secondsense.access.NodeInfo
import com.secondsense.access.ScreenContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PromptBuilder(
    private val maxNodesInPrompt: Int = 12,
    private val maxFeedbackItems: Int = 2,
    private val maxRecentActionsInPrompt: Int = 4
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
        lastError: String?,
        recentActions: List<String> = emptyList()
    ): String {
        val compactContext = toCompactContext(screenContext)
        val contextJson = json.encodeToString(compactContext)
        val feedbackText = recentFeedback.takeLast(maxFeedbackItems).joinToString("\n")
        val recentActionText = recentActions
            .takeLast(maxRecentActionsInPrompt)
            .asReversed()
            .joinToString("\n")
        val systemPrompt = SYSTEM_TEMPLATE
            .replace(SCHEMA_PLACEHOLDER, AGENT_RESPONSE_SCHEMA)
            .trimIndent()
        val userPrompt = """
            Goal: ${goal.trim()}
            Step: $step

            Current compact screen context (JSON):
            $contextJson

            Recent execution feedback:
            ${if (feedbackText.isBlank()) "none" else feedbackText}

            Recent action trace (latest first):
            ${if (recentActionText.isBlank()) "none" else recentActionText}

            Last planner/execution error:
            ${lastError ?: "none"}

            Retry rule:
            ${if (lastError == null) "none" else "Previous output was invalid. Retry from CURRENT context and return one valid JSON object with exactly one action."}

            /no_think
        """.trimIndent()

        return """
            <bos><start_of_turn>system
            $systemPrompt
            <end_of_turn>
            <start_of_turn>user
            $userPrompt
            <end_of_turn>
            <start_of_turn>model
        """.trimIndent()
    }

    private fun toCompactContext(screenContext: ScreenContext?): PromptScreenContext {
        if (screenContext == null) {
            return PromptScreenContext(
                currentAppPackage = null,
                nodes = emptyList()
            )
        }

        return PromptScreenContext(
            currentAppPackage = screenContext.currentAppPackage,
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
            editable = editable
        )
    }

    companion object {
        private const val SCHEMA_PLACEHOLDER = "{{ACTION_SCHEMA}}"

        private val SYSTEM_TEMPLATE = """
            You are SecondSense, an offline Android UI agent running Gemma-3-1B-IT.
            Return exactly one MINIFIED JSON object only.
            No markdown, no prose, no code fences, no tags, no extra keys.

            Contract:
            $SCHEMA_PLACEHOLDER

            Rules:
            - If status="in_progress", output EXACTLY 1 action.
            - Use open_app when goal names an app and currentAppPackage is different.
            - Use selectors only from current nodes; prefer text/contentDesc/viewId.
            - If uncertain, use status="needs_clarification" with question.
            - Use status="done" only with visible evidence from current context.
            - Output must start with '{' and end with '}'.
            - Never output the character '|'.
            - /no_think

            Minimal examples:
            - If goal mentions Instagram and currentAppPackage is not Instagram:
              {"status":"in_progress","actions":[{"type":"open_app","app":"Instagram"}],"needsUserConfirmation":false}
            - If currentAppPackage is Instagram and a node text is "Messages":
              {"status":"in_progress","actions":[{"type":"click","selector":{"textContains":"Messages"}}],"needsUserConfirmation":false}
        """.trimIndent()

        private val AGENT_RESPONSE_SCHEMA = """
            {"status":"in_progress","actions":[{"type":"open_app","app":"Instagram"}],"question":null,"result":null,"needsUserConfirmation":false,"confirmationPrompt":null}

            status must be one of: in_progress, needs_clarification, done, error
            action.type must be one of: open_app, click, type_text, scroll, back, home, wait
        """.trimIndent()
    }
}

@Serializable
private data class PromptScreenContext(
    val currentAppPackage: String?,
    val nodes: List<PromptNode>
)

@Serializable
private data class PromptNode(
    val id: String,
    val text: String? = null,
    val contentDesc: String? = null,
    val viewId: String? = null,
    val clickable: Boolean,
    val editable: Boolean
)
