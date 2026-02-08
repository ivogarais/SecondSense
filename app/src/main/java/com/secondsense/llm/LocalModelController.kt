package com.secondsense.llm

import android.content.Context
import com.secondsense.agent.AgentModelClient

class LocalModelController(
    appContext: Context
) {

    private val storage = ModelStorage(appContext.filesDir)
    private val downloadManager = ModelDownloadManager(storage)
    private val sessionManager = ModelSessionManager(
        storage = storage,
        runtime = JniLlamaRuntime()
    )
    private val agentModelClient: AgentModelClient = LlamaModelClient(sessionManager)

    fun preferredVariant(): LocalModelVariant = LocalModelVariant.preferredDefault()

    fun startPreferredDownload(): Boolean = downloadManager.start(preferredVariant())

    fun startDownload(variant: LocalModelVariant): Boolean = downloadManager.start(variant)

    fun pauseDownload(): Boolean = downloadManager.pause()

    fun resumeDownload(): Boolean = downloadManager.resume()

    fun cancelDownload(deletePartial: Boolean = false): Boolean = downloadManager.cancel(deletePartial)

    fun currentDownloadState(): ModelDownloadState = downloadManager.currentState()

    fun addDownloadStateListener(listener: (ModelDownloadState) -> Unit) {
        downloadManager.addListener(listener)
    }

    fun removeDownloadStateListener(listener: (ModelDownloadState) -> Unit) {
        downloadManager.removeListener(listener)
    }

    fun ensureModelLoaded(): EnsureModelLoadedResult = sessionManager.ensureLoaded()

    fun unloadModel() = sessionManager.unload()

    fun currentLoadedVariant(): LocalModelVariant? = sessionManager.loadedModelVariant()

    fun plannerClient(): AgentModelClient = agentModelClient
}
