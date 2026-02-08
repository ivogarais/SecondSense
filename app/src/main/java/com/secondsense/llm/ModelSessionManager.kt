package com.secondsense.llm

import java.io.File

class ModelSessionManager(
    private val storage: ModelStorage,
    private val runtime: LlamaRuntime,
    private val selectionPolicy: ModelSelectionPolicy = ModelSelectionPolicy()
) {

    private val lock = Any()

    @Volatile
    private var loadedVariant: LocalModelVariant? = null

    @Volatile
    private var preferFallbackForNextLoad = false

    fun loadedModelVariant(): LocalModelVariant? = loadedVariant

    fun ensureLoaded(preferredVariant: LocalModelVariant? = null): EnsureModelLoadedResult {
        synchronized(lock) {
            val preferred = resolvePreferredVariant(preferredVariant)
            val currentLoaded = loadedVariant
            if (currentLoaded != null) {
                val currentFile = storage.modelFile(currentLoaded)
                if (isUsableModelFile(currentFile)) {
                    return EnsureModelLoadedResult.Ready(
                        variant = currentLoaded,
                        modelPath = currentFile.absolutePath,
                        usedFallback = currentLoaded != preferred,
                        loadLatencyMs = 0L
                    )
                }

                runtime.unloadModel()
                loadedVariant = null
            }

            val candidates = selectionPolicy.candidateOrder(preferred)
            val missing = mutableListOf<LocalModelVariant>()
            val failures = mutableListOf<String>()

            for (variant in candidates) {
                val file = storage.modelFile(variant)
                if (!isUsableModelFile(file)) {
                    missing += variant
                    continue
                }

                when (
                    val loadResult = runtime.loadModel(
                        modelPath = file.absolutePath,
                        contextSize = LOAD_CONTEXT_SIZE,
                        threads = preferredThreadCount()
                    )
                ) {
                    is LlamaLoadResult.Success -> {
                        loadedVariant = variant
                        storage.writePreferredVariant(variant)
                        preferFallbackForNextLoad = false
                        return EnsureModelLoadedResult.Ready(
                            variant = variant,
                            modelPath = file.absolutePath,
                            usedFallback = variant != preferred,
                            loadLatencyMs = loadResult.loadLatencyMs
                        )
                    }

                    is LlamaLoadResult.Failure -> {
                        failures += "${variant.fileName}: ${loadResult.message}"
                    }
                }
            }

            loadedVariant = null
            return if (missing.size == candidates.size) {
                EnsureModelLoadedResult.MissingFiles(missing)
            } else {
                EnsureModelLoadedResult.Failed(
                    message = failures.joinToString(separator = " | ")
                )
            }
        }
    }

    fun unload() {
        synchronized(lock) {
            runtime.unloadModel()
            loadedVariant = null
        }
    }

    fun generate(prompt: String): LocalGenerationResult {
        synchronized(lock) {
            val currentVariant = loadedVariant
            if (currentVariant == null) {
                return LocalGenerationResult.Failed("No model loaded.")
            }

            return when (val generateResult = runtime.generate(prompt, GENERATION_CONFIG)) {
                is LlamaGenerateResult.Success -> {
                    val fallbackCandidate = currentVariant.fallbackVariant()
                    if (
                        fallbackCandidate != currentVariant &&
                        selectionPolicy.shouldFallbackFromQ5(
                            GenerationPerformanceStats(
                                firstTokenLatencyMs = generateResult.metrics.totalLatencyMs,
                                averageTokenLatencyMs = generateResult.metrics.averageTokenLatencyMs
                            )
                        )
                    ) {
                        preferFallbackForNextLoad = true
                    }

                    LocalGenerationResult.Success(
                        text = generateResult.text,
                        variant = currentVariant,
                        metrics = generateResult.metrics
                    )
                }

                is LlamaGenerateResult.Failure -> {
                    LocalGenerationResult.Failed(generateResult.message)
                }
            }
        }
    }

    private fun resolvePreferredVariant(requested: LocalModelVariant?): LocalModelVariant {
        if (requested != null) return requested
        if (preferFallbackForNextLoad) {
            return (storage.readPreferredVariant() ?: LocalModelVariant.preferredDefault()).fallbackVariant()
        }
        return storage.readPreferredVariant() ?: LocalModelVariant.preferredDefault()
    }

    private fun isUsableModelFile(file: File): Boolean {
        return file.exists() && file.isFile && file.length() > 0L
    }

    private fun preferredThreadCount(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        val target = (cores - 1).coerceAtLeast(MIN_LOAD_THREADS)
        return target.coerceAtMost(MAX_LOAD_THREADS)
    }

    private companion object {
        private const val LOAD_CONTEXT_SIZE = 2048
        private const val MIN_LOAD_THREADS = 4
        private const val MAX_LOAD_THREADS = 8

        val GENERATION_CONFIG = LlamaGenerateConfig(
            maxTokens = 160,
            temperature = 0.2f,
            topP = 0.9f
        )
    }
}

sealed interface EnsureModelLoadedResult {
    data class Ready(
        val variant: LocalModelVariant,
        val modelPath: String,
        val usedFallback: Boolean,
        val loadLatencyMs: Long
    ) : EnsureModelLoadedResult

    data class MissingFiles(
        val variants: List<LocalModelVariant>
    ) : EnsureModelLoadedResult

    data class Failed(
        val message: String
    ) : EnsureModelLoadedResult
}

sealed interface LocalGenerationResult {
    data class Success(
        val text: String,
        val variant: LocalModelVariant,
        val metrics: LlamaGenerationMetrics
    ) : LocalGenerationResult

    data class Failed(
        val message: String
    ) : LocalGenerationResult
}
