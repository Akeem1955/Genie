package com.akimy.genie.service

import android.app.Notification
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

private const val MAX_AWARENESS_EVENTS = 20
private const val MAX_NOTIFICATION_EVENTS = 10
private const val DUPLICATE_EVENT_WINDOW_MS = 1_500L

class AccessibilityAwarenessTracker {
    private val recentEvents = ArrayDeque<AwarenessEventRecord>()
    private val notificationEvents = ArrayDeque<AwarenessEventRecord>()
    private val recentEventSignatures = mutableMapOf<String, Long>()

    private var previousScreenSnapshot: SemanticScreenSnapshot = SemanticScreenSnapshot.empty()
    private var latestScreenDiff: SemanticScreenDiff = SemanticScreenDiff.empty()

    var latestScreenSnapshot: SemanticScreenSnapshot = SemanticScreenSnapshot.empty()
        private set

    fun onAccessibilityEvent(event: AccessibilityEvent?, root: AccessibilityNodeInfo?): AwarenessEventRecord? {
        if (event == null || !shouldTrackEvent(event)) return null

        if (root != null) {
            updateSnapshot(root, recomputeDiff = true)
        }

        val record = summarizeEvent(event, previousScreenSnapshot, latestScreenSnapshot, latestScreenDiff)
            ?: return null
        if (!shouldRecord(record)) {
            return null
        }
        recordEvent(record)
        if (record.type == "notification") {
            notificationEvents += record
            while (notificationEvents.size > MAX_NOTIFICATION_EVENTS) {
                notificationEvents.removeFirst()
            }
        }
        return record
    }

    fun readFocused(root: AccessibilityNodeInfo?): String {
        refreshSnapshot(root)
        return latestScreenSnapshot.focusedNode?.let(UINodeParser::describeSemanticNode)
            ?: "Nothing is focused right now."
    }

    fun readScreenSummary(root: AccessibilityNodeInfo?): String {
        refreshSnapshot(root)
        return latestScreenSnapshot.summary
    }

    fun whereAmI(root: AccessibilityNodeInfo?): String {
        refreshSnapshot(root)
        val snapshot = latestScreenSnapshot
        val parts = mutableListOf<String>()
        parts += snapshot.summary
        snapshot.focusedNode?.let { parts += "Current item: ${UINodeParser.describeSemanticNode(it)}" }
        val landmarks = UINodeParser.describeLandmarks(root)
        if (landmarks.isNotBlank()) {
            parts += landmarks
        }
        val learnedMap = ScreenMapStore.describeCurrentScreen(snapshot)
        if (!learnedMap.startsWith("No learned screen map", ignoreCase = true)) {
            parts += learnedMap
        }
        return parts.joinToString("\n")
    }

    fun readNearbyContext(root: AccessibilityNodeInfo?): String {
        refreshSnapshot(root)
        return UINodeParser.describeNearbyContext(root)
    }

    fun readDialog(root: AccessibilityNodeInfo?): String {
        refreshSnapshot(root)
        return UINodeParser.describeDialog(root)
    }

    fun readFormState(root: AccessibilityNodeInfo?): String {
        refreshSnapshot(root)
        return UINodeParser.describeFormState(root)
    }

    fun whatCanIDoHere(root: AccessibilityNodeInfo?): String {
        refreshSnapshot(root)
        return UINodeParser.describeAvailableActions(root)
    }

    fun readScreenChanges(): String {
        return latestScreenDiff.summary
    }

    fun readNotifications(limit: Int = 5): String {
        if (notificationEvents.isEmpty()) {
            return "No recent notifications."
        }

        return notificationEvents
            .toList()
            .takeLast(limit.coerceIn(1, MAX_NOTIFICATION_EVENTS))
            .joinToString("\n") { it.summary }
    }

    fun readRecentEvents(limit: Int = 5): String {
        if (recentEvents.isEmpty()) {
            return "No recent accessibility events."
        }

        return recentEvents
            .toList()
            .takeLast(limit.coerceIn(1, MAX_AWARENESS_EVENTS))
            .joinToString("\n") { "${it.type}: ${it.summary}" }
    }

    private fun refreshSnapshot(root: AccessibilityNodeInfo?) {
        updateSnapshot(root, recomputeDiff = false)
    }

    private fun updateSnapshot(root: AccessibilityNodeInfo?, recomputeDiff: Boolean) {
        if (root == null) return

        val prior = latestScreenSnapshot
        val current = UINodeParser.extractSemanticScreen(root)
        ScreenMapStore.learnSnapshot(current)
        if (recomputeDiff) {
            previousScreenSnapshot = prior
            latestScreenDiff = UINodeParser.diffScreens(prior, current)
        }
        latestScreenSnapshot = current
    }

    private fun shouldTrackEvent(event: AccessibilityEvent): Boolean {
        return when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED,
            AccessibilityEvent.TYPE_ANNOUNCEMENT,
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> true

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val changeTypes = event.contentChangeTypes
                changeTypes == 0 ||
                    changeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT != 0 ||
                    changeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION != 0 ||
                    changeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION != 0 ||
                    changeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_ENABLED != 0 ||
                    changeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_ERROR != 0 ||
                    changeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED != 0 ||
                    changeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED != 0 ||
                    changeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_TITLE != 0 ||
                    changeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE != 0
            }

