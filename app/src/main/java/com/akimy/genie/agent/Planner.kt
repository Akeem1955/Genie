package com.akimy.genie.agent

import android.util.Log
import com.akimy.genie.data.SkillDao
import com.akimy.genie.engine.AgentResponse
import com.akimy.genie.engine.GenieEngine
import com.akimy.genie.telemetry.ErrorTaxonomy
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

private const val TAG = "GeniePlanner"

interface GeniePlanner {
    suspend fun findSkillMatch(goal: String): SkillMatch
    suspend fun plan(
        prompt: String,
        imagePngBytes: List<ByteArray> = emptyList(),
    ): PlanResult
}

sealed class SkillMatch {
    data object None : SkillMatch()

    sealed class Cached(open val steps: List<Decision.Act>) : SkillMatch()

    data class BuiltIn(override val steps: List<Decision.Act>) : Cached(steps)

    data class Stored(
        val skillId: Int,
        val goalPattern: String,
        override val steps: List<Decision.Act>,
    ) : Cached(steps)
}

/**
 * The Planner node in the agent graph.
 *
 * 1. First checks the SkillLibrary for a pre-compiled plan match
 * 2. If no match, sends the assembled prompt to GenieEngine
 * 3. Parses the returned tool call into an orchestrator Decision
 */
class Planner(
    private val engine: GenieEngine,
    private val skillDao: SkillDao? = null,
) : GeniePlanner {

    override suspend fun findSkillMatch(goal: String): SkillMatch {
        val builtIn = BuiltInSkillMatcher.match(goal)
        if (builtIn != null) {
            Log.d(TAG, "Matched built-in skill for goal: '$goal'")
            return SkillMatch.BuiltIn(builtIn)
        }

        if (skillDao == null) return SkillMatch.None

        val skills = skillDao.findMatchingSkills(goal)
        if (skills.isEmpty()) return SkillMatch.None

        val bestSkill = skills.maxByOrNull { it.successCount } ?: return SkillMatch.None
        return try {
            Log.d(
                TAG,
                "Found skill match: '${bestSkill.goalPattern}' (${bestSkill.successCount} successes)"
            )
            SkillMatch.Stored(
                skillId = bestSkill.id,
                goalPattern = bestSkill.goalPattern,
                steps = Json.decodeFromString<List<Decision.Act>>(bestSkill.planJson),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse skill plan: ${e.message}")
            SkillMatch.None
        }
    }

    override suspend fun plan(
        prompt: String,
        imagePngBytes: List<ByteArray>,
    ): PlanResult {
        val responseChunks = mutableListOf<AgentResponse>()
        val fullText = StringBuilder()
        var toolCallMessage: Message? = null

        engine.sendAgentMessage(prompt, imagePngBytes).collect { response ->
            responseChunks.add(response)
            when (response) {
                is AgentResponse.Token -> fullText.append(response.text)
                is AgentResponse.Done -> Unit
                is AgentResponse.ToolCallRequest -> {
                    Log.d(TAG, "Got tool call request from engine")
                    toolCallMessage = response.message
                }
                is AgentResponse.Error -> {
                    Log.e(TAG, "Engine error during planning: ${response.error}")
                }
            }
        }

        val errorResponse = responseChunks.filterIsInstance<AgentResponse.Error>().firstOrNull()
        if (errorResponse != null) {
            val taxonomy = errorResponse.error
            if (taxonomy is ErrorTaxonomy.LogicErr) {
                return PlanResult.ParseError(taxonomy.message)
            }
            return PlanResult.Error(taxonomy.toString())
        }

        toolCallMessage?.let { return parseDecision(it) }
        return parseDecision(fullText.toString().trim())
    }

    internal fun parseDecision(message: Message): PlanResult {
        if (message.toolCalls.isEmpty()) {
            return PlanResult.ParseError("No tool call found in model response")
        }

        if (message.toolCalls.size > 1) {
            return PlanResult.ParseError(
                "Expected exactly one tool call per turn, got ${message.toolCalls.size}"
            )
        }

        return parseDecision(message.toolCalls.single())
    }

    internal fun parseDecision(toolCall: ToolCall): PlanResult {
        if (toolCall.name == "tasks") {
            val summary = stringifyArgument(toolCall.arguments["plan"])
                .trim()
                .takeIf { it.isNotEmpty() }
                ?: return PlanResult.ParseError("tasks requires a non-empty 'plan'")
            return PlanResult.Success(Decision.Finish(summary))
        }

        if (toolCall.name == "reply") {
            val message = stringifyArgument(toolCall.arguments["message"])
                .trim()
                .takeIf { it.isNotEmpty() }
                ?: return PlanResult.ParseError("reply requires a non-empty 'message'")
            return PlanResult.Success(Decision.Reply(message))
        }

        val args = toolCall.arguments.entries.associate { (key, value) ->
            key to stringifyArgument(value)
        }
        return PlanResult.Success(Decision.Act(tool = toolCall.name, args = args))
    }

    /**
     * Plain text is invalid in native planner mode. The model must call one tool per turn.
     */
    internal fun parseDecision(rawText: String): PlanResult {
        if (rawText.isBlank()) {
            return PlanResult.ParseError("Model returned neither tool calls nor text")
        }

        val excerpt = rawText.take(120)
        Log.w(TAG, "Plain text returned in native tool-calling mode: $excerpt")
        return PlanResult.ParseError(
            message = "Model returned plain text instead of a tool call. Use a tool call or tasks.",
            rawText = rawText,
        )
    }

    private fun stringifyArgument(value: Any?): String {
        return when (value) {
            null -> ""
            is String -> value
            is Number, is Boolean -> value.toString()
            is JsonPrimitive -> value.contentOrNull ?: value.toString()
            is Map<*, *>, is List<*>, is Array<*> -> anyToJson(value).toString()
            else -> value.toString()
        }
    }

    private fun anyToJson(value: Any?): JsonElement {
        return when (value) {
            null -> JsonNull
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Byte -> JsonPrimitive(value)
            is Short -> JsonPrimitive(value)
            is Int -> JsonPrimitive(value)
            is Long -> JsonPrimitive(value)
            is Float -> JsonPrimitive(value)
            is Double -> JsonPrimitive(value)
            is Map<*, *> -> buildJsonObject {
                value.forEach { (key, nestedValue) ->
                    if (key != null) {
                        put(key.toString(), anyToJson(nestedValue))
                    }
                }
            }
            is List<*> -> buildJsonArray {
                value.forEach { add(anyToJson(it)) }
            }
            is Array<*> -> buildJsonArray {
                value.forEach { add(anyToJson(it)) }
            }
            else -> JsonPrimitive(value.toString())
        }
    }
}

sealed class PlanResult {
    data class Success(val decision: Decision) : PlanResult()
    data class ParseError(
        val message: String,
        val rawText: String? = null,
    ) : PlanResult()
    data class Error(val message: String) : PlanResult()
}
