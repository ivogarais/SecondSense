package com.secondsense.agent

import com.secondsense.automation.ActionExecutionResult

class AgentLoopController(
    private val executeActions: (List<Action>) -> List<ActionExecutionResult>
) {

    fun handleResponse(
        response: AgentResponse,
        userConfirmed: Boolean = false
    ): LoopResult {
        return processResponse(
            response = response,
            userConfirmed = userConfirmed
        )
    }

    fun handleModelOutput(
        rawModelOutput: String,
        userConfirmed: Boolean = false
    ): LoopResult {
        return when (val decoded = AgentJsonCodec.decode(rawModelOutput)) {
            is DecodeResult.Failure -> LoopResult.DecodeFailed(
                reason = decoded.reason,
                rawJson = decoded.rawJson
            )

            is DecodeResult.Success -> processResponse(
                response = decoded.response,
                userConfirmed = userConfirmed
            )
        }
    }

    private fun processResponse(
        response: AgentResponse,
        userConfirmed: Boolean
    ): LoopResult {
        val validationIssues = AgentResponseValidator.validate(response)
        if (validationIssues.isNotEmpty()) {
            return LoopResult.ValidationFailed(validationIssues)
        }

        if (response.needsUserConfirmation && !userConfirmed) {
            return LoopResult.ConfirmationRequired(
                prompt = response.confirmationPrompt ?: "Confirm action execution?",
                actions = response.actions
            )
        }

        return when (response.status) {
            Status.IN_PROGRESS -> {
                val executionResults = executeActions(response.actions)
                LoopResult.ActionsExecuted(
                    executionResults = executionResults,
                    allSucceeded = executionResults.all { it.success }
                )
            }

            Status.NEEDS_CLARIFICATION -> {
                LoopResult.ClarificationRequired(
                    question = response.question ?: "Please clarify your request."
                )
            }

            Status.DONE -> LoopResult.Done(result = response.result ?: "Done.")
            Status.ERROR -> LoopResult.ModelError(message = response.result ?: "Model reported error status.")
        }
    }
}

sealed interface LoopResult {
    data class DecodeFailed(
        val reason: String,
        val rawJson: String?
    ) : LoopResult

    data class ValidationFailed(
        val issues: List<ValidationIssue>
    ) : LoopResult

    data class ConfirmationRequired(
        val prompt: String,
        val actions: List<Action>
    ) : LoopResult

    data class ClarificationRequired(
        val question: String
    ) : LoopResult

    data class ActionsExecuted(
        val executionResults: List<ActionExecutionResult>,
        val allSucceeded: Boolean
    ) : LoopResult

    data class Done(
        val result: String
    ) : LoopResult

    data class ModelError(
        val message: String
    ) : LoopResult
}
