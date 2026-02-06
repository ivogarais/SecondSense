package com.secondsense.agent

data class ValidationIssue(
    val field: String,
    val message: String
)

object AgentResponseValidator {

    private const val MAX_ACTIONS_PER_STEP = 3
    private const val MAX_TYPED_TEXT_LENGTH = 500
    private const val MAX_WAIT_MS = 3000
    private const val MAX_APP_NAME_LENGTH = 80

    fun validate(response: AgentResponse): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        when (response.status) {
            Status.IN_PROGRESS -> {
                if (response.actions.isEmpty()) {
                    issues += ValidationIssue("actions", "Must include at least one action when status is in_progress.")
                }
            }
            Status.NEEDS_CLARIFICATION -> {
                if (response.question.isNullOrBlank()) {
                    issues += ValidationIssue("question", "Must include a non-empty question when status is needs_clarification.")
                }
            }
            Status.DONE -> {
                if (response.result.isNullOrBlank()) {
                    issues += ValidationIssue("result", "Must include a non-empty result when status is done.")
                }
            }
            Status.ERROR -> Unit
        }

        if (response.actions.size > MAX_ACTIONS_PER_STEP) {
            issues += ValidationIssue("actions", "Too many actions. Max allowed per step is $MAX_ACTIONS_PER_STEP.")
        }

        if (response.needsUserConfirmation && response.confirmationPrompt.isNullOrBlank()) {
            issues += ValidationIssue(
                field = "confirmationPrompt",
                message = "Must include confirmationPrompt when needsUserConfirmation is true."
            )
        }

        response.actions.forEachIndexed { index, action ->
            issues += validateAction(index, action)
        }

        return issues
    }

    private fun validateAction(index: Int, action: Action): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val prefix = "actions[$index]"

        when (action) {
            is Action.OpenApp -> {
                if (action.app.isBlank()) {
                    issues += ValidationIssue("$prefix.app", "App name/package cannot be blank.")
                }
                if (action.app.length > MAX_APP_NAME_LENGTH) {
                    issues += ValidationIssue("$prefix.app", "App name/package is too long.")
                }
            }

            is Action.Click -> {
                if (!action.selector.hasAnySignal()) {
                    issues += ValidationIssue(
                        "$prefix.selector",
                        "Selector must include at least one matcher (text/contentDesc/viewId/nodeId)."
                    )
                }
            }

            is Action.TypeText -> {
                if (action.text.isBlank()) {
                    issues += ValidationIssue("$prefix.text", "Text cannot be blank.")
                }
                if (action.text.length > MAX_TYPED_TEXT_LENGTH) {
                    issues += ValidationIssue("$prefix.text", "Text exceeds max length ($MAX_TYPED_TEXT_LENGTH).")
                }
            }

            is Action.Scroll -> {
                if (action.amount !in 1..3) {
                    issues += ValidationIssue("$prefix.amount", "Scroll amount must be in range 1..3.")
                }
            }

            Action.Back,
            Action.Home -> Unit

            is Action.Wait -> {
                if (action.ms !in 0..MAX_WAIT_MS) {
                    issues += ValidationIssue("$prefix.ms", "Wait ms must be in range 0..$MAX_WAIT_MS.")
                }
            }
        }

        return issues
    }

    private fun Selector.hasAnySignal(): Boolean {
        return !textContains.isNullOrBlank() ||
            !contentDescContains.isNullOrBlank() ||
            !viewIdContains.isNullOrBlank() ||
            !nodeId.isNullOrBlank()
    }
}
