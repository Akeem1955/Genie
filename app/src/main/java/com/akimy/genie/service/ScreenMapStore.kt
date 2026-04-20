package com.akimy.genie.service

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

private const val SCREEN_MAP_PREFS = "genie_screen_maps"
private const val SCREEN_MAP_PREFS_KEY = "maps_json"
private const val MAX_SCREEN_MAPS = 80
private const val MAX_HINTS = 5
private const val MAX_COUNT_BUCKETS = 10
private const val LEARN_DEBOUNCE_MS = 5_000L

@Serializable
data class PersonalizedScreenMap(
    val key: String,
    val packageName: String,
    val paneTitle: String? = null,
    val dialogLabel: String? = null,
    val visitCount: Int = 0,
    val firstSeenAtMs: Long = System.currentTimeMillis(),
    val lastSeenAtMs: Long = System.currentTimeMillis(),
    val headingCounts: Map<String, Int> = emptyMap(),
    val tabCounts: Map<String, Int> = emptyMap(),
    val toggleCounts: Map<String, Int> = emptyMap(),
    val actionableCounts: Map<String, Int> = emptyMap(),
    val focusCounts: Map<String, Int> = emptyMap(),
    val roleShortcutCounts: Map<String, Int> = emptyMap(),
    val userHints: List<String> = emptyList(),
)

object ScreenMapStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val screenMaps = ConcurrentHashMap<String, PersonalizedScreenMap>()
    private val lastLearnedAtMs = ConcurrentHashMap<String, Long>()
    @Volatile private var appContext: Context? = null
    @Volatile private var loaded = false

    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (!loaded) {
            load()
        }
    }

    fun learnSnapshot(snapshot: SemanticScreenSnapshot) {
        if (!isLearnable(snapshot)) return

        val now = System.currentTimeMillis()
        val key = buildKey(snapshot)
        val lastLearnedAt = lastLearnedAtMs[key] ?: 0L
        if (now - lastLearnedAt < LEARN_DEBOUNCE_MS) {
            return
        }
        lastLearnedAtMs[key] = now

        val existing = screenMaps[key]
        val updated = (existing ?: PersonalizedScreenMap(
            key = key,
            packageName = snapshot.packageName,
            paneTitle = snapshot.panes.firstOrNull(),
            dialogLabel = snapshot.dialogs.firstOrNull()?.label,
            firstSeenAtMs = now,
            lastSeenAtMs = now,
        )).copy(
            paneTitle = snapshot.panes.firstOrNull() ?: existing?.paneTitle,
            dialogLabel = snapshot.dialogs.firstOrNull()?.label ?: existing?.dialogLabel,
            visitCount = (existing?.visitCount ?: 0) + 1,
            lastSeenAtMs = now,
            headingCounts = mergeCounts(existing?.headingCounts, snapshot.headings.map { it.label }),
            tabCounts = mergeCounts(existing?.tabCounts, snapshot.tabs.map { it.label }),
            toggleCounts = mergeCounts(existing?.toggleCounts, snapshot.toggles.map { it.label }),
            actionableCounts = mergeCounts(
                existing?.actionableCounts,
                snapshot.actionableNodes.mapNotNull { it.label.takeIf(String::isNotBlank) },
            ),
            focusCounts = mergeCounts(
                existing?.focusCounts,
                listOfNotNull(snapshot.focusedNode?.label?.takeIf(String::isNotBlank)),
            ),
            roleShortcutCounts = mergeCounts(
                existing?.roleShortcutCounts,
                snapshot.actionableNodes
                    .mapNotNull { node ->
                        val label = node.label.takeIf(String::isNotBlank) ?: return@mapNotNull null
                        "${node.role}|$label"
                    }
            ),
        )

        screenMaps[key] = updated
        trimIfNeeded()
        persist()
    }

    fun describeCurrentScreen(snapshot: SemanticScreenSnapshot): String {
        if (!isLearnable(snapshot)) {
            return "No learned screen map is available for this screen yet."
        }

        val map = screenMaps[buildKey(snapshot)]
            ?: return "No learned screen map is available for this screen yet."

        val lines = mutableListOf<String>()
        val title = map.dialogLabel ?: map.paneTitle ?: appLabel(map.packageName)
        lines += "Learned screen map for $title. Seen ${map.visitCount} times."

        if (map.userHints.isNotEmpty()) {
            lines += "Remembered hints: ${map.userHints.joinToString(" | ")}"
        }

        topLabels(map.headingCounts)?.let { lines += "Frequent headings: $it" }
        topLabels(map.tabCounts)?.let { lines += "Frequent tabs: $it" }
        topLabels(map.toggleCounts)?.let { lines += "Frequent toggles: $it" }
        topLabels(map.actionableCounts)?.let { lines += "Frequent controls: $it" }
        topRoleShortcuts(map.roleShortcutCounts)?.let { lines += "Learned shortcuts: $it" }

        return lines.joinToString("\n")
    }

    fun saveUserHint(snapshot: SemanticScreenSnapshot, note: String): String {
        if (!isLearnable(snapshot)) {
            return "There is no stable screen visible to save a hint for."
        }

        val trimmed = note.trim()
        if (trimmed.isBlank()) {
            return "Hint was empty, so nothing was saved."
        }

        val key = buildKey(snapshot)
        val now = System.currentTimeMillis()
        val existing = screenMaps[key] ?: PersonalizedScreenMap(
            key = key,
            packageName = snapshot.packageName,
            paneTitle = snapshot.panes.firstOrNull(),
            dialogLabel = snapshot.dialogs.firstOrNull()?.label,
            firstSeenAtMs = now,
            lastSeenAtMs = now,
        )

        val updated = existing.copy(
            paneTitle = snapshot.panes.firstOrNull() ?: existing.paneTitle,
            dialogLabel = snapshot.dialogs.firstOrNull()?.label ?: existing.dialogLabel,
            lastSeenAtMs = now,
            userHints = (listOf(trimmed) + existing.userHints)
                .distinct()
                .take(MAX_HINTS),
        )

        screenMaps[key] = updated
        persist()
        return "Saved screen hint for ${updated.dialogLabel ?: updated.paneTitle ?: appLabel(updated.packageName)}."
    }

    private fun load() {
        val prefs = appContext?.getSharedPreferences(SCREEN_MAP_PREFS, Context.MODE_PRIVATE) ?: return
        val raw = prefs.getString(SCREEN_MAP_PREFS_KEY, null)
        if (raw.isNullOrBlank()) {
            loaded = true
            return
        }

        runCatching {
            json.decodeFromString<List<PersonalizedScreenMap>>(raw)
        }.onSuccess { loadedMaps ->
            screenMaps.clear()
            loadedMaps.forEach { map -> screenMaps[map.key] = map }
            loaded = true
        }.onFailure {
            screenMaps.clear()
            loaded = true
        }
    }

    private fun persist() {
        val prefs = appContext?.getSharedPreferences(SCREEN_MAP_PREFS, Context.MODE_PRIVATE) ?: return
        val payload = json.encodeToString(screenMaps.values.sortedByDescending { it.lastSeenAtMs }.take(MAX_SCREEN_MAPS))
        prefs.edit().putString(SCREEN_MAP_PREFS_KEY, payload).apply()
    }

    private fun trimIfNeeded() {
        if (screenMaps.size <= MAX_SCREEN_MAPS) return

        val sorted = screenMaps.values.sortedByDescending { it.lastSeenAtMs }
        val keep = sorted.take(MAX_SCREEN_MAPS).map { it.key }.toSet()
        screenMaps.keys.removeIf { it !in keep }
    }

    private fun mergeCounts(
        existing: Map<String, Int>?,
        values: List<String>,
    ): Map<String, Int> {
        val merged = existing.orEmpty().toMutableMap()
        values
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { value ->
                merged[value] = (merged[value] ?: 0) + 1
            }

        return merged.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(MAX_COUNT_BUCKETS)
            .associate { it.key to it.value }
    }

    private fun topLabels(counts: Map<String, Int>): String? {
        val labels = counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(4)
            .map { it.key }
        return labels.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private fun topRoleShortcuts(counts: Map<String, Int>): String? {
        if (counts.isEmpty()) return null

        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(6)
            .mapNotNull { entry ->
                val separator = entry.key.indexOf('|')
                if (separator <= 0) return@mapNotNull null
                val role = entry.key.substring(0, separator).replace('_', ' ')
                val label = entry.key.substring(separator + 1)
                "$role -> $label"
            }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" | ")
    }

    private fun isLearnable(snapshot: SemanticScreenSnapshot): Boolean {
        return snapshot.packageName.isNotBlank() && snapshot.visibleNodes.isNotEmpty()
    }

    private fun buildKey(snapshot: SemanticScreenSnapshot): String {
        val scope = snapshot.dialogs.firstOrNull()?.label
            ?: snapshot.panes.firstOrNull()
            ?: "root"
        return "${snapshot.packageName}|${scope.take(80)}"
    }

    private fun appLabel(packageName: String): String {
        return packageName.substringAfterLast('.').ifBlank { "current app" }
    }
}
