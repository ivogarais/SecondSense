package com.secondsense.llm

import com.secondsense.agent.AgentModelClient

class LlamaModelClient(
    private val sessionManager: ModelSessionManager
) : AgentModelClient {

    override fun generate(prompt: String): String {
        val loadResult = sessionManager.ensureLoaded()
        if (loadResult !is EnsureModelLoadedResult.Ready) {
            val reason = when (loadResult) {
                is EnsureModelLoadedResult.MissingFiles -> {
                    val files = loadResult.variants.joinToString { it.fileName }
                    "Missing model files: $files"
                }

                is EnsureModelLoadedResult.Failed -> loadResult.message
                is EnsureModelLoadedResult.Ready -> "Unexpected load result state."
            }
            throw IllegalStateException(reason)
        }

        return when (val generation = sessionManager.generate(prompt)) {
            is LocalGenerationResult.Success -> generation.text
            is LocalGenerationResult.Failed -> throw IllegalStateException(generation.message)
        }
    }
}
