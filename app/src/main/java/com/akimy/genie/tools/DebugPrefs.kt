package com.akimy.genie.tools

import android.content.Context

/**
 * SharedPreferences toggle for debug/test mode.
 * When enabled, the accessibility service skips LLM loading
 * and shows a manual tool-testing overlay instead.
 */
object DebugPrefs {
    private const val PREFS = "genie_debug"
    private const val KEY_DEBUG_MODE = "debug_mode_enabled"

    fun isDebugMode(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEBUG_MODE, false)
    }

    fun setDebugMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DEBUG_MODE, enabled)
            .apply()
    }
}
