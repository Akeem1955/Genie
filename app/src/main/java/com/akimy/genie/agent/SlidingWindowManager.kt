package com.akimy.genie.agent

import android.util.Log

private const val TAG = "GenieSlidingWindow"

/**
 * Manages a sliding window over the agent's conversation history.
 *
 * Enforces a fixed window size to prevent context overflow in the LLM.
 * Implements error pruning: when a step succeeds, any preceding
 * TransientErr entries for the same tool are removed.
 */
class SlidingWindowManager(
    private val maxWindowSize: Int = 10,
) {
    /**
     * Get the windowed history for prompt injection.
     * Applies FIFO truncation and error pruning.
     *
     * @param fullHistory The complete history from AgentState
     * @return A list of history entries within the window
     */
    fun getWindow(fullHistory: List<HistoryEntry>): List<HistoryEntry> {
        if (fullHistory.size <= maxWindowSize) {
            return fullHistory.toList()
        }

        // Always keep the first entry (UserMessage with the goal)
        val firstEntry = fullHistory.firstOrNull()
        val recentEntries = fullHistory.takeLast(maxWindowSize - 1)

        return if (firstEntry != null && firstEntry !in recentEntries) {
            listOf(firstEntry) + recentEntries
        } else {
            recentEntries
        }
    }

    /**
     * Prune transient errors from history after a successful step.
     *
     * When a tool succeeds, remove any immediately preceding TransientErr
     * entries for the same tool — they're noise that wastes context tokens.
     *
     * @param history The mutable history list to prune in-place
     */
    fun pruneAfterSuccess(history: MutableList<HistoryEntry>) {
        if (history.size < 2) return

        val lastEntry = history.lastOrNull()
        if (lastEntry !is HistoryEntry.ToolResult) return
        if (lastEntry.outcome !is ToolOutcome.Ok) return

        val successToolName = lastEntry.toolName

        // Walk backwards and remove TransientErr results for the same tool
        val iterator = history.listIterator(history.size - 1)
        val indicesToRemove = mutableListOf<Int>()

        var index = history.size - 2
        while (index >= 0) {
            val entry = history[index]
            if (entry is HistoryEntry.ToolResult &&
                entry.toolName == successToolName &&
                entry.outcome is ToolOutcome.TransientErr
            ) {
                indicesToRemove.add(index)

                // Also remove the ModelDecision that preceded this tool result
                if (index > 0 && history[index - 1] is HistoryEntry.ModelDecision) {
                    indicesToRemove.add(index - 1)
                    index -= 2
                } else {
                    index--
                }
            } else {
                break // Stop at the first non-transient-error entry
            }
        }

        if (indicesToRemove.isNotEmpty()) {
            Log.d(TAG, "Pruning ${indicesToRemove.size} transient error entries for '$successToolName'")
            indicesToRemove.sortedDescending().forEach { history.removeAt(it) }
        }
    }
}
