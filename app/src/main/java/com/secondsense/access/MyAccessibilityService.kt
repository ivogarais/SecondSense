package com.secondsense.access

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.secondsense.BuildConfig
import com.secondsense.agent.Action
import com.secondsense.agent.AgentLoopController
import com.secondsense.agent.LoopResult
import com.secondsense.automation.ActionExecutionResult
import com.secondsense.automation.ActionExecutor
import java.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MyAccessibilityService : AccessibilityService() {

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    private var lastLoggedAtMs: Long = 0L
    private val actionExecutor by lazy { ActionExecutor(this) }
    private val loopController by lazy { AgentLoopController(::executeActionsInternal) }
    private var debugReceiverRegistered = false
    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_RUN_MODEL_OUTPUT) return
            Log.d(TAG, "Received debug model-output broadcast.")

            val rawModelOutput = intent.getStringExtra(EXTRA_MODEL_OUTPUT)
            if (rawModelOutput.isNullOrBlank()) {
                Log.w(TAG, "Debug broadcast missing EXTRA_MODEL_OUTPUT.")
                return
            }

            val userConfirmed = intent.getBooleanExtra(EXTRA_USER_CONFIRMED, false)
            val result = loopController.handleModelOutput(rawModelOutput, userConfirmed)
            logLoopResult(result)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastLoggedAtMs < LOG_THROTTLE_MS) return

        val root = rootInActiveWindow ?: return
        val screenContext = buildScreenContext(root, event.packageName?.toString())

        lastLoggedAtMs = now
        Log.d(TAG, json.encodeToString(screenContext))
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted.")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected.")
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

    private fun buildScreenContext(
        root: AccessibilityNodeInfo,
        fallbackPackage: String?
    ): ScreenContext {
        val currentPackage = root.packageName?.toString() ?: fallbackPackage
        val nodes = mutableListOf<NodeInfo>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        var nodeIdCounter = 1

        stack.add(root)

        while (stack.isNotEmpty() && nodes.size < MAX_NODES) {
            val node = stack.removeLast()
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let(stack::addLast)
            }

            if (shouldInclude(node)) {
                nodes += NodeInfo(
                    id = "n${nodeIdCounter++}",
                    text = node.text?.toString()?.takeIf(String::isNotBlank),
                    contentDesc = node.contentDescription?.toString()?.takeIf(String::isNotBlank),
                    className = node.className?.toString(),
                    clickable = node.isClickable,
                    editable = node.isEditable,
                    enabled = node.isEnabled,
                    focusable = node.isFocusable,
                    bounds = node.bounds()
                )
            }
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

    private fun registerDebugReceiver() {
        if (debugReceiverRegistered) return

        val filter = IntentFilter(ACTION_RUN_MODEL_OUTPUT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val receiverFlags = if (BuildConfig.DEBUG) {
                Context.RECEIVER_EXPORTED
            } else {
                Context.RECEIVER_NOT_EXPORTED
            }
            registerReceiver(debugReceiver, filter, receiverFlags)
            Log.d(TAG, "Debug receiver registered. exportedInDebug=${BuildConfig.DEBUG}")
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(debugReceiver, filter)
            Log.d(TAG, "Debug receiver registered for pre-Tiramisu.")
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
                result.executionResults.forEach {
                    Log.d(
                        TAG,
                        "Exec: success=${it.success}, parentFallback=${it.usedParentFallback}, msg=${it.message}"
                    )
                }
            }

            is LoopResult.ClarificationRequired -> {
                Log.d(TAG, "LoopResult.ClarificationRequired question=${result.question}")
            }

            is LoopResult.ConfirmationRequired -> {
                Log.d(TAG, "LoopResult.ConfirmationRequired prompt=${result.prompt}")
            }

            is LoopResult.DecodeFailed -> {
                Log.e(TAG, "LoopResult.DecodeFailed reason=${result.reason}")
            }

            is LoopResult.Done -> {
                Log.d(TAG, "LoopResult.Done result=${result.result}")
            }

            is LoopResult.ModelError -> {
                Log.e(TAG, "LoopResult.ModelError message=${result.message}")
            }

            is LoopResult.ValidationFailed -> {
                Log.e(TAG, "LoopResult.ValidationFailed issues=${result.issues.joinToString { "${it.field}:${it.message}" }}")
            }
        }
    }

    companion object {
        private const val TAG = "MyAccessibilityService"
        private const val LOG_THROTTLE_MS = 500L
        private const val MAX_NODES = 80

        const val ACTION_RUN_MODEL_OUTPUT = "com.secondsense.action.RUN_MODEL_OUTPUT"
        const val EXTRA_MODEL_OUTPUT = "extra_model_output"
        const val EXTRA_USER_CONFIRMED = "extra_user_confirmed"
    }
}
