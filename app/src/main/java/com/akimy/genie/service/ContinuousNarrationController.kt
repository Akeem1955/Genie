package com.akimy.genie.service

private const val NARRATION_DUPLICATE_WINDOW_MS = 2_500L

class ContinuousNarrationController {
    private var enabled = false
    private var lastNarration: String = "No narration yet."
    private var lastNarrationSignature: String? = null
    private var lastNarrationAtMs: Long = 0L

    fun enable(): String {
        enabled = true
        return "Continuous reader is now on."
    }

    fun disable(): String {
        enabled = false
        return "Continuous reader is now off."
    }

    fun toggle(): String {
        return if (enabled) disable() else enable()
    }

    fun isEnabled(): Boolean = enabled

    fun status(): String {
        return if (enabled) {
            "Continuous reader is on."
        } else {
            "Continuous reader is off."
        }
    }

    fun repeatLastNarration(): String = lastNarration

    fun rememberNarration(text: String, signature: String? = null) {
        val normalized = text.trim()
        if (normalized.isBlank()) return
        lastNarration = normalized
        if (signature != null) {
            lastNarrationSignature = signature
            lastNarrationAtMs = System.currentTimeMillis()
        }
    }

    fun narrationFor(record: AwarenessEventRecord?): String? {
        if (!enabled || record == null) return null
        if (!shouldNarrate(record)) return null

        val signature = "${record.type}|${record.summary}"
        val withinDuplicateWindow = record.timestampMs - lastNarrationAtMs < NARRATION_DUPLICATE_WINDOW_MS
        if (withinDuplicateWindow &&
            (lastNarrationSignature == signature || lastNarration == record.summary)
        ) {
            return null
        }

        lastNarrationSignature = signature
        lastNarrationAtMs = record.timestampMs
        rememberNarration(record.summary)
        return record.summary
    }

    private fun shouldNarrate(record: AwarenessEventRecord): Boolean {
        if (record.summary.isBlank()) return false

        return when (record.type) {
            "focus",
            "input_focus",
            "dialog",
            "keyboard",
            "notification",
            "toggle",
            "window",
            "selection" -> true

            "content" -> {
                val summary = record.summary.lowercase()
                "error" in summary ||
                    "state changed" in summary ||
                    "pane" in summary ||
                    "dialog" in summary ||
                    "availability changed" in summary
            }

            else -> false
        }
    }
}
