package com.secondsense.automation

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.secondsense.access.NodeMatcher
import com.secondsense.agent.Action
import com.secondsense.agent.ScrollDirection

class ActionExecutor(
    private val service: AccessibilityService,
    private val nodeMatcher: NodeMatcher = NodeMatcher()
) {

    fun execute(actions: List<Action>): List<ActionExecutionResult> {
        return actions.map(::execute)
    }

    fun execute(action: Action): ActionExecutionResult {
        return when (action) {
            is Action.OpenApp -> executeOpenApp(action)
            is Action.Click -> executeClick(action)
            is Action.TypeText -> executeTypeText(action)
            is Action.Scroll -> executeScroll(action)
            Action.Back -> executeGlobalAction(action, AccessibilityService.GLOBAL_ACTION_BACK, "back")
            Action.Home -> executeGlobalAction(action, AccessibilityService.GLOBAL_ACTION_HOME, "home")
            is Action.Wait -> executeWait(action)
        }
    }

    private fun executeOpenApp(action: Action.OpenApp): ActionExecutionResult {
        val appInput = action.app.trim()
        if (appInput.isEmpty()) {
            return action.failure("App name cannot be blank.")
        }

        val launchIntent = resolveLaunchIntent(appInput)
            ?: return action.failure("Could not resolve launchable app for '$appInput'.")

        return try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            service.startActivity(launchIntent)
            action.success("Launched '$appInput'.")
        } catch (t: Throwable) {
            action.failure("Failed to launch '$appInput': ${t.message}")
        }
    }

    private fun resolveLaunchIntent(appInput: String): Intent? {
        val packageManager = service.packageManager
        val normalizedInput = appInput.trim().lowercase()

        if (appInput.contains('.')) {
            buildLaunchIntentForPackage(appInput)?.let { return it }
        }

        KNOWN_APP_PACKAGES[normalizedInput]
            ?.let(::buildLaunchIntentForPackage)
            ?.let { return it }

        val launchables = queryLaunchableActivities()
        var bestPackageName: String? = null

        launchables.forEach { resolveInfo ->
            val packageName = resolveInfo.activityInfo.packageName
            val label = resolveInfo.loadLabel(packageManager).toString()

            if (label.equals(appInput, ignoreCase = true) || packageName.equals(appInput, ignoreCase = true)) {
                bestPackageName = packageName
                return@forEach
            }

            if (bestPackageName == null && (label.contains(appInput, true) || packageName.contains(appInput, true))) {
                bestPackageName = packageName
            }
        }

        return bestPackageName?.let(::buildLaunchIntentForPackage)
    }

    private fun buildLaunchIntentForPackage(packageName: String): Intent? {
        val packageManager = service.packageManager
        packageManager.getLaunchIntentForPackage(packageName)?.let { return it }
        packageManager.getLeanbackLaunchIntentForPackage(packageName)?.let { return it }

        val queryIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(packageName)

        val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                queryIntent,
                android.content.pm.PackageManager.ResolveInfoFlags.of(0)
            ).firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(queryIntent, 0).firstOrNull()
        } ?: return null

        return Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(packageName, resolveInfo.activityInfo.name)
    }

    private fun queryLaunchableActivities() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            service.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                android.content.pm.PackageManager.ResolveInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            service.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                0
            )
        }

    private fun executeClick(action: Action.Click): ActionExecutionResult {
        val root = service.rootInActiveWindow ?: return action.failure("No active window root available.")
        val match = nodeMatcher.findBestMatch(root, action.selector)
            ?: return action.failure("No matching clickable node found for selector.")

        val clicked = match.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return if (clicked) {
            action.success(
                message = "Click executed.",
                matchedNodeId = match.matchedNodeId,
                usedParentFallback = match.usedParentFallback
            )
        } else {
            action.failure(
                message = "Click action failed on matched node.",
                matchedNodeId = match.matchedNodeId,
                usedParentFallback = match.usedParentFallback
            )
        }
    }

    private fun executeTypeText(action: Action.TypeText): ActionExecutionResult {
        val root = service.rootInActiveWindow ?: return action.failure("No active window root available.")
        val target = findEditableTarget(root) ?: return action.failure("No editable target is focused or visible.")

        if (!target.isFocused) {
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text)
        }
        val success = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        return if (success) {
            action.success("Text entered into editable field.")
        } else {
            action.failure("Failed to set text in editable field.")
        }
    }

    private fun findEditableTarget(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?.takeIf { it.isEditable && it.isEnabled }
            ?.let { return it }

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)

        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let(stack::addLast)
            }

            if (node.isEditable && node.isEnabled) {
                return node
            }
        }
        return null
    }

    private fun executeScroll(action: Action.Scroll): ActionExecutionResult {
        val root = service.rootInActiveWindow ?: return action.failure("No active window root available.")
        val directionAction = when (action.direction) {
            ScrollDirection.UP -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            ScrollDirection.DOWN -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }

        val target = findScrollableTarget(root, directionAction)
            ?: return action.failure("No scrollable target available for ${action.direction}.")

        repeat(action.amount) {
            if (!target.performAction(directionAction)) {
                return action.failure("Scroll failed after ${it + 1} step(s).")
            }
            SystemClock.sleep(SCROLL_STEP_DELAY_MS)
        }
        return action.success("Scrolled ${action.direction.name.lowercase()} x${action.amount}.")
    }

    private fun findScrollableTarget(
        root: AccessibilityNodeInfo,
        directionAction: Int
    ): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)

        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let(stack::addLast)
            }

            val supportsDirection = node.actionList.any { it.id == directionAction }
            if (node.isScrollable || supportsDirection) {
                return node
            }
        }
        return null
    }

    private fun executeGlobalAction(
        action: Action,
        globalAction: Int,
        actionName: String
    ): ActionExecutionResult {
        val success = service.performGlobalAction(globalAction)
        return if (success) {
            action.success("Global '$actionName' executed.")
        } else {
            action.failure("Global '$actionName' failed.")
        }
    }

    private fun executeWait(action: Action.Wait): ActionExecutionResult {
        if (action.ms < 0) {
            return action.failure("Wait duration cannot be negative.")
        }
        SystemClock.sleep(action.ms.toLong())
        return action.success("Waited ${action.ms}ms.")
    }

    private fun Action.success(
        message: String,
        matchedNodeId: String? = null,
        usedParentFallback: Boolean = false
    ): ActionExecutionResult {
        return ActionExecutionResult(
            action = this,
            success = true,
            message = message,
            matchedNodeId = matchedNodeId,
            usedParentFallback = usedParentFallback
        )
    }

    private fun Action.failure(
        message: String,
        matchedNodeId: String? = null,
        usedParentFallback: Boolean = false
    ): ActionExecutionResult {
        return ActionExecutionResult(
            action = this,
            success = false,
            message = message,
            matchedNodeId = matchedNodeId,
            usedParentFallback = usedParentFallback
        )
    }

    companion object {
        private const val SCROLL_STEP_DELAY_MS = 160L

        private val KNOWN_APP_PACKAGES = mapOf(
            "instagram" to "com.instagram.android"
        )
    }
}

data class ActionExecutionResult(
    val action: Action,
    val success: Boolean,
    val message: String,
    val matchedNodeId: String? = null,
    val usedParentFallback: Boolean = false
)
