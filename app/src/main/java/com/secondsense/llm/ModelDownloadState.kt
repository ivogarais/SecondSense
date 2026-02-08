package com.secondsense.llm

sealed interface ModelDownloadState {
    data object Idle : ModelDownloadState

    data class Downloading(
        val variant: LocalModelVariant,
        val downloadedBytes: Long,
        val totalBytes: Long?
    ) : ModelDownloadState

    data class Paused(
        val variant: LocalModelVariant,
        val downloadedBytes: Long,
        val totalBytes: Long?
    ) : ModelDownloadState

    data class Verifying(
        val variant: LocalModelVariant
    ) : ModelDownloadState

    data class Completed(
        val variant: LocalModelVariant,
        val modelPath: String
    ) : ModelDownloadState

    data class Cancelled(
        val variant: LocalModelVariant,
        val keptPartialFile: Boolean
    ) : ModelDownloadState

    data class Failed(
        val variant: LocalModelVariant,
        val message: String,
        val recoverable: Boolean
    ) : ModelDownloadState
}
