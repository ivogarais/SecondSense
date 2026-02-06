package com.secondsense.agent

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MockAgentModelClient : AgentModelClient {

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }

    private var turn = 0

    override fun generate(prompt: String): String {
        turn += 1

        val inInstagram = prompt.contains("\"currentAppPackage\":\"com.instagram.android\"")
        val hasMessagesNode =
            prompt.contains("\"text\":\"Messages\"") ||
                prompt.contains("\"contentDesc\":\"Messages\"")

        val response = when {
            turn > 6 -> AgentResponse(
                status = Status.DONE,
                result = "Stopped after mock max turns."
            )

            !inInstagram -> AgentResponse(
                status = Status.IN_PROGRESS,
                actions = listOf(
                    Action.OpenApp("Instagram"),
                    Action.Wait(ms = 900)
                )
            )

            hasMessagesNode -> AgentResponse(
                status = Status.IN_PROGRESS,
                actions = listOf(
                    Action.Click(selector = Selector(textContains = "Messages")),
                    Action.Wait(ms = 600)
                )
            )

            else -> AgentResponse(
                status = Status.DONE,
                result = "Reached Instagram context in mock mode."
            )
        }

        return json.encodeToString(response)
    }
}
