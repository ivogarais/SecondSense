package com.secondsense.llm

data class LlamaGenerateConfig(
    val maxTokens: Int? = null,
    val temperature: Float = 0.1f,
    val topP: Float = 0.9f
)

data class LlamaGenerationMetrics(
    val totalLatencyMs: Long,
    val averageTokenLatencyMs: Long?
)

sealed interface LlamaLoadResult {
    data class Success(
        val loadLatencyMs: Long
    ) : LlamaLoadResult

    data class Failure(
        val message: String
    ) : LlamaLoadResult
}

sealed interface LlamaGenerateResult {
    data class Success(
        val text: String,
        val metrics: LlamaGenerationMetrics
    ) : LlamaGenerateResult

    data class Failure(
        val message: String
    ) : LlamaGenerateResult
}

interface LlamaRuntime {
    fun isNativeAvailable(): Boolean

    fun loadModel(
        modelPath: String,
        contextSize: Int = 2048,
        threads: Int = 4
    ): LlamaLoadResult

    fun unloadModel()

    fun generate(
        prompt: String,
        config: LlamaGenerateConfig = LlamaGenerateConfig()
    ): LlamaGenerateResult
}
