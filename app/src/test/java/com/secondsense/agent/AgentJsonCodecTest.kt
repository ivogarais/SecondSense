package com.secondsense.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentJsonCodecTest {

    @Test
    fun decode_validInProgressActions_returnsSuccess() {
        val raw =
            """
            {
              "status": "in_progress",
              "actions": [
                {"type": "open_app", "app": "Instagram"},
                {"type": "click", "selector": {"textContains": "Messages"}},
                {"type": "wait", "ms": 500}
              ]
            }
            """.trimIndent()

        val result = AgentJsonCodec.decode(raw)

        assertTrue(result is DecodeResult.Success)
        val response = (result as DecodeResult.Success).response
        assertEquals(Status.IN_PROGRESS, response.status)
        assertEquals(3, response.actions.size)
        assertTrue(response.actions[0] is Action.OpenApp)
        assertTrue(response.actions[1] is Action.Click)
        assertTrue(response.actions[2] is Action.Wait)

        val openApp = response.actions[0] as Action.OpenApp
        assertEquals("Instagram", openApp.app)
    }

    @Test
    fun decode_markdownWrappedJson_extractsAndDecodes() {
        val raw =
            """
            Here is the result:
            ```json
            {
              "status": "done",
              "result": "Task completed"
            }
            ```
            """.trimIndent()

        val result = AgentJsonCodec.decode(raw)

        assertTrue(result is DecodeResult.Success)
        val response = (result as DecodeResult.Success).response
        assertEquals(Status.DONE, response.status)
        assertEquals("Task completed", response.result)
    }

    @Test
    fun decode_withoutJsonObject_returnsFailure() {
        val raw = "model output with no structured content"

        val result = AgentJsonCodec.decode(raw)

        assertTrue(result is DecodeResult.Failure)
        val failure = result as DecodeResult.Failure
        assertTrue(failure.reason.contains("No JSON object found"))
        assertNull(failure.rawJson)
    }

    @Test
    fun decode_invalidActionShape_returnsFailureWithPayload() {
        val raw =
            """
            {
              "status": "in_progress",
              "actions": [
                {"app": "Instagram"}
              ]
            }
            """.trimIndent()

        val result = AgentJsonCodec.decode(raw)

        assertTrue(result is DecodeResult.Failure)
        val failure = result as DecodeResult.Failure
        assertTrue(failure.reason.contains("Invalid agent response JSON"))
        assertNotNull(failure.rawJson)
    }
}
