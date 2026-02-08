package com.secondsense.llm

import android.util.Log
import com.secondsense.debug.AgentLogStore
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

class ModelDownloadManager(
    private val storage: ModelStorage,
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
        (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 20_000
            instanceFollowRedirects = true
        }
    }
) {

    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<(ModelDownloadState) -> Unit>()

    @Volatile
    private var state: ModelDownloadState = ModelDownloadState.Idle

    @Volatile
    private var activeVariant: LocalModelVariant? = null

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var pauseRequested = false

    @Volatile
    private var cancelRequested = false

    @Volatile
    private var deletePartialOnCancel = false

    fun currentState(): ModelDownloadState = state

    fun addListener(listener: (ModelDownloadState) -> Unit) {
        listeners += listener
        listener(state)
    }

    fun removeListener(listener: (ModelDownloadState) -> Unit) {
        listeners -= listener
    }

    fun start(variant: LocalModelVariant): Boolean {
        synchronized(lock) {
            if (worker?.isAlive == true) return false

            pauseRequested = false
            cancelRequested = false
            deletePartialOnCancel = false
            activeVariant = variant

            worker = Thread {
                runDownload(variant)
            }.apply {
                name = "model-download-${variant.name.lowercase()}"
                isDaemon = true
                start()
            }
            return true
        }
    }

    fun pause(): Boolean {
        synchronized(lock) {
            if (worker?.isAlive != true) return false
            pauseRequested = true
            return true
        }
    }

    fun resume(): Boolean {
        val variant = activeVariant ?: return false
        synchronized(lock) {
            if (worker?.isAlive == true) return false
            pauseRequested = false
            cancelRequested = false
            deletePartialOnCancel = false

            worker = Thread {
                runDownload(variant)
            }.apply {
                name = "model-download-${variant.name.lowercase()}-resume"
                isDaemon = true
                start()
            }
            return true
        }
    }

    fun cancel(deletePartial: Boolean): Boolean {
        synchronized(lock) {
            val alive = worker?.isAlive == true
            val paused = state is ModelDownloadState.Paused
            if (!alive && !paused) return false

            cancelRequested = true
            deletePartialOnCancel = deletePartial

            if (paused) {
                val variant = activeVariant
                if (variant != null) {
                    if (deletePartial) {
                        storage.partialModelFile(variant).delete()
                    }
                    emitState(
                        ModelDownloadState.Cancelled(
                            variant = variant,
                            keptPartialFile = !deletePartial
                        )
                    )
                } else {
                    emitState(ModelDownloadState.Idle)
                }
            }

            return true
        }
    }

    private fun runDownload(variant: LocalModelVariant) {
        try {
            val modelFile = storage.modelFile(variant)
            if (modelFile.exists()) {
                val existingHash = computeSha256(modelFile)
                if (hashMatches(existingHash, variant.expectedSha256)) {
                    storage.writePreferredVariant(variant)
                    emitState(
                        ModelDownloadState.Completed(
                            variant = variant,
                            modelPath = modelFile.absolutePath
                        )
                    )
                    return
                }
            }

            val partialFile = storage.partialModelFile(variant)
            val downloadResult = downloadToPartialFile(variant, partialFile)
            if (!downloadResult.completed) return

            if (cancelRequested) {
                handleCancelled(variant, partialFile)
                return
            }
            if (pauseRequested) {
                emitState(
                    ModelDownloadState.Paused(
                        variant = variant,
                        downloadedBytes = partialFile.length(),
                        totalBytes = downloadResult.totalBytes
                    )
                )
                return
            }

            val moved = movePartialToFinal(partialFile, modelFile)
            if (!moved) {
                emitState(
                    ModelDownloadState.Failed(
                        variant = variant,
                        message = "Failed moving model from partial to final path.",
                        recoverable = true
                    )
                )
                return
            }

            emitState(ModelDownloadState.Verifying(variant))
            val actualHash = computeSha256(modelFile)
            if (!hashMatches(actualHash, variant.expectedSha256)) {
                val message =
                    "Checksum verification failed for ${variant.fileName}. expected=${variant.expectedSha256.lowercase()} actual=$actualHash"
                Log.e(TAG, message)
                AgentLogStore.append(TAG, message)
                modelFile.delete()
                emitState(
                    ModelDownloadState.Failed(
                        variant = variant,
                        message = message,
                        recoverable = false
                    )
                )
                return
            }

            storage.writePreferredVariant(variant)
            emitState(
                ModelDownloadState.Completed(
                    variant = variant,
                    modelPath = modelFile.absolutePath
                )
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Model download failed: ${t.message}", t)
            emitState(
                ModelDownloadState.Failed(
                    variant = variant,
                    message = t.message ?: "Unknown download failure.",
                    recoverable = true
                )
            )
        } finally {
            synchronized(lock) {
                worker = null
                if (state is ModelDownloadState.Cancelled) {
                    pauseRequested = false
                    cancelRequested = false
                    deletePartialOnCancel = false
                }
            }
        }
    }

    private data class DownloadResult(
        val completed: Boolean,
        val totalBytes: Long?
    )

    private fun downloadToPartialFile(
        variant: LocalModelVariant,
        partialFile: File
    ): DownloadResult {
        partialFile.parentFile?.mkdirs()

        val initialBytes = partialFile.takeIf(File::exists)?.length() ?: 0L
        val connection = openConnection(variant.downloadUrl, initialBytes)

        try {
            val responseCode = connection.responseCode
            val supportsResume = responseCode == HttpURLConnection.HTTP_PARTIAL
            val append = supportsResume && initialBytes > 0L
            val startingBytes = if (append) initialBytes else 0L

            if (!supportsResume && initialBytes > 0L) {
                partialFile.delete()
            }

            val contentLength = connection.contentLengthLong.takeIf { it > 0L }
            val totalBytes = when {
                contentLength == null -> null
                supportsResume -> contentLength + startingBytes
                else -> contentLength
            }

            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(partialFile, append).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var downloaded = startingBytes
                    emitState(
                        ModelDownloadState.Downloading(
                            variant = variant,
                            downloadedBytes = downloaded,
                            totalBytes = totalBytes
                        )
                    )

                    while (true) {
                        if (cancelRequested) {
                            handleCancelled(variant, partialFile)
                            return DownloadResult(completed = false, totalBytes = totalBytes)
                        }
                        if (pauseRequested) {
                            emitState(
                                ModelDownloadState.Paused(
                                    variant = variant,
                                    downloadedBytes = downloaded,
                                    totalBytes = totalBytes
                                )
                            )
                            return DownloadResult(completed = false, totalBytes = totalBytes)
                        }

                        val read = input.read(buffer)
                        if (read == -1) break

                        output.write(buffer, 0, read)
                        val previousDownloaded = downloaded
                        downloaded += read.toLong()

                        val previousBucket = previousDownloaded / PROGRESS_EMIT_INTERVAL_BYTES
                        val currentBucket = downloaded / PROGRESS_EMIT_INTERVAL_BYTES
                        val shouldEmit = currentBucket > previousBucket
                        if (shouldEmit) {
                            emitState(
                                ModelDownloadState.Downloading(
                                    variant = variant,
                                    downloadedBytes = downloaded,
                                    totalBytes = totalBytes
                                )
                            )
                        }
                    }

                    output.flush()
                    emitState(
                        ModelDownloadState.Downloading(
                            variant = variant,
                            downloadedBytes = downloaded,
                            totalBytes = totalBytes
                        )
                    )
                }
            }

            return DownloadResult(completed = true, totalBytes = totalBytes)
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String, offset: Long): HttpURLConnection {
        val connection = connectionFactory(URL(url))
        if (offset > 0L) {
            connection.setRequestProperty("Range", "bytes=$offset-")
        }
        connection.connect()

        val code = connection.responseCode
        if (code !in setOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
            throw IllegalStateException("Download request failed with HTTP $code")
        }
        return connection
    }

    private fun movePartialToFinal(partialFile: File, modelFile: File): Boolean {
        if (!partialFile.exists()) return false
        modelFile.parentFile?.mkdirs()
        if (modelFile.exists() && !modelFile.delete()) {
            return false
        }

        if (partialFile.renameTo(modelFile)) return true

        return try {
            FileInputStream(partialFile).use { input ->
                FileOutputStream(modelFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }
            partialFile.delete()
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hashMatches(actualHash: String, expectedHash: String): Boolean {
        return actualHash.equals(expectedHash.trim().lowercase(), ignoreCase = true)
    }

    private fun handleCancelled(variant: LocalModelVariant, partialFile: File) {
        if (deletePartialOnCancel) {
            partialFile.delete()
        }
        emitState(
            ModelDownloadState.Cancelled(
                variant = variant,
                keptPartialFile = !deletePartialOnCancel && partialFile.exists()
            )
        )
    }

    private fun emitState(newState: ModelDownloadState) {
        state = newState
        listeners.forEach { listener ->
            try {
                listener(newState)
            } catch (_: Throwable) {
                // Keep delivery best-effort for all listeners.
            }
        }
    }

    companion object {
        private const val TAG = "ModelDownloadManager"
        private const val BUFFER_SIZE = 32 * 1024
        private const val PROGRESS_EMIT_INTERVAL_BYTES = 512L * 1024L
    }
}
