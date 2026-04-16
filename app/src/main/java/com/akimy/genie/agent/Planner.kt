package com.akimy.genie.agent

import android.util.Log
import com.akimy.genie.data.SkillDao
import com.akimy.genie.engine.AgentResponse
import com.akimy.genie.engine.GenieEngine
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json

private const val TAG = "GeniePlanner"

/**
 * The Planner node in the agent graph.
 *
 * 1. First checks the SkillLibrary for a pre-compiled plan match
 * 2. If no match, sends the assembled prompt to GenieEngine
 * 3. Parses the streamed response into a Decision (Act or Finish)
 */
class Planner(
    private val engine: GenieEngine,
    private val skillDao: SkillDao? = null,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Attempt to match the goal against the SkillLibrary.
     *
     * @param goal The user's goal text
     * @return A pre-compiled Decision.Act list, or null if no match
     */
    suspend fun findSkillMatch(goal: String): List<Decision.Act>? {
        if (skillDao == null) return null

        val skills = skillDao.findMatchingSkills(goal)
        if (skills.isEmpty()) return null

        // Use the skill with the highest success count
        val bestSkill = skills.maxByOrNull { it.successCount } ?: return null

        return try {
            Log.d(TAG, "Found skill match: '${bestSkill.goalPattern}' (${bestSkill.successCount} successes)")
            json.decodeFromString<List<Decision.Act>>(bestSkill.planJson)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse skill plan: ${e.message}")
            null
        }
    }

    /**
     * Send the prompt to the LLM and parse the Decision from the response.
     *
     * @param prompt The full prompt from PromptBuilder
     * @return The parsed Decision, or null if parsing failed
     */
    suspend fun plan(prompt: String): PlanResult {
        val responseChunks = mutableListOf<AgentResponse>()
        val fullText = StringBuilder()

        engine.sendAgentMessage(prompt).collect { response ->
            responseChunks.add(response)
            when (response) {
                is AgentResponse.Token -> fullText.append(response.text)
                is AgentResponse.Done -> { /* handled below */ }
                is AgentResponse.ToolCallRequest -> {
                    Log.d(TAG, "Got tool call request from engine")
                }
                is AgentResponse.Error -> {
                    Log.e(TAG, "Engine error during planning: ${response.error}")
                }
            }
        }

        // Check for errors first
        val errorResponse = responseChunks.filterIsInstance<AgentResponse.Error>().firstOrNull()
        if (errorResponse != null) {
            return PlanResult.Error(errorResponse.error.toString())
        }

        // Parse the model's JSON output into a Decision
        return parseDecision(fullText.toString().trim())
    }

    /**
     * Parse the model's raw text output into a Decision.
     * Handles common LLM output quirks (markdown fences, extra text around JSON).
     */
    internal fun parseDecision(rawText: String): PlanResult {
        // Strip markdown code fences if present
        var jsonText = rawText
            .replace("```json", "")
            .replace("```", "")
            .trim()

        // Try to extract JSON object from the text
        val jsonStart = jsonText.indexOf('{')
        val jsonEnd = jsonText.lastIndexOf('}')
        if (jsonStart == -1 || jsonEnd == -1 || jsonEnd <= jsonStart) {
            Log.w(TAG, "No JSON found in model output: $rawText")
            return PlanResult.ParseError("No valid JSON in model output")
        }
        jsonText = jsonText.substring(jsonStart, jsonEnd + 1)

        return try {
            // Parse the action field to determine Decision type
            val jsonElement = json.parseToJsonElement(jsonText)
            val jsonObject = jsonElement as? kotlinx.serialization.json.JsonObject
                ?: return PlanResult.ParseError("Expected JSON object")

            val action = jsonObject["action"]?.let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content
            }

            when (action) {
                "act" -> {
                    val tool = jsonObject["tool"]?.let {
                        (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                    } ?: return PlanResult.ParseError("Missing 'tool' field in act decision")

                    val argsElement = jsonObject["args"] as? kotlinx.serialization.json.JsonObject
                    val args = argsElement?.entries?.associate { (k, v) ->
                        k to (v as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                    } ?: emptyMap()

                    PlanResult.Success(Decision.Act(tool = tool, args = args))
                }

                "finish" -> {
                    val summary = jsonObject["summary"]?.let {
                        (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                    } ?: "Goal completed"

                    PlanResult.Success(Decision.Finish(summary = summary))
                }

                else -> {
                    PlanResult.ParseError("Unknown action type: '$action'")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "JSON parse error: ${e.message}")
            PlanResult.ParseError("JSON parse error: ${e.message}")
        }
    }
}

/**
 * Result of a planning attempt.
 */
sealed class PlanResult {
    data class Success(val decision: Decision) : PlanResult()
    data class ParseError(val message: String) : PlanResult()
    data class Error(val message: String) : PlanResult()
}
