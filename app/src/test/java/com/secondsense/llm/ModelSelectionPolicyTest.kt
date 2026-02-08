package com.secondsense.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSelectionPolicyTest {

    @Test
    fun candidateOrder_prefersConfiguredDefault() {
        val policy = ModelSelectionPolicy()

        val order = policy.candidateOrder(LocalModelVariant.GEMMA_3_1B_IT_Q5_K_M)

        assertEquals(listOf(LocalModelVariant.GEMMA_3_1B_IT_Q5_K_M), order)
    }

    @Test
    fun shouldFallbackFromQ5_whenAverageLatencyTooHigh() {
        val policy = ModelSelectionPolicy(q5AverageTokenLatencyThresholdMs = 200L)

        val shouldFallback = policy.shouldFallbackFromQ5(
            GenerationPerformanceStats(
                firstTokenLatencyMs = 1_000L,
                averageTokenLatencyMs = 240L
            )
        )

        assertTrue(shouldFallback)
    }

    @Test
    fun shouldFallbackFromQ5_whenLatencyAcceptable() {
        val policy = ModelSelectionPolicy(q5AverageTokenLatencyThresholdMs = 400L)

        val shouldFallback = policy.shouldFallbackFromQ5(
            GenerationPerformanceStats(
                firstTokenLatencyMs = 1_200L,
                averageTokenLatencyMs = 180L
            )
        )

        assertFalse(shouldFallback)
    }
}
