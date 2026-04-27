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
            return GenieModelConfig(
                modelId = id,
                modelFile = filename,
                commitHash = "local",
                sizeInBytes = 0L, // Size doesn't matter for local imports since we already have it
                displayName = "Custom: $filename",
                customUrl = "", // No download URL
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
