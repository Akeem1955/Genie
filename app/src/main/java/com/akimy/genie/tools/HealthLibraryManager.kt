package com.akimy.genie.tools

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader

private const val TAG = "HealthLibraryManager"

/**
 * Manages the local health topics JSON database.
 * Treats the 150k token JSON as a queryable database, loading only specific entries.
 */
class HealthLibraryManager(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // Cache for topic index (loaded once)
    private var topicIndex: HealthTopicIndex? = null

    /**
     * Load the topic index (just disease names, not full data).
     * This is small (~500-1000 tokens) and can be shared with model.
     */
    fun loadTopicIndex(): HealthTopicIndex {
        if (topicIndex != null) return topicIndex!!

        try {
            val rawJson = readRawJsonFile()
            val jsonArray = json.parseToJsonElement(rawJson).jsonArray

            val topics = jsonArray.mapNotNull { element ->
                try {
                    element.jsonObject["disease"]?.jsonPrimitive?.content
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse topic name: ${e.message}")
                    null
                }
            }

            val index = HealthTopicIndex(
                topics = topics.sorted(),
                totalCount = topics.size
            )
            topicIndex = index
            Log.d(TAG, "Loaded ${index.totalCount} health topics")
            return index

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load topic index", e)
            return HealthTopicIndex(emptyList(), 0)
        }
    }

    /**
     * Query a specific health topic by disease name.
     * Returns only that entry's data (1-3k tokens).
     */
    fun queryTopic(diseaseName: String): HealthRecord? {
        try {
            val rawJson = readRawJsonFile()
            val jsonArray = json.parseToJsonElement(rawJson).jsonArray

            for (element in jsonArray) {
                val obj = element.jsonObject
                val disease = obj["disease"]?.jsonPrimitive?.content ?: continue

                if (disease.equals(diseaseName, ignoreCase = true)) {
                    return parseHealthRecord(obj)
                }
            }

            Log.w(TAG, "No match found for disease: $diseaseName")
            return null

        } catch (e: Exception) {
            Log.e(TAG, "Failed to query topic: $diseaseName", e)
            return null
        }
    }

    /**
     * Search for topics matching a query string (case-insensitive contains).
     * Returns list of matching disease names.
     */
    fun searchTopics(query: String): List<String> {
        val index = loadTopicIndex()
        val normalized = query.trim().lowercase()

        if (normalized.isEmpty()) return emptyList()

        return index.topics.filter { topic ->
            topic.lowercase().contains(normalized)
        }.take(10) // Limit to top 10 matches
    }

    private fun readRawJsonFile(): String {
        val resourceId = context.resources.getIdentifier(
            "local_health_library",
            "raw",
            context.packageName
        )

        if (resourceId == 0) {
            throw IllegalStateException("local_health_library.json not found in res/raw/")
        }

        return context.resources.openRawResource(resourceId).use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                reader.readText()
            }
        }
    }

    private fun parseHealthRecord(jsonObject: JsonObject): HealthRecord {
        val disease = jsonObject["disease"]?.jsonPrimitive?.content ?: "Unknown"
        val urlSource = jsonObject["url_source"]?.jsonPrimitive?.content ?: ""
        val dataObject = jsonObject["data"]?.jsonObject

        val dataMap = mutableMapOf<String, DataValue>()

        dataObject?.forEach { (key, value) ->
            dataMap[key] = parseDataValue(value)
        }

        return HealthRecord(
            disease = disease,
            url_source = urlSource,
            data = dataMap
        )
    }

    private fun parseDataValue(element: JsonElement): DataValue {
        return when (element) {
            is JsonArray -> {
                val list = element.map { it.jsonPrimitive.content }
                DataValue.ListText(list)
            }
            is JsonObject -> {
                val map = element.mapValues { it.value.jsonPrimitive.content }
                DataValue.NestedMap(map)
            }
            else -> {
                DataValue.Text(element.jsonPrimitive.content)
            }
        }
    }

    /**
     * Get formatted topic list for model context.
     * Returns a concise string with all topic names.
     */
    fun getTopicListForModel(): String {
        val index = loadTopicIndex()
        return buildString {
            appendLine("Available Health Topics (${index.totalCount} total):")
            appendLine()

            // Group by first letter for readability
            val grouped = index.topics.groupBy { it.first().uppercaseChar() }

            grouped.forEach { (letter, topics) ->
                appendLine("[$letter]")
                topics.forEach { topic ->
                    appendLine("  • $topic")
                }
                appendLine()
            }
        }
    }
}
