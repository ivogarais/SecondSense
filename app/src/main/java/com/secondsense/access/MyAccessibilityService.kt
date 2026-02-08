package com.secondsense.access

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.secondsense.BuildConfig
import com.secondsense.agent.Action
import com.secondsense.agent.AgentJsonCodec
import com.secondsense.agent.AgentLoopController
import com.secondsense.agent.AgentRunner
import com.secondsense.agent.LoopResult
import com.secondsense.agent.PromptBuilder
import com.secondsense.agent.RunResult
import com.secondsense.automation.ActionExecutionResult
import com.secondsense.automation.ActionExecutor
import com.secondsense.debug.AgentLogStore
import com.secondsense.llm.LocalModelProvider

class MyAccessibilityService : AccessibilityService() {

    private var lastSummaryLoggedAtMs: Long = 0L
    private val actionExecutor by lazy { ActionExecutor(this) }
    private val loopController by lazy { AgentLoopController(::executeActionsInternal) }
    private val screenContextProvider by lazy {
        ScreenContextProvider(
            service = this,
            maxNodes = MAX_CONTEXT_NODES
        )
    }

    @Volatile
    private var goalRunInProgress = false

    private var debugReceiverRegistered = false
    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_RUN_MODEL_OUTPUT -> handleRawModelOutput(intent)
                ACTION_RUN_AGENT_GOAL -> handleGoalRun(intent)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastSummaryLoggedAtMs < LOG_SUMMARY_THROTTLE_MS) return

        val screenContext = screenContextProvider.capture() ?: return
        lastSummaryLoggedAtMs = now

        Log.d(
            TAG,
            "ScreenContext app=${screenContext.currentAppPackage ?: "unknown"}, nodes=${screenContext.nodes.size}"
        )

        if (BuildConfig.DEBUG && DEBUG_VERBOSE_CONTEXT_LOGS) {
            Log.v(TAG, "ScreenContext detail: $screenContext")
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted.")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected.")
        appendAgentLog("Accessibility service connected.")
        registerDebugReceiver()
    }

    override fun onDestroy() {
        unregisterDebugReceiver()
        super.onDestroy()
    }

    fun executeActions(actions: List<Action>): List<ActionExecutionResult> {
        val results = executeActionsInternal(actions)
        results.forEach {
            Log.d(
                TAG,
                "Action result: success=${it.success}, usedParentFallback=${it.usedParentFallback}, message=${it.message}"
            )
        }
        return results
    }

    private fun executeActionsInternal(actions: List<Action>): List<ActionExecutionResult> {
        return actionExecutor.execute(actions)
    }

    private fun handleRawModelOutput(intent: Intent) {
        Log.d(TAG, "Received debug model-output broadcast.")

        val rawModelOutput = intent.getStringExtra(EXTRA_MODEL_OUTPUT)
        if (rawModelOutput.isNullOrBlank()) {
            Log.w(TAG, "Debug broadcast missing EXTRA_MODEL_OUTPUT.")
            appendAgentLog("Debug broadcast missing model output.")
            return
        }

        val userConfirmed = intent.getBooleanExtra(EXTRA_USER_CONFIRMED, false)
        val result = loopController.handleModelOutput(rawModelOutput, userConfirmed)
        logLoopResult(result)
    }

    private fun handleGoalRun(intent: Intent) {
        val goal = intent.getStringExtra(EXTRA_USER_GOAL)
        if (goal.isNullOrBlank()) {
            Log.w(TAG, "Goal broadcast missing EXTRA_USER_GOAL.")
            appendAgentLog("Run goal failed: missing goal text.")
            return
        }

        if (goalRunInProgress) {
            Log.w(TAG, "A goal run is already in progress.")
            appendAgentLog("Run goal ignored: another run is in progress.")
            return
        }

        val userConfirmed = intent.getBooleanExtra(EXTRA_USER_CONFIRMED, false)

        Thread {
            goalRunInProgress = true
            try {
                Log.d(TAG, "Starting local goal run: $goal")
                appendAgentLog("Starting goal: $goal")

                val runner = AgentRunner(
                    modelClient = LocalModelProvider.get(applicationContext).plannerClient(),
                    loopController = loopController,
                    promptBuilder = PromptBuilder(),
                    screenContextProvider = { screenContextProvider.capture() },
                    onPlannerStep = { trace ->
                        appendAgentLog("Planner step=${trace.step} promptPreview=${compactForLog(trace.prompt, 2200)}")
                        val extractedJson = AgentJsonCodec.extractJsonObject(trace.rawModelOutput)
                        if (BuildConfig.DEBUG) {
                            Log.d(
                                TAG,
                                "Planner step=${trace.step} raw=${compactForLog(trace.rawModelOutput)}"
                            )
                        }
                        appendAgentLog("Planner step=${trace.step} rawPreview=${compactForLog(trace.rawModelOutput, 2200)}")
                        if (extractedJson != null) {
                            appendAgentLog("Planner step=${trace.step} json=${compactForLog(extractedJson, 2200)}")
                        } else {
                            appendAgentLog("Planner step=${trace.step} json=NOT_FOUND")
                        }
                    }
                )

                val runResult = runner.run(goal = goal, userConfirmed = userConfirmed)
                logRunResult(runResult)
            } catch (t: Throwable) {
                Log.e(TAG, "Goal run crashed: ${t.message}", t)
                appendAgentLog("Goal run crashed: ${t.message ?: "unknown"}")
            } finally {
                goalRunInProgress = false
                appendAgentLog("Goal run finished.")
            }
        }.start()
    }

    private fun registerDebugReceiver() {
        if (debugReceiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(ACTION_RUN_MODEL_OUTPUT)
            addAction(ACTION_RUN_AGENT_GOAL)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val receiverFlags = if (BuildConfig.DEBUG) {
                Context.RECEIVER_EXPORTED
            } else {
                Context.RECEIVER_NOT_EXPORTED
            }
            registerReceiver(debugReceiver, filter, receiverFlags)
            Log.d(TAG, "Debug receiver registered. exportedInDebug=${BuildConfig.DEBUG}")
            appendAgentLog("Debug receiver registered.")
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(debugReceiver, filter)
            Log.d(TAG, "Debug receiver registered for pre-Tiramisu.")
            appendAgentLog("Debug receiver registered.")
        }
        debugReceiverRegistered = true
    }

    private fun unregisterDebugReceiver() {
        if (!debugReceiverRegistered) return
        try {
            unregisterReceiver(debugReceiver)
        } catch (_: IllegalArgumentException) {
            // Ignore if receiver already unregistered by framework.
        } finally {
            debugReceiverRegistered = false
        }
    }

    private fun logLoopResult(result: LoopResult) {
        when (result) {
            is LoopResult.ActionsExecuted -> {
                Log.d(TAG, "LoopResult.ActionsExecuted allSucceeded=${result.allSucceeded}")
                appendAgentLog("ActionsExecuted allSucceeded=${result.allSucceeded}")
                result.executionResults.forEach {
                    Log.d(
                        TAG,
                        "Exec: success=${it.success}, parentFallback=${it.usedParentFallback}, msg=${it.message}"
                    )
                    appendAgentLog("Exec success=${it.success} msg=${it.message}")
                }
            }

            is LoopResult.ClarificationRequired -> {
                Log.d(TAG, "LoopResult.ClarificationRequired question=${result.question}")
                appendAgentLog("Needs clarification: ${result.question}")
            }

            is LoopResult.ConfirmationRequired -> {
                Log.d(TAG, "LoopResult.ConfirmationRequired prompt=${result.prompt}")
                appendAgentLog("Needs confirmation: ${result.prompt}")
            }

            is LoopResult.DecodeFailed -> {
                Log.e(TAG, "LoopResult.DecodeFailed reason=${result.reason}")
                appendAgentLog("Decode failed: ${result.reason}")
                if (!result.rawJson.isNullOrBlank()) {
                    appendAgentLog("Decode failed rawJson=${compactForLog(result.rawJson, 1200)}")
                }
            }

            is LoopResult.Done -> {
                Log.d(TAG, "LoopResult.Done result=${result.result}")
                appendAgentLog("Done: ${result.result}")
            }

            is LoopResult.ModelError -> {
                Log.e(TAG, "LoopResult.ModelError message=${result.message}")
                appendAgentLog("Model error: ${result.message}")
            }

            is LoopResult.ValidationFailed -> {
                Log.e(TAG, "LoopResult.ValidationFailed issues=${result.issues.joinToString { "${it.field}:${it.message}" }}")
                appendAgentLog("Validation failed: ${result.issues.joinToString { "${it.field}:${it.message}" }}")
            }
        }
    }

    private fun logRunResult(result: RunResult) {
        when (result) {
            is RunResult.Completed -> {
                Log.d(TAG, "RunResult.Completed result=${result.result}")
                appendAgentLog("Run completed: ${result.result}")
            }

            is RunResult.NeedsClarification -> {
                Log.d(TAG, "RunResult.NeedsClarification question=${result.question}")
                appendAgentLog("Run needs clarification: ${result.question}")
            }

            is RunResult.NeedsConfirmation -> {
                Log.d(TAG, "RunResult.NeedsConfirmation prompt=${result.prompt}")
                appendAgentLog("Run needs confirmation: ${result.prompt}")
            }

            is RunResult.MaxStepsReached -> {
                Log.w(TAG, "RunResult.MaxStepsReached maxSteps=${result.maxSteps}")
                appendAgentLog("Run stopped: max steps reached (${result.maxSteps}).")
            }

            is RunResult.Failed -> {
                Log.e(TAG, "RunResult.Failed reason=${result.reason}, lastError=${result.lastError}")
                appendAgentLog("Run failed: ${result.reason}. lastError=${result.lastError ?: "none"}")
            }
        }
    }

    companion object {
        private const val TAG = "MyAccessibilityService"

        private const val LOG_SUMMARY_THROTTLE_MS = 1200L
        private const val MAX_CONTEXT_NODES = 40
        private const val DEBUG_VERBOSE_CONTEXT_LOGS = false

        const val ACTION_RUN_MODEL_OUTPUT = "com.secondsense.action.RUN_MODEL_OUTPUT"
        const val ACTION_RUN_AGENT_GOAL = "com.secondsense.action.RUN_AGENT_GOAL"

        const val EXTRA_MODEL_OUTPUT = "extra_model_output"
        const val EXTRA_USER_GOAL = "extra_user_goal"
        const val EXTRA_USER_CONFIRMED = "extra_user_confirmed"
    }

    private fun compactForLog(value: String, maxChars: Int = 1600): String {
        val normalized = value
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (normalized.length <= maxChars) normalized else normalized.take(maxChars) + "...(truncated)"
    }

    private fun appendAgentLog(message: String) {
        AgentLogStore.append(TAG, message)
    }
}
