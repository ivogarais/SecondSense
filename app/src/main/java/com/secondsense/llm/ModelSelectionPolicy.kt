package com.secondsense.llm

data class GenerationPerformanceStats(
    val firstTokenLatencyMs: Long? = null,
    val averageTokenLatencyMs: Long? = null
)

class ModelSelectionPolicy(
    private val q5FirstTokenLatencyThresholdMs: Long = 4500L,
    private val q5AverageTokenLatencyThresholdMs: Long = 320L
) {

    fun candidateOrder(preferred: LocalModelVariant = LocalModelVariant.preferredDefault()): List<LocalModelVariant> {
        val fallback = preferred.fallbackVariant()
        return if (preferred == fallback) {
            listOf(preferred)
        } else {
            listOf(preferred, fallback)
        }
    }

    fun shouldFallbackFromQ5(stats: GenerationPerformanceStats?): Boolean {
        if (stats == null) return false

        val firstTokenSlow = stats.firstTokenLatencyMs?.let { it > q5FirstTokenLatencyThresholdMs } ?: false
        val averageTokenSlow = stats.averageTokenLatencyMs?.let { it > q5AverageTokenLatencyThresholdMs } ?: false

        return firstTokenSlow || averageTokenSlow
    }
}
