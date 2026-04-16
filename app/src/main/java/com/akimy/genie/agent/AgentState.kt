package com.akimy.genie.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Core agent state types for the LangGraph-style planning loop.
 *
 * This file defines the immutable data structures that flow through:
 *   StateInit → Window → PromptBuilder → Planner → [Act|Finish]
 */

/**
 * The agent's current planning state.
 *
 * @param goal The user's original request (natural language)
 * @param history The full history of entries for this goal
 * @param retryCount How many times the current step has been retried (TransientErr)
 * @param replanCount How many times the agent has replanned (LogicErr/AuthErr)
 * @param maxRetries Maximum retries before escalating
 * @param maxReplans Maximum replans before hard stop
 * @param isNovelPlan True if this plan was NOT found in the SkillLibrary
 */
data class AgentState(
    val goal: String,
    val history: MutableList<HistoryEntry> = mutableListOf(),
    val retryCount: Int = 0,
    val replanCount: Int = 0,
    val maxRetries: Int = 3,
    val maxReplans: Int = 3,
    val isNovelPlan: Boolean = true,
)

/**
 * An entry in the agent's conversation/action history.
 */
sealed class HistoryEntry {
    /** The original user request */
    data class UserMessage(val text: String) : HistoryEntry()

    /** A decision made by the LLM planner */
    data class ModelDecision(val decision: Decision) : HistoryEntry()

    /** The result of executing a tool */
    data class ToolResult(
        val toolName: String,
        val args: Map<String, String>,
        val outcome: ToolOutcome,
    ) : HistoryEntry()
}

/**
 * A decision output by the LLM planner.
 * The model must output valid JSON matching one of these variants.
 */
@Serializable
sealed class Decision {
    /**
     * The agent wants to execute a tool.
     * Example JSON: {"action": "act", "tool": "click", "args": {"target": "Settings"}}
     */
    @Serializable
    @SerialName("act")
    data class Act(
        val tool: String,
        val args: Map<String, String> = emptyMap(),
    ) : Decision()

    /**
     * The agent has completed the goal.
     * Example JSON: {"action": "finish", "summary": "Opened Settings app successfully."}
     */
    @Serializable
    @SerialName("finish")
    data class Finish(
        val summary: String,
    ) : Decision()
}

/**
 * The outcome of a tool execution.
 * Maps directly to ErrorTaxonomy for the agent loop's retry/replan logic.
 */
sealed class ToolOutcome {
    /** Tool executed successfully */
    data class Ok(val result: String) : ToolOutcome()

    /** Transient error — worth retrying */
    data class TransientErr(val message: String) : ToolOutcome()

    /** Logic error — agent should replan */
    data class LogicErr(val message: String) : ToolOutcome()

    /** HITL denied/timeout — agent should replan with notification */
    data class AuthErr(val message: String) : ToolOutcome()

    /** Fatal — agent must stop immediately */
    data class FatalErr(val message: String) : ToolOutcome()
}
