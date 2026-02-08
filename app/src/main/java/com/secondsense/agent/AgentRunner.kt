package com.secondsense.agent

import com.secondsense.access.ScreenContext

class AgentRunner(
    private val modelClient: AgentModelClient,
    private val loopController: AgentLoopController,
    private val promptBuilder: PromptBuilder,
    private val screenContextProvider: () -> ScreenContext?,
    private val onPlannerStep: ((PlannerStepTrace) -> Unit)? = null,
    private val sleepFn: (Long) -> Unit = { Thread.sleep(it) },
    private val config: Config = Config()
) {

    private val traceMemory = ArrayDeque<String>()
    private var successfulActionCount: Int = 0

    data class PlannerStepTrace(
        val step: Int,
        val prompt: String,
        val rawModelOutput: String
    )

    data class Config(
        val maxSteps: Int = 8,
        val stepDelayMs: Long = 450L,
        val maxConsecutivePlannerErrors: Int = 4
    )

    fun run(goal: String, userConfirmed: Boolean = false): RunResult {
        val feedback = mutableListOf<String>()
        var lastError: String? = null
        var consecutivePlannerErrors = 0
        traceMemory.clear()
        successfulActionCount = 0

        for (step in 1..config.maxSteps) {
            val screenContext = screenContextProvider()
            if (screenContext == null) {
                return RunResult.Failed("No active screen context available.")
            }

            val bootstrapApp = maybeBootstrapOpenApp(goal, step, screenContext)
            if (bootstrapApp != null) {
                val bootstrapResult = loopController.handleResponse(
                    response = AgentResponse(
                        status = Status.IN_PROGRESS,
                        actions = listOf(Action.OpenApp(bootstrapApp))
                    ),
                    userConfirmed = userConfirmed
                )
                val bootstrapTrace = "bootstrap open_app($bootstrapApp)"
                feedback += "step $step: $bootstrapTrace"
                appendTraceMemory("step $step: $bootstrapTrace")

                when (bootstrapResult) {
                    is LoopResult.ActionsExecuted -> {
                        consecutivePlannerErrors = 0
                        successfulActionCount += bootstrapResult.executionResults.count { it.success }
                        bootstrapResult.executionResults.forEach { result ->
                            appendTraceMemory(
                                "step $step: ${actionToTrace(result.action)} => ${if (result.success) "ok" else "fail"}"
                            )
                        }
                        val stepFeedback = bootstrapResult.executionResults.joinToString(" | ") {
                            "success=${it.success}, msg=${it.message}"
                        }
                        lastError = if (bootstrapResult.allSucceeded) null else stepFeedback
                        sleepFn(config.stepDelayMs)
                        continue
                    }

                    is LoopResult.ConfirmationRequired -> return RunResult.NeedsConfirmation(bootstrapResult.prompt)
                    is LoopResult.ClarificationRequired -> return RunResult.NeedsClarification(bootstrapResult.question)
                    is LoopResult.Done -> return RunResult.Completed(bootstrapResult.result)
                    is LoopResult.DecodeFailed -> {
                        consecutivePlannerErrors += 1
                        lastError = "decode_failed: ${bootstrapResult.reason}"
                    }
                    is LoopResult.ValidationFailed -> {
                        consecutivePlannerErrors += 1
                        val issueText = bootstrapResult.issues.joinToString { "${it.field}:${it.message}" }
                        lastError = "validation_failed: $issueText"
                    }
                    is LoopResult.ModelError -> {
                        consecutivePlannerErrors += 1
                        lastError = "model_error: ${bootstrapResult.message}"
                    }
                }
            }

            val prompt = promptBuilder.build(
                goal = goal,
                step = step,
                screenContext = screenContext,
                recentFeedback = feedback,
                lastError = lastError,
                recentActions = traceMemory.toList()
            )

            val rawModelOutput = try {
                modelClient.generate(prompt)
            } catch (t: Throwable) {
                return RunResult.Failed(
                    reason = "Model generation failed.",
                    lastError = t.message
                )
            }
            onPlannerStep?.invoke(
                PlannerStepTrace(
                    step = step,
                    prompt = prompt,
                    rawModelOutput = rawModelOutput
                )
            )

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
                    successfulActionCount += loopResult.executionResults.count { it.success }
                    loopResult.executionResults.forEach { result ->
                        appendTraceMemory(
                            "step $step: ${actionToTrace(result.action)} => ${if (result.success) "ok" else "fail"}"
                        )
                    }
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
                    if (shouldRejectDoneWithoutProgress(goal, screenContext)) {
                        consecutivePlannerErrors += 1
                        lastError = "done_rejected: goal does not appear satisfied from current context."
                        feedback += "step $step: $lastError"
                        continue
                    }
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

    private fun appendTraceMemory(entry: String) {
        traceMemory += entry
        while (traceMemory.size > MAX_TRACE_ITEMS) {
            traceMemory.removeFirst()
        }
    }

    private fun actionToTrace(action: Action): String {
        return when (action) {
            is Action.OpenApp -> "open_app(${trim(action.app, 28)})"
            is Action.Click -> {
                val signal = action.selector.textContains
                    ?: action.selector.contentDescContains
                    ?: action.selector.viewIdContains
                    ?: action.selector.nodeId
                    ?: "?"
                "click(${trim(signal, 36)})"
            }
            is Action.TypeText -> "type_text(${trim(action.text, 28)})"
            is Action.Scroll -> "scroll(${action.direction.name.lowercase()},${action.amount})"
            Action.Back -> "back()"
            Action.Home -> "home()"
            is Action.Wait -> "wait(${action.ms})"
        }
    }

    private fun trim(value: String, maxLength: Int): String {
        val clean = value.replace(Regex("\\s+"), " ").trim()
        return if (clean.length <= maxLength) clean else clean.take(maxLength) + "..."
    }

    private fun shouldRejectDoneWithoutProgress(goal: String, screenContext: ScreenContext): Boolean {
        if (successfulActionCount > 0) return false

        val normalizedGoal = goal.lowercase()
        if (!containsNavigationIntent(normalizedGoal)) return false

        val currentPackage = screenContext.currentAppPackage?.lowercase().orEmpty()
        val nodeSignals = screenContext.nodes.joinToString(" ") {
            listOf(it.text, it.contentDesc, it.viewId).filterNotNull().joinToString(" ")
        }.lowercase()

        if (normalizedGoal.contains("instagram") && !currentPackage.contains("instagram")) return true
        if (normalizedGoal.contains("whatsapp") && !currentPackage.contains("whatsapp")) return true
        if (
            normalizedGoal.contains("message") ||
            normalizedGoal.contains("dm") ||
            normalizedGoal.contains("chat")
        ) {
            val hasMessageSignals = listOf("message", "messages", "dm", "chat", "inbox")
                .any { nodeSignals.contains(it) }
            if (!hasMessageSignals) return true
        }

        return false
    }

    private fun containsNavigationIntent(goal: String): Boolean {
        return listOf(
            "open",
            "go to",
            "navigate",
            "message",
            "dm",
            "chat",
            "send",
            "click",
            "tap",
            "type",
            "scroll",
            "back",
            "home"
        ).any { goal.contains(it) }
    }

    private fun maybeBootstrapOpenApp(
        goal: String,
        step: Int,
        screenContext: ScreenContext
    ): String? {
        if (step != 1 || successfulActionCount > 0) return null
        val target = inferGoalAppTarget(goal) ?: return null

        val currentPackage = screenContext.currentAppPackage?.lowercase().orEmpty()
        if (currentPackage.contains(target.packageHint)) return null

        return target.displayName
    }

    private fun inferGoalAppTarget(goal: String): GoalAppTarget? {
        val normalizedGoal = goal.lowercase()
        return when {
            normalizedGoal.contains("instagram") -> GoalAppTarget("Instagram", "instagram")
            normalizedGoal.contains("whatsapp") -> GoalAppTarget("WhatsApp", "whatsapp")
            normalizedGoal.contains("telegram") -> GoalAppTarget("Telegram", "telegram")
            normalizedGoal.contains("gmail") -> GoalAppTarget("Gmail", "gmail")
            normalizedGoal.contains("youtube") -> GoalAppTarget("YouTube", "youtube")
            else -> null
        }
    }

    private companion object {
        private const val MAX_TRACE_ITEMS = 8
    }
}

private data class GoalAppTarget(
    val displayName: String,
    val packageHint: String
)

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
