package com.secondsense.llm

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSessionManagerTest {

    @Test
    fun ensureLoaded_returnsMissingFiles_whenModelFileDoesNotExist() {
        val tempDir = Files.createTempDirectory("secondsense-model-session-missing").toFile()
        val storage = ModelStorage(tempDir)
        val runtime = FakeRuntime()
        val manager = ModelSessionManager(storage = storage, runtime = runtime)

        val result = manager.ensureLoaded()

        assertTrue(result is EnsureModelLoadedResult.MissingFiles)
        assertEquals(0, runtime.loadCallCount)
    }

    @Test
    fun ensureLoaded_reusesAlreadyLoadedModel_withoutReloading() {
        val tempDir = Files.createTempDirectory("secondsense-model-session-ready").toFile()
        val storage = ModelStorage(tempDir)
        val variant = LocalModelVariant.GEMMA_3_1B_IT_Q5_K_M
        val modelFile = storage.modelFile(variant)
        modelFile.parentFile?.mkdirs()
        modelFile.writeText("fake-gguf-bytes")

        val runtime = FakeRuntime()
        val manager = ModelSessionManager(storage = storage, runtime = runtime)

        val first = manager.ensureLoaded()
        val second = manager.ensureLoaded()

        assertTrue(first is EnsureModelLoadedResult.Ready)
        assertTrue(second is EnsureModelLoadedResult.Ready)
        assertEquals(1, runtime.loadCallCount)
        assertEquals(2048, runtime.lastLoadContextSize)
        assertTrue((runtime.lastLoadThreads ?: 0) >= 4)

        second as EnsureModelLoadedResult.Ready
        assertEquals(0L, second.loadLatencyMs)
    }

    @Test
    fun generate_returnsRuntimeOutput_afterLocalLoad() {
        val tempDir = Files.createTempDirectory("secondsense-model-session-generate").toFile()
        val storage = ModelStorage(tempDir)
        val variant = LocalModelVariant.GEMMA_3_1B_IT_Q5_K_M
        val modelFile = storage.modelFile(variant)
        modelFile.parentFile?.mkdirs()
        modelFile.writeText("fake-gguf-bytes")

        val runtime = FakeRuntime(
            generateResult = LlamaGenerateResult.Success(
                text = "{\"status\":\"done\"}",
                metrics = LlamaGenerationMetrics(totalLatencyMs = 12L, averageTokenLatencyMs = 2L)
            )
        )
        val manager = ModelSessionManager(storage = storage, runtime = runtime)

        val loadResult = manager.ensureLoaded()
        assertTrue(loadResult is EnsureModelLoadedResult.Ready)

        val generation = manager.generate("ping")

        assertTrue(generation is LocalGenerationResult.Success)
        assertEquals(1, runtime.generateCallCount)
        assertEquals(160, runtime.lastGenerateConfig?.maxTokens)
        assertEquals(0.2f, runtime.lastGenerateConfig?.temperature)
    }

    private class FakeRuntime(
        private val loadResult: LlamaLoadResult = LlamaLoadResult.Success(loadLatencyMs = 10L),
        private val generateResult: LlamaGenerateResult = LlamaGenerateResult.Success(
            text = "{}",
            metrics = LlamaGenerationMetrics(totalLatencyMs = 20L, averageTokenLatencyMs = 4L)
        )
    ) : LlamaRuntime {

        var loadCallCount = 0
            private set
        var generateCallCount = 0
            private set
        var lastGenerateConfig: LlamaGenerateConfig? = null
            private set
        var lastLoadContextSize: Int? = null
            private set
        var lastLoadThreads: Int? = null
            private set

        override fun isNativeAvailable(): Boolean = true

        override fun loadModel(modelPath: String, contextSize: Int, threads: Int): LlamaLoadResult {
            loadCallCount += 1
            lastLoadContextSize = contextSize
            lastLoadThreads = threads
            return loadResult
        }

        override fun unloadModel() = Unit

        override fun generate(prompt: String, config: LlamaGenerateConfig): LlamaGenerateResult {
            generateCallCount += 1
            lastGenerateConfig = config
            return generateResult
        }
    }
}
