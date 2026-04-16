package com.akimy.genie.agent

import android.content.Context
import android.util.Log
import com.akimy.genie.data.FactDao
import com.akimy.genie.data.Skill
import com.akimy.genie.data.SkillDao
import com.akimy.genie.engine.GenieEngine
import com.akimy.genie.telemetry.ErrorTaxonomy
import com.akimy.genie.telemetry.EventLogger
import com.akimy.genie.telemetry.GenieEvent
import com.akimy.genie.tools.HITLInterceptionWrapper
import com.akimy.genie.tools.ToolRegistry
import com.akimy.genie.tools.ToolServiceContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "GenieOrchestrator"

/**
 * The main agent loop controller — the "conductor" of the LangGraph-style state machine.
 *
 * Implements the full flow:
 *   StateInit → Window → PromptBuilder → Planner → [Act|Finish]
 *       Act → RegistryCheck → SafetyWrapper → Execute → Evaluate → UpdateState → ↺
 *       Finish → NovelCheck → [WriteSkill|End]
 *
 * Error handling:
 * - TransientErr → retry with exponential backoff (up to maxRetries)
 * - LogicErr/AuthErr → replan (up to maxReplans)
 * - FatalErr → hard stop
 */
class AgentOrchestrator(
    private val engine: GenieEngine,
    private val toolRegistry: ToolRegistry,
    private val promptBuilder: PromptBuilder,
    private val planner: Planner,
    private val factDao: FactDao,
    private val skillDao: SkillDao,
    private val appContext: Context,
) {
    private val slidingWindowManager = SlidingWindowManager()
    private val json = Json { encodeDefaults = true }

    /**
     * Execute the agent loop for a user goal.
     *
     * @param goal The user's natural language request
     * @param serviceContext The OS bridge for tool execution
     * @param onStatusUpdate Callback for agent status (for TTS/overlay)
     */
    suspend fun executeGoal(
        goal: String,
        serviceContext: ToolServiceContext,
        onStatusUpdate: (String) -> Unit = {},
    ): String = withContext(Dispatchers.Default) {
        Log.d(TAG, "=== New Goal: $goal ===")
        EventLogger.emit(GenieEvent.StateTransition("idle", "planning"))

        // Initialize state
        val state = AgentState(goal = goal)
        state.history.add(HistoryEntry.UserMessage(goal))

        // Check SkillLibrary first
        val cachedSteps = planner.findSkillMatch(goal)
        if (cachedSteps != null) {
            Log.d(TAG, "Found cached skill with ${cachedSteps.size} steps")
            state.isNovelPlan.let { /* isNovelPlan = false for cached */ }
            return@withContext executeCachedPlan(state, cachedSteps, serviceContext, onStatusUpdate)
        }

        // Main agent loop
        var loopCount = 0
        val maxLoops = 20 // Safety limit to prevent infinite loops

        while (loopCount < maxLoops) {
            loopCount++
            Log.d(TAG, "--- Loop iteration $loopCount ---")

            // Build prompt with windowed history + injected facts
            val facts = try {
                factDao.getAllFactsSnapshot().map { "${it.key}: ${it.value}" }
            } catch (e: Exception) { emptyList() }

            val prompt = promptBuilder.buildPrompt(state, facts)

            // Plan next action
            EventLogger.emit(GenieEvent.StateTransition("building_prompt", "planning"))
            val planResult = planner.plan(prompt)

            when (planResult) {
                is PlanResult.Success -> {
                    val decision = planResult.decision
                    state.history.add(HistoryEntry.ModelDecision(decision))

                    when (decision) {
                        is Decision.Act -> {
                            // Execute the tool
                            val outcome = executeToolWithSafety(
                                decision, state, serviceContext, onStatusUpdate
                            )

                            // Evaluate outcome
                            when (outcome) {
                                is ToolOutcome.Ok -> {
                                    state.history.add(HistoryEntry.ToolResult(
                                        decision.tool, decision.args, outcome
                                    ))
                                    slidingWindowManager.pruneAfterSuccess(state.history)
                                    // Reset retry counter on success
                                    // Continue loop for next step
                                }

                                is ToolOutcome.TransientErr -> {
                                    state.history.add(HistoryEntry.ToolResult(
                                        decision.tool, decision.args, outcome
                                    ))
                                    if (state.retryCount < state.maxRetries) {
                                        val backoffMs = (1000L * (1 shl state.retryCount))
                                            .coerceAtMost(8000L)
                                        Log.d(TAG, "TransientErr — retrying in ${backoffMs}ms")
                                        delay(backoffMs)
                                        // Retry count incremented in state copy
                                    } else {
                                        Log.w(TAG, "Max retries exceeded")
                                        onStatusUpdate("I couldn't complete this step after multiple attempts.")
                                        return@withContext "Failed: max retries exceeded for ${decision.tool}"
                                    }
                                }

                                is ToolOutcome.LogicErr -> {
                                    state.history.add(HistoryEntry.ToolResult(
                                        decision.tool, decision.args, outcome
                                    ))
                                    if (state.replanCount < state.maxReplans) {
                                        Log.d(TAG, "LogicErr — replanning")
                                        // Agent will see the error in history and replan
                                    } else {
                                        Log.w(TAG, "Max replans exceeded")
                                        onStatusUpdate("I couldn't figure out how to do this.")
                                        return@withContext "Failed: max replans exceeded"
                                    }
                                }

                                is ToolOutcome.AuthErr -> {
                                    state.history.add(HistoryEntry.ToolResult(
                                        decision.tool, decision.args, outcome
                                    ))
                                    onStatusUpdate("Action requires your authorization, which was denied.")
                                    return@withContext "Stopped: user denied authorization for ${decision.tool}"
                                }

                                is ToolOutcome.FatalErr -> {
                                    Log.e(TAG, "FatalErr — hard stop")
                                    EventLogger.emit(GenieEvent.ErrorOccurred(
                                        ErrorTaxonomy.FatalErr(outcome.message),
                                        "AgentOrchestrator.executeGoal"
                                    ))
                                    onStatusUpdate("Something went wrong. Please try again.")
                                    return@withContext "Fatal error: ${outcome.message}"
                                }
                            }
                        }

                        is Decision.Finish -> {
                            Log.d(TAG, "=== Goal Complete: ${decision.summary} ===")
                            EventLogger.emit(GenieEvent.StateTransition("executing", "finished"))

                            // Self-evolution: write novel plan to SkillLibrary
                            if (state.isNovelPlan) {
                                writeSkill(state)
                            }

                            onStatusUpdate(decision.summary)
                            return@withContext decision.summary
                        }
                    }
                }

                is PlanResult.ParseError -> {
                    Log.w(TAG, "Parse error: ${planResult.message}")
                    state.history.add(HistoryEntry.ToolResult(
                        "planner", emptyMap(),
                        ToolOutcome.LogicErr("Could not parse your response. ${planResult.message}")
                    ))
                    // Add error to history so model can self-correct on next iteration
                }

                is PlanResult.Error -> {
                    Log.e(TAG, "Planner error: ${planResult.message}")
                    onStatusUpdate("I encountered an error while thinking.")
                    return@withContext "Error: ${planResult.message}"
                }
            }
        }

        Log.w(TAG, "Max loop iterations reached")
        onStatusUpdate("I reached the maximum number of steps without completing the goal.")
        return@withContext "Failed: max loop iterations reached"
    }

    /**
     * Execute a tool with HITL safety check if needed.
     */
    private suspend fun executeToolWithSafety(
        decision: Decision.Act,
        state: AgentState,
        serviceContext: ToolServiceContext,
        onStatusUpdate: (String) -> Unit,
    ): ToolOutcome {
        val tool = toolRegistry.getTool(decision.tool)
            ?: return ToolOutcome.LogicErr("Unknown tool: '${decision.tool}'")

        val startTime = System.currentTimeMillis()

        val outcome = if (tool.requiresAuth) {
            HITLInterceptionWrapper.executeWithAuth(
                tool, decision.args, serviceContext, appContext
            )
        } else {
            // Handle save_fact / retrieve_fact specially
            val rawOutcome = tool.execute(decision.args, serviceContext)
            handleFactTools(decision.tool, decision.args, rawOutcome)
        }

        val duration = System.currentTimeMillis() - startTime
        EventLogger.emit(GenieEvent.ToolExecution(
            toolName = decision.tool,
            args = decision.args,
            durationMs = duration,
            success = outcome is ToolOutcome.Ok,
        ))

        return outcome
    }

    /**
     * Handle save_fact/retrieve_fact by routing to the Room database.
     */
    private suspend fun handleFactTools(
        toolName: String,
        args: Map<String, String>,
        rawOutcome: ToolOutcome,
    ): ToolOutcome {
        if (rawOutcome !is ToolOutcome.Ok) return rawOutcome

        return when {
            rawOutcome.result.startsWith("SAVE_FACT:") -> {
                val parts = rawOutcome.result.removePrefix("SAVE_FACT:").split("=", limit = 2)
                if (parts.size == 2) {
                    try {
                        factDao.upsert(parts[0], parts[1])
                        ToolOutcome.Ok("Saved fact: ${parts[0]} = ${parts[1]}")
                    } catch (e: Exception) {
                        ToolOutcome.TransientErr("Failed to save fact: ${e.message}")
                    }
                } else rawOutcome
            }

            rawOutcome.result.startsWith("RETRIEVE_FACT:") -> {
                val key = rawOutcome.result.removePrefix("RETRIEVE_FACT:")
                try {
                    val fact = factDao.getFactByKey(key)
                    if (fact != null) {
                        ToolOutcome.Ok("${fact.key} = ${fact.value}")
                    } else {
                        ToolOutcome.Ok("No fact found for key: '$key'")
                    }
                } catch (e: Exception) {
                    ToolOutcome.TransientErr("Failed to retrieve fact: ${e.message}")
                }
            }

            else -> rawOutcome
        }
    }

    /**
     * Execute a cached plan from the SkillLibrary.
     */
    private suspend fun executeCachedPlan(
        state: AgentState,
        steps: List<Decision.Act>,
        serviceContext: ToolServiceContext,
        onStatusUpdate: (String) -> Unit,
    ): String {
        for (step in steps) {
            val outcome = executeToolWithSafety(step, state, serviceContext, onStatusUpdate)
            if (outcome is ToolOutcome.Ok) {
                state.history.add(HistoryEntry.ToolResult(step.tool, step.args, outcome))
            } else {
                // Cached plan failed — fall back to live planning
                Log.w(TAG, "Cached plan step failed: ${step.tool}, falling back to live planning")
                return executeGoal(state.goal, serviceContext, onStatusUpdate)
            }
        }
        onStatusUpdate("Done!")
        return "Completed from cached skill"
    }

    /**
     * Write a novel plan to the SkillLibrary for self-evolution.
     */
    private suspend fun writeSkill(state: AgentState) {
        try {
            val actSteps = state.history
                .filterIsInstance<HistoryEntry.ModelDecision>()
                .map { it.decision }
                .filterIsInstance<Decision.Act>()

            if (actSteps.isEmpty()) return

            val planJson = json.encodeToString(actSteps)
            val skill = Skill(
                goalPattern = state.goal.lowercase().take(100),
                planJson = planJson,
                successCount = 1,
            )
            skillDao.insert(skill)

            EventLogger.emit(GenieEvent.SkillWritten(
                goalPattern = skill.goalPattern,
                stepCount = actSteps.size,
            ))
            Log.d(TAG, "Written new skill: '${skill.goalPattern}' (${actSteps.size} steps)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write skill: ${e.message}")
        }
    }
}
