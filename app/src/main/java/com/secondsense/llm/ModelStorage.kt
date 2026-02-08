package com.secondsense.llm

import java.io.File

class ModelStorage(
    private val appFilesDir: File
) {

    fun modelsDirectory(): File {
        val dir = File(appFilesDir, MODELS_DIRECTORY_NAME)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("Failed to create models directory: ${dir.absolutePath}")
        }
        if (!dir.isDirectory) {
            throw IllegalStateException("Models path is not a directory: ${dir.absolutePath}")
        }
        return dir
    }

    fun modelFile(variant: LocalModelVariant): File = safeResolve(variant.fileName)

    fun partialModelFile(variant: LocalModelVariant): File = safeResolve("${variant.fileName}.part")

    fun hasModel(variant: LocalModelVariant): Boolean {
        val file = modelFile(variant)
        return file.exists() && file.isFile && file.length() > 0L
    }

    fun readPreferredVariant(): LocalModelVariant? {
        val file = safeResolve(PREFERRED_MODEL_MARKER_FILE)
        if (!file.exists()) return null

        val value = file.readText().trim()
        return LocalModelVariant.entries.firstOrNull { it.name == value }
    }

    fun writePreferredVariant(variant: LocalModelVariant) {
        val file = safeResolve(PREFERRED_MODEL_MARKER_FILE)
        file.parentFile?.mkdirs()
        file.writeText(variant.name)
    }

    internal fun safeResolve(fileName: String): File {
        require(isSafeModelFileName(fileName)) {
            "Unsafe model file name: $fileName"
        }

        val root = modelsDirectory()
        val candidate = File(root, fileName)

        val rootPath = root.canonicalPath
        val candidatePath = candidate.canonicalPath
        val withinRoot = candidatePath == rootPath || candidatePath.startsWith("$rootPath${File.separator}")
        require(withinRoot) {
            "Resolved path escapes models directory: $candidatePath"
        }

        return candidate
    }

    companion object {
        private const val MODELS_DIRECTORY_NAME = "models"
        private const val PREFERRED_MODEL_MARKER_FILE = "preferred_model.txt"
        private val FILE_NAME_REGEX = Regex("^[A-Za-z0-9._-]+$")

        fun isSafeModelFileName(fileName: String): Boolean {
            if (fileName.isBlank()) return false
            return FILE_NAME_REGEX.matches(fileName)
        }
    }
}