            else -> false
        }
    }

    private fun summarizeEvent(
        event: AccessibilityEvent,
        previousSnapshot: SemanticScreenSnapshot,
        currentSnapshot: SemanticScreenSnapshot,
        screenDiff: SemanticScreenDiff,
    ): AwarenessEventRecord? {
        val textSummary = event.text
            ?.map { it?.toString().orEmpty().trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.joinToString(" ")
            .orEmpty()

        val packageName = event.packageName?.toString().orEmpty()
        val className = event.className?.toString().orEmpty()

        val summary = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val focused = currentSnapshot.focusedNode
                focused?.let(UINodeParser::describeSemanticNode)
                    ?: textSummary.ifBlank { "Focus changed." }
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                when {
                    isKeyboardEvent(packageName, className, textSummary) -> "Keyboard opened."
                    currentSnapshot.dialogs.firstOrNull() != null ->
                        "Dialog appeared: ${currentSnapshot.dialogs.first().label}"
                    currentSnapshot.panes.firstOrNull() != null ->
                        "Window changed: ${currentSnapshot.panes.first()}"
                    textSummary.isNotBlank() -> textSummary
                    className.isNotBlank() -> "Window changed in ${packageName.ifBlank { "current app" }}"
                    else -> null
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                detectStructuredChange(screenDiff)
                    ?: summarizeContentChange(event, currentSnapshot)
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val focused = currentSnapshot.focusedNode?.label
                if (textSummary.isNotBlank()) {
                    "Text changed${focused?.let { " in $it" }.orEmpty()}: $textSummary"
                } else {
                    detectStructuredChange(screenDiff)
                }
            }

            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                describeNotification(event, packageName, textSummary)
            }

            AccessibilityEvent.TYPE_ANNOUNCEMENT -> textSummary.takeIf { it.isNotBlank() }
            AccessibilityEvent.TYPE_VIEW_SELECTED -> textSummary.ifBlank { "Selection changed." }
            else -> detectStructuredChange(screenDiff)
        }?.trim()?.takeIf { it.isNotBlank() } ?: return null

        return AwarenessEventRecord(
            timestampMs = System.currentTimeMillis(),
            type = describeEventType(event.eventType, summary),
            summary = summary,
        )
    }

    private fun summarizeContentChange(
        event: AccessibilityEvent,
        currentSnapshot: SemanticScreenSnapshot,
    ): String? {
        val changeTypes = event.contentChangeTypes
        val focusedLabel = currentSnapshot.focusedNode?.label

        return when {
            changeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED != 0 ->
                currentSnapshot.panes.firstOrNull()?.let { "Pane appeared: $it" }

            changeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED != 0 ->
                "Pane dismissed."

            changeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_TITLE != 0 ->
                currentSnapshot.panes.firstOrNull()?.let { "Pane updated: $it" }

            changeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_ERROR != 0 ->
                currentSnapshot.focusedNode?.errorText?.let { "Error: $it" }

            changeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_ENABLED != 0 && focusedLabel != null ->
                "$focusedLabel availability changed."

            changeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION != 0 && focusedLabel != null ->
                currentSnapshot.focusedNode?.stateDescription?.let {
                    "$focusedLabel state changed: $it"
                }

            changeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT != 0 && focusedLabel != null ->
                "Visible text changed near $focusedLabel."

            changeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION != 0 && focusedLabel != null ->
                "$focusedLabel description changed."

            else -> null
        }
    }

    private fun describeNotification(
        event: AccessibilityEvent,
        packageName: String,
        fallbackText: String,
    ): String {
        val source = packageName.substringAfterLast('.').ifBlank { "notification" }
        val notification = event.parcelableData as? Notification
        val title = notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val text = notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val detail = listOfNotNull(title, text).distinct().joinToString(": ")
            .ifBlank { fallbackText }

        return if (detail.isNotBlank()) {
            "Notification from $source: $detail"
        } else {
            "Notification from $source."
        }
    }

    private fun detectStructuredChange(screenDiff: SemanticScreenDiff): String? {
        return screenDiff.changes.firstOrNull()?.summary
    }

    private fun shouldRecord(record: AwarenessEventRecord): Boolean {
        val signature = "${record.type}|${record.summary}"
        val lastTimestamp = recentEventSignatures[signature]
        if (lastTimestamp != null && record.timestampMs - lastTimestamp < DUPLICATE_EVENT_WINDOW_MS) {
            return false
        }

        recentEventSignatures[signature] = record.timestampMs
        recentEventSignatures.entries.removeIf { (_, timestamp) ->
            record.timestampMs - timestamp > DUPLICATE_EVENT_WINDOW_MS * 4
        }
        return true
    }

    private fun recordEvent(record: AwarenessEventRecord) {
        recentEvents += record
        while (recentEvents.size > MAX_AWARENESS_EVENTS) {
            recentEvents.removeFirst()
        }
    }

    private fun describeEventType(eventType: Int, summary: String): String {
        return when {
            summary.startsWith("Notification", ignoreCase = true) -> "notification"
            summary.startsWith("Dialog", ignoreCase = true) -> "dialog"
            summary.startsWith("Keyboard", ignoreCase = true) -> "keyboard"
            summary.contains(" is now on", ignoreCase = true) ||
                summary.contains(" is now off", ignoreCase = true) -> "toggle"
            eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> "focus"
            eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED -> "input_focus"
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "window"
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "content"
            eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "text"
            eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> "notification"
            eventType == AccessibilityEvent.TYPE_ANNOUNCEMENT -> "announcement"
            eventType == AccessibilityEvent.TYPE_VIEW_SELECTED -> "selection"
            else -> "event"
        }
    }

    private fun isKeyboardEvent(packageName: String, className: String, textSummary: String): Boolean {
        val packageLower = packageName.lowercase()
        val classLower = className.lowercase()
        val textLower = textSummary.lowercase()

        return "inputmethod" in packageLower ||
            "keyboard" in packageLower ||
            "keyboard" in classLower ||
            "inputmethod" in classLower ||
            "keyboard" in textLower
    }
}
