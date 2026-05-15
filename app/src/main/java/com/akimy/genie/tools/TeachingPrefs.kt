package com.akimy.genie.tools

import android.content.Context

private const val PREFS_NAME = "genie_teaching_prefs"
private const val KEY_TEACHING_LANGUAGE = "teaching_language"
private const val DEFAULT_LANGUAGE = "English"

object TeachingPrefs {
    fun getTeachingLanguage(context: Context): String {
        return context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TEACHING_LANGUAGE, DEFAULT_LANGUAGE)
            ?: DEFAULT_LANGUAGE
    }

    fun setTeachingLanguage(context: Context, language: String) {
        val normalized = language.trim().ifEmpty { DEFAULT_LANGUAGE }
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TEACHING_LANGUAGE, normalized)
            .apply()
    }
}
