package com.akimy.genie.engine

import android.content.Context

/**
 * SharedPreferences helper for persisting the user's model choice.
 * Read by both [MainActivity] and [GenieAccessibilityService].
 */
object ModelPrefs {

    private const val PREFS_NAME = "genie_model_prefs"
    private const val KEY_SELECTED_MODEL_ID = "selected_model_id"

    /** Save the user's selected model. */
    fun setSelectedModelId(context: Context, modelId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_MODEL_ID, modelId)
            .apply()
    }

    /** Get the selected model id, or null if the user has never chosen. */
    fun getSelectedModelId(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_MODEL_ID, null)
    }

    /** Resolve the [GenieModelConfig] for the user's choice, or null if not set. */
    fun getSelectedConfig(context: Context): GenieModelConfig? {
        val id = getSelectedModelId(context) ?: return null

        if (id.startsWith("custom:")) {
            val filename = id.substringAfter("custom:")

            // Detect audio/image support from filename
            val supportsAudio = filename in listOf(
                "gemma-4-E2B-it.litertlm",
                "gemma-4-E4B-it.litertlm",
                "gemma-3n-E2B-it-int4.litertlm",
                "gemma-3n-E4B-it-int4.litertlm"
            )
            val supportsImage = supportsAudio // Same models support both

            return GenieModelConfig(
                modelId = id,
                modelFile = filename,
                commitHash = "local",
                sizeInBytes = 0L,
                displayName = "Custom: $filename",
                downloadUrl = "",
                supportsAudio = supportsAudio,
                supportsImage = supportsImage,
            )
        }

        return when (id) {
            GenieModelConfig.DEFAULT.modelId -> GenieModelConfig.DEFAULT
            GenieModelConfig.E2B.modelId -> GenieModelConfig.E2B
            GenieModelConfig.E4B.modelId -> GenieModelConfig.E4B
            else -> null
        }
    }
}
