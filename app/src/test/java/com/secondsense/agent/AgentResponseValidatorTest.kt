package com.secondsense.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentResponseValidatorTest {

    @Test
    fun validate_validResponse_returnsNoIssues() {
        val response = AgentResponse(
            status = Status.IN_PROGRESS,
            actions = listOf(
                Action.OpenApp("Instagram"),
                Action.Click(selector = Selector(textContains = "Messages"))
            )
        )

        val issues = AgentResponseValidator.validate(response)

        assertTrue(issues.isEmpty())
    }

    @Test
    fun validate_needsClarificationWithoutQuestion_returnsIssue() {
        val response = AgentResponse(
            status = Status.NEEDS_CLARIFICATION,
            actions = emptyList(),
            question = ""
        )

        val issues = AgentResponseValidator.validate(response)

        assertEquals(1, issues.size)
        assertEquals("question", issues.first().field)
    }

    @Test
    fun validate_clickWithoutSelectorSignals_returnsIssue() {
        val response = AgentResponse(
            status = Status.IN_PROGRESS,
            actions = listOf(Action.Click(selector = Selector()))
        )

        val issues = AgentResponseValidator.validate(response)

        assertEquals(1, issues.size)
        assertEquals("actions[0].selector", issues.first().field)
    }

    @Test
    fun validate_tooManyActions_returnsIssue() {
        val response = AgentResponse(
            status = Status.IN_PROGRESS,
            actions = listOf(
                Action.Back,
                Action.Home,
                Action.Wait(10),
                Action.Wait(20)
            )
        )

        val issues = AgentResponseValidator.validate(response)

        assertTrue(issues.any { it.field == "actions" && it.message.contains("Too many actions") })
    }

    @Test
    fun validate_needsConfirmationWithoutPrompt_returnsIssue() {
        val response = AgentResponse(
            status = Status.IN_PROGRESS,
            actions = listOf(Action.Wait(100)),
            needsUserConfirmation = true,
            confirmationPrompt = null
        )

        val issues = AgentResponseValidator.validate(response)

        assertTrue(issues.any { it.field == "confirmationPrompt" })
    }

    @Test
    fun validate_waitOutOfRange_returnsIssue() {
        val response = AgentResponse(
            status = Status.IN_PROGRESS,
            actions = listOf(Action.Wait(5000))
        )

        val issues = AgentResponseValidator.validate(response)

        assertTrue(issues.any { it.field == "actions[0].ms" })
    }
}
