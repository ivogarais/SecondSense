package com.secondsense.llm

import android.util.Log
import android.os.SystemClock
import com.secondsense.debug.AgentLogStore
import kotlin.math.max

class JniLlamaRuntime : LlamaRuntime {

    private val lock = Any()

    @Volatile
    private var modelHandle: Long = 0L

    override fun isNativeAvailable(): Boolean = LlamaNativeBridge.isAvailable()

    override fun loadModel(
        modelPath: String,
        contextSize: Int,
        threads: Int
    ): LlamaLoadResult {
        synchronized(lock) {
            if (!LlamaNativeBridge.isAvailable()) {
                AgentLogStore.append(TAG, "loadModel failed: native library unavailable")
                return LlamaLoadResult.Failure(
                    "llama.cpp native library not loaded. Build the bundled ggml-org/llama.cpp JNI target."
                )
            }

            if (modelHandle != 0L) {
                LlamaNativeBridge.unloadModel(modelHandle)
                modelHandle = 0L
            }

            val startedAt = SystemClock.elapsedRealtime()
            AgentLogStore.append(
                TAG,
                "loadModel start path=$modelPath ctx=$contextSize threads=${max(1, threads)}"
            )
            val handle = LlamaNativeBridge.loadModel(
                modelPath = modelPath,
                contextSize = contextSize,
                threads = max(1, threads)
            )
            if (handle == 0L) {
                AgentLogStore.append(TAG, "loadModel failed: invalid model handle")
                return LlamaLoadResult.Failure("Native runtime returned an invalid model handle.")
            }

            modelHandle = handle
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            AgentLogStore.append(TAG, "loadModel done latencyMs=$elapsed")
            return LlamaLoadResult.Success(loadLatencyMs = elapsed)
        }
    }

    override fun unloadModel() {
        synchronized(lock) {
            if (modelHandle != 0L) {
                LlamaNativeBridge.unloadModel(modelHandle)
                modelHandle = 0L
            }
        }
    }

    override fun generate(
        prompt: String,
        config: LlamaGenerateConfig
    ): LlamaGenerateResult {
        val handle = modelHandle
        if (handle == 0L) {
            AgentLogStore.append(TAG, "generate skipped: no model loaded")
            return LlamaGenerateResult.Failure("No model loaded.")
        }
        if (!LlamaNativeBridge.isAvailable()) {
            AgentLogStore.append(TAG, "generate failed: native library unavailable")
            return LlamaGenerateResult.Failure("llama.cpp native library is unavailable.")
        }

        val startedAt = SystemClock.elapsedRealtime()
        val requestedMaxTokens = config.maxTokens ?: 0
        Log.d(
            TAG,
            "generate start promptChars=${prompt.length}, maxTokens=${config.maxTokens ?: "auto"}, temp=${config.temperature}, topP=${config.topP}"
        )
        AgentLogStore.append(
            TAG,
            "generate start promptChars=${prompt.length} maxTokens=${config.maxTokens ?: "auto"}"
        )
        val text = try {
            LlamaNativeBridge.generate(
                handle = handle,
                prompt = prompt,
                maxTokens = requestedMaxTokens,
                temperature = config.temperature,
                topP = config.topP
            )
        } catch (t: Throwable) {
            Log.e(TAG, "generate failed: ${t.message}", t)
            AgentLogStore.append(TAG, "generate failed: ${t.message ?: "unknown"}")
            return LlamaGenerateResult.Failure(t.message ?: "Native generation failed.")
        }

        if (text.isBlank()) {
            Log.e(TAG, "generate failed: native runtime returned empty response.")
            AgentLogStore.append(TAG, "generate failed: empty response")
            return LlamaGenerateResult.Failure("Native runtime returned an empty response.")
        }

        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val preview = text
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(520)
        Log.d(
            TAG,
            "generate done latencyMs=$elapsed outChars=${text.length} preview=${text.replace('\n', ' ').take(220)}"
        )
        AgentLogStore.append(TAG, "generate done latencyMs=$elapsed outChars=${text.length}")
        AgentLogStore.append(TAG, "generate preview=$preview")
        val outputTokenEstimate = estimateTokenCount(text)
        val avgLatency = if (outputTokenEstimate > 0) elapsed / outputTokenEstimate else null
        return LlamaGenerateResult.Success(
            text = text,
            metrics = LlamaGenerationMetrics(
                totalLatencyMs = elapsed,
                averageTokenLatencyMs = avgLatency
            )
        )
    }

    private fun estimateTokenCount(text: String): Long {
        val chunks = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return chunks.size.toLong()
    }

    private companion object {
        private const val TAG = "JniLlamaRuntime"
    }
}

private object LlamaNativeBridge {

    @Volatile
    private var loadAttempted = false

    @Volatile
    private var available = false

    fun isAvailable(): Boolean {
        if (loadAttempted) return available

        synchronized(this) {
            if (loadAttempted) return available
            available = tryLoadAnyLibrary()
            loadAttempted = true
            return available
        }
    }

    private fun tryLoadAnyLibrary(): Boolean {
        val candidates = listOf("secondsense_llama", "llama", "llama-jni", "llama_cpp")
        for (libraryName in candidates) {
            try {
                System.loadLibrary(libraryName)
                return true
            } catch (_: UnsatisfiedLinkError) {
                // Try next known library name.
            }
        }
        return false
    }

    fun loadModel(modelPath: String, contextSize: Int, threads: Int): Long {
        return nativeLoadModel(modelPath, contextSize, threads)
    }

    fun unloadModel(handle: Long) {
        nativeUnloadModel(handle)
    }

    fun generate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float
    ): String {
        return nativeGenerate(handle, prompt, maxTokens, temperature, topP)
    }

    external fun nativeLoadModel(modelPath: String, contextSize: Int, threads: Int): Long

    external fun nativeUnloadModel(handle: Long)

    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float
    ): String
}
