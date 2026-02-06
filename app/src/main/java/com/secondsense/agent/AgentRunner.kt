package com.secondsense.agent

import com.secondsense.access.ScreenContext

class AgentRunner(
    private val modelClient: AgentModelClient,
    private val loopController: AgentLoopController,
    private val promptBuilder: PromptBuilder,
    private val screenContextProvider: () -> ScreenContext?,
    private val sleepFn: (Long) -> Unit = { Thread.sleep(it) },
    private val config: Config = Config()
) {

    data class Config(
        val maxSteps: Int = 8,
        val stepDelayMs: Long = 450L,
        val maxConsecutivePlannerErrors: Int = 2
    )

    fun run(goal: String, userConfirmed: Boolean = false): RunResult {
        val feedback = mutableListOf<String>()
        var lastError: String? = null
        var consecutivePlannerErrors = 0

        for (step in 1..config.maxSteps) {
            val screenContext = screenContextProvider()
            if (screenContext == null) {
                return RunResult.Failed("No active screen context available.")
            }

            val prompt = promptBuilder.build(
                goal = goal,
                step = step,
                screenContext = screenContext,
                recentFeedback = feedback,
                lastError = lastError
            )

            val rawModelOutput = modelClient.generate(prompt)
            val loopResult = loopController.handleModelOutput(
                rawModelOutput = rawModelOutput,
                userConfirmed = userConfirmed
            )

            when (loopResult) {
                is LoopResult.ActionsExecuted -> {
                    consecutivePlannerErrors = 0
                    val stepFeedback = loopResult.executionResults.joinToString(" | ") {
                        "success=${it.success}, msg=${it.message}"
                    }
                    feedback += "step $step: $stepFeedback"
                    lastError = if (loopResult.allSucceeded) null else stepFeedback
                    sleepFn(config.stepDelayMs)
                }

                is LoopResult.ClarificationRequired -> {
                    return RunResult.NeedsClarification(loopResult.question)
                }

                is LoopResult.ConfirmationRequired -> {
                    return RunResult.NeedsConfirmation(loopResult.prompt)
                }

                is LoopResult.Done -> {
                    return RunResult.Completed(loopResult.result)
                }

                is LoopResult.DecodeFailed -> {
                    consecutivePlannerErrors += 1
                    lastError = "decode_failed: ${loopResult.reason}"
                    feedback += "step $step: $lastError"
                }

                is LoopResult.ValidationFailed -> {
                    consecutivePlannerErrors += 1
                    val issueText = loopResult.issues.joinToString { "${it.field}:${it.message}" }
                    lastError = "validation_failed: $issueText"
                    feedback += "step $step: $lastError"
                }

                is LoopResult.ModelError -> {
                    consecutivePlannerErrors += 1
                    lastError = "model_error: ${loopResult.message}"
                    feedback += "step $step: $lastError"
                }
            }

            if (consecutivePlannerErrors > config.maxConsecutivePlannerErrors) {
                return RunResult.Failed(
                    "Stopped after repeated planner errors.",
                    lastError = lastError
                )
            }
        }

        return RunResult.MaxStepsReached(config.maxSteps)
    }
}

sealed interface RunResult {
    data class Completed(val result: String) : RunResult

    data class NeedsClarification(val question: String) : RunResult

    data class NeedsConfirmation(val prompt: String) : RunResult

    data class MaxStepsReached(val maxSteps: Int) : RunResult

    data class Failed(
        val reason: String,
        val lastError: String? = null
    ) : RunResult
}
