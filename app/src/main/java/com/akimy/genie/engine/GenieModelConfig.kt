package com.akimy.genie.engine

import android.content.Context
import java.io.File

/**
 * Configuration for the Gemma model used by Genie.
 *
 * Simplified from Gallery's Model.kt — contains only the fields needed for
 * download, storage, and engine initialization. No UI-related config.
 *
 * The model path follows Gallery's convention:
 *   {getExternalFilesDir()}/{normalizedName}/{version}/{fileName}
 */
data class GenieModelConfig(
    /** HuggingFace model repository ID (e.g., "litert-community/Gemma4-2B-IT-4b") */
    val modelId: String,

    /** The model file name on HuggingFace */
    val modelFile: String,

    /** Git commit hash (version pinning) */
    val commitHash: String,

    /** Total file size in bytes (for download progress calculation) */
    val sizeInBytes: Long,

    /** Human-readable display name */
    val displayName: String,

    /** Optional custom download URL (overrides HF-constructed URL) */
    val customUrl: String? = null,
) {
    /** Normalized name for filesystem path (replace non-alphanumeric with underscores) */
    val normalizedName: String = Regex("[^a-zA-Z0-9]").replace(modelId, "_")

    /** Construct the full HuggingFace download URL */
    val downloadUrl: String
        get() = customUrl
            ?: "https://huggingface.co/$modelId/resolve/$commitHash/$modelFile?download=true"

    /**
     * Get the local file path where the model is stored.
     * Follows Gallery convention: externalFilesDir/normalizedName/commitHash/modelFile
     */
    fun getLocalPath(context: Context): String {
        val baseDir = context.getExternalFilesDir(null)?.absolutePath ?: ""
        return listOf(baseDir, normalizedName, commitHash, modelFile)
            .joinToString(File.separator)
    }

    /**
     * Get the local directory path for the model.
     */
    fun getLocalDir(context: Context): String {
        val baseDir = context.getExternalFilesDir(null)?.absolutePath ?: ""
        return listOf(baseDir, normalizedName, commitHash)
            .joinToString(File.separator)
    }

    /**
     * Check if the model file has already been downloaded.
     */
    fun isDownloaded(context: Context): Boolean {
        return File(getLocalPath(context)).exists()
    }

    companion object {
        /**
         * Default Gemma 4 2B 4-bit quantized model configuration.
         *
         * NOTE: Update modelId, commitHash, and sizeInBytes once the official
         * Gemma 4 .litertlm package is published to HuggingFace.
         */
        val DEFAULT = GenieModelConfig(
            modelId = "litert-community/Gemma4-2B-IT-4b",
            modelFile = "gemma4-2b-it-4b.litertlm",
            commitHash = "main",
            sizeInBytes = 1_500_000_000L, // ~1.5 GB estimated for 4-bit 2B model
            displayName = "Gemma 4 2B (4-bit)",
        )
    }
}
