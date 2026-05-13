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
    /** Model identifier (e.g., "litert-community/gemma-4-E2B-it-litert-lm") */
    val modelId: String,

    /** The model file name */
    val modelFile: String,

    /** Git commit hash (version pinning) */
    val commitHash: String,

    /** Total file size in bytes (for download progress calculation) */
    val sizeInBytes: Long,

    /** Human-readable display name */
    val displayName: String,

    /** HuggingFace download URL for the model file */
    val downloadUrl: String,

    /** Whether this model supports audio input */
    val supportsAudio: Boolean = false,

    /** Whether this model supports image input */
    val supportsImage: Boolean = false,
) {
    /** Normalized name for filesystem path (replace non-alphanumeric with underscores) */
    val normalizedName: String = Regex("[^a-zA-Z0-9]").replace(modelId, "_")

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
         * Gemma 4 E2B — Effective 2-Bit quantized with audio + image support.
         * From Gallery's model allowlist (matches Google's exact config).
         * 2.4 GB, 32K context, supports text/audio/image input.
         */
        val E2B = GenieModelConfig(
            modelId = "litert-community/gemma-4-E2B-it-litert-lm",
            modelFile = "gemma-4-E2B-it.litertlm",
            commitHash = "7fa1d78473894f7e736a21d920c3aa80f950c0db",
            sizeInBytes = 2_583_085_056L,
            displayName = "Gemma 4 (E2B)",
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/7fa1d78473894f7e736a21d920c3aa80f950c0db/gemma-4-E2B-it.litertlm?download=true",
            supportsAudio = true,
            supportsImage = true,
        )

        /**
         * Gemma 4 E4B — Effective 4-Bit quantized with audio + image support.
         * From Gallery's model allowlist (matches Google's exact config).
         * 3.4 GB, 32K context, supports text/audio/image input.
         */
        val E4B = GenieModelConfig(
            modelId = "litert-community/gemma-4-E4B-it-litert-lm",
            modelFile = "gemma-4-E4B-it.litertlm",
            commitHash = "9695417f248178c63a9f318c6e0c56cb917cb837",
            sizeInBytes = 3_654_467_584L,
            displayName = "Gemma 4 (E4B)",
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/9695417f248178c63a9f318c6e0c56cb917cb837/gemma-4-E4B-it.litertlm?download=true",
            supportsAudio = true,
            supportsImage = true,
        )

        /** Default model is E2B. */
        val DEFAULT = E2B

        /** All available model configs for the UI chooser. */
        val ALL = listOf(E2B, E4B)
    }
}
