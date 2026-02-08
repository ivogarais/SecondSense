package com.secondsense.agent

import com.secondsense.access.Bounds
import com.secondsense.access.NodeInfo
import com.secondsense.access.ScreenContext
import com.secondsense.automation.ActionExecutionResult
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunnerAndPromptBuilderTest {

    @Test
    fun promptBuilder_compactsContextAndLimitsNodeCount() {
        val nodes = (1..20).map {
            NodeInfo(
                id = "n$it",
                text = "Item $it",
                contentDesc = null,
                viewId = null,
                className = "android.widget.TextView",
                clickable = true,
                editable = false,
                enabled = true,
                focusable = true,
                bounds = Bounds(0, 0, 100, 100)
            )
        }
        val context = ScreenContext(
            currentAppPackage = "com.test.app",
            timestamp = "2026-02-06T20:00:00Z",
            nodes = nodes
        )

        val prompt = PromptBuilder(maxNodesInPrompt = 8).build(
            goal = "Open app",
            step = 1,
            screenContext = context,
            recentFeedback = emptyList(),
            lastError = null
        )

        assertTrue(prompt.contains("\"currentAppPackage\":\"com.test.app\""))
        assertTrue(prompt.contains("\"id\":\"n8\""))
        assertFalse(prompt.contains("\"id\":\"n9\""))
        assertFalse(prompt.contains("\"bounds\""))
    }

    @Test
    @OptIn(ExperimentalSerializationApi::class)
    fun agentRunner_executesActionsAndCompletes() {
        val json = kotlinx.serialization.json.Json {
            encodeDefaults = true
            explicitNulls = false
            classDiscriminator = "type"
        }

        val first = json.encodeToString(
            AgentResponse(
                status = Status.IN_PROGRESS,
                actions = listOf(Action.Wait(ms = 10))
            )
        )
        val second = json.encodeToString(
            AgentResponse(
                status = Status.DONE,
                result = "Done in mock"
            )
        )

        val scriptedClient = ScriptedClient(listOf(first, second))
        val loopController = AgentLoopController { actions ->
            actions.map {
                ActionExecutionResult(
                    action = it,
                    success = true,
                    message = "ok"
                )
            }
        }

        val runner = AgentRunner(
            modelClient = scriptedClient,
            loopController = loopController,
            promptBuilder = PromptBuilder(),
            screenContextProvider = {
                ScreenContext(
                    currentAppPackage = "com.test",
                    timestamp = "2026-02-06T20:00:00Z",
                    nodes = emptyList()
                )
            },
            sleepFn = {},
            config = AgentRunner.Config(maxSteps = 4, stepDelayMs = 0)
        )

        val result = runner.run(goal = "Test goal", userConfirmed = true)

        assertTrue(result is RunResult.Completed)
        assertEquals(2, scriptedClient.calls)
        assertEquals("Done in mock", (result as RunResult.Completed).result)
    }

    @Test
    @OptIn(ExperimentalSerializationApi::class)
    fun agentRunner_bootstrapsOpenApp_beforePlannerWhenGoalNamesTargetApp() {
        val json = kotlinx.serialization.json.Json {
            encodeDefaults = true
            explicitNulls = false
            classDiscriminator = "type"
        }
        val done = json.encodeToString(
            AgentResponse(
                status = Status.DONE,
                result = "Reached target app."
            )
        )
        val scriptedClient = ScriptedClient(listOf(done))
        val executedActions = mutableListOf<Action>()

        val loopController = AgentLoopController { actions ->
            executedActions += actions
            actions.map {
                ActionExecutionResult(
                    action = it,
                    success = true,
                    message = "ok"
                )
            }
        }

        val runner = AgentRunner(
            modelClient = scriptedClient,
            loopController = loopController,
            promptBuilder = PromptBuilder(),
            screenContextProvider = {
                ScreenContext(
                    currentAppPackage = "com.secondsense",
                    timestamp = "2026-02-06T20:00:00Z",
                    nodes = emptyList()
                )
            },
            sleepFn = {},
            config = AgentRunner.Config(maxSteps = 4, stepDelayMs = 0)
        )

        val result = runner.run(goal = "Open Instagram and go to messages", userConfirmed = true)

        assertTrue(result is RunResult.Completed)
        assertTrue(executedActions.first() is Action.OpenApp)
        assertEquals("Instagram", (executedActions.first() as Action.OpenApp).app)
        assertEquals(1, scriptedClient.calls)
    }

    private class ScriptedClient(
        private val outputs: List<String>
    ) : AgentModelClient {
        var calls: Int = 0
            private set

        override fun generate(prompt: String): String {
            val value = outputs.getOrNull(calls) ?: outputs.last()
            calls += 1
            return value
        }
    }
}
