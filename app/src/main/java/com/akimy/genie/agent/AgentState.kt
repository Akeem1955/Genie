package com.akimy.genie.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Core agent state types for the LangGraph-style planning loop.
 */

/**
 * The agent's current planning state.
 *
 * @param goal The user's original request
 * @param history The full history of entries for this goal
 * @param retryCount How many times the current step has been retried
 * @param replanCount How many times the agent has replanned after logic failures
 * @param maxRetries Maximum retries before escalating
 * @param maxReplans Maximum replans before hard stop
 * @param isNovelPlan True if this plan was not found in the SkillLibrary
 */
data class AgentState(
    val goal: String,
    val history: MutableList<HistoryEntry> = mutableListOf(),
    var retryCount: Int = 0,
    var replanCount: Int = 0,
    val maxRetries: Int = 3,
    val maxReplans: Int = 3,
    var isNovelPlan: Boolean = true,
)

/**
 * An entry in the agent's conversation and action history.
 */
sealed class HistoryEntry {
    data class UserMessage(val text: String) : HistoryEntry()

    data class ModelDecision(val decision: Decision) : HistoryEntry()

    data class ToolResult(
        val toolName: String,
        val args: Map<String, String>,
        val outcome: ToolOutcome,
    ) : HistoryEntry()
}

/**
 * A planner decision consumed by the orchestrator.
 */
@Serializable
sealed class Decision {
    @Serializable
    @SerialName("act")
    data class Act(
        val tool: String,
        val args: Map<String, String> = emptyMap(),
    ) : Decision()

    @Serializable
    @SerialName("finish")
    data class Finish(
        val summary: String,
    ) : Decision()
}

/**
 * The outcome of a tool execution.
 */
sealed class ToolOutcome {
    data class Ok(val result: String) : ToolOutcome()

    data class TransientErr(val message: String) : ToolOutcome()

    data class LogicErr(val message: String) : ToolOutcome()

    data class AuthErr(val message: String) : ToolOutcome()

    data class FatalErr(val message: String) : ToolOutcome()
}
