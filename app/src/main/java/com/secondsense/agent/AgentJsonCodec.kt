package com.secondsense.agent

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

object AgentJsonCodec {

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = true
        classDiscriminator = "type"
    }

    fun decode(raw: String): DecodeResult {
        val payload = extractFirstJsonObject(raw)
            ?: return DecodeResult.Failure(
                reason = "No JSON object found in model output.",
                rawJson = null
            )

        return try {
            val response = json.decodeFromString<AgentResponse>(payload)
            DecodeResult.Success(response)
        } catch (e: SerializationException) {
            DecodeResult.Failure(
                reason = "Invalid agent response JSON: ${e.message}",
                rawJson = payload
            )
        }
    }

    fun extractJsonObject(raw: String): String? {
        return extractFirstJsonObject(raw)
    }

    private fun extractFirstJsonObject(text: String): String? {
        var depth = 0
        var start = -1
        var inString = false
        var escaping = false

        for (i in text.indices) {
            val ch = text[i]

            if (inString) {
                if (escaping) {
                    escaping = false
                } else if (ch == '\\') {
                    escaping = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }

            when (ch) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) {
                        start = i
                    }
                    depth += 1
                }
                '}' -> {
                    if (depth > 0) {
                        depth -= 1
                        if (depth == 0 && start >= 0) {
                            return text.substring(start, i + 1)
                        }
                    }
                }
            }
        }

        return null
    }
}

sealed interface DecodeResult {
    data class Success(val response: AgentResponse) : DecodeResult

    data class Failure(
        val reason: String,
        val rawJson: String?
    ) : DecodeResult
}
