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
    /** Model identifier (e.g., "genie/gemma-4-E4B-it") */
    val modelId: String,

    /** The model file name */
    val modelFile: String,

    /** Git commit hash (version pinning) */
    val commitHash: String,

    /** Total file size in bytes (for download progress calculation) */
    val sizeInBytes: Long,

    /** Human-readable display name */
    val displayName: String,

    /** S3 download URL for the model file */
    val customUrl: String,
) {
    /** Normalized name for filesystem path (replace non-alphanumeric with underscores) */
    val normalizedName: String = Regex("[^a-zA-Z0-9]").replace(modelId, "_")

    /** Construct the download URL. Genie is S3-only for model distribution. */
    val downloadUrl: String
        get() = customUrl

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
        private const val S3_BASE =
            "https://simiebot-video-assets-072531718183-us-east-1-an.s3.us-east-1.amazonaws.com/Genie//"

        /**
         * Gemma 4 — Effective 2-Bit quantized (lighter).
         * Smaller on-device footprint (~900 MB), slightly lower quality.
         */
        val E2B = GenieModelConfig(
            modelId = "genie/gemma-4-E2B-it",
            modelFile = "gemma-4-E2B-it.litertlm",
            commitHash = "v1",
            sizeInBytes = 2_583_085_056L,
            displayName = "Gemma 4 (E2B)",
            customUrl = "${S3_BASE}gemma-4-E2B-it.litertlm",
        )

        /**
         * Gemma 4 — Effective 4-Bit quantized.
         * Higher quality, larger footprint (~1.5 GB).
         */
        val E4B = GenieModelConfig(
            modelId = "genie/gemma-4-E4B-it",
            modelFile = "gemma-4-E4B-it.litertlm",
            commitHash = "v1",
            sizeInBytes = 3_654_467_584L,
            displayName = "Gemma 4 (E4B)",
            customUrl = "${S3_BASE}gemma-4-E4B-it.litertlm",
        )

        /** Default model is E2B. */
        val DEFAULT = E2B

        /** All available model configs for the UI chooser. */
        val ALL = listOf(E2B, E4B)
    }
}
