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
    var intent: AgentIntent? = null,
    var plan: AgentPlan? = null,
    var currentStepIndex: Int = 0,
    val history: MutableList<HistoryEntry> = mutableListOf(),
    val stepObservations: MutableList<StepObservation> = mutableListOf(),
    var retryCount: Int = 0,
    var replanCount: Int = 0,
    val maxRetries: Int = 3,
    val maxReplans: Int = 3,
    var isNovelPlan: Boolean = true,
)

/**
 * The model's structured understanding of the user goal.
 *
 * This stays deliberately generic. Genie should not hardcode domains such as
 * messaging; the planner can extract entities like app, recipient, and message
 * when they are relevant to the goal.
 */
@Serializable
data class AgentIntent(
    val summary: String,
    val entities: Map<String, String> = emptyMap(),
)

/**
 * A model-generated plan that the orchestrator executes step by step.
 */
@Serializable
data class AgentPlan(
    val intent: AgentIntent,
    val steps: List<PlanStep>,
)

/**
 * One plan step. The model still chooses concrete tools, but only for the
 * current step and preferably from [allowedTools].
 */
@Serializable
data class PlanStep(
    val instruction: String,
    val expectedOutcome: String,
    val allowedTools: List<String> = emptyList(),
)

/**
 * Compact execution record for step-level verification and repair.
 */
data class StepObservation(
    val stepIndex: Int,
    val toolName: String,
    val outcome: ToolOutcome,
)

/**
 * An entry in the agent's conversation and action history.
 */
sealed class HistoryEntry {
    data class UserMessage(val text: String) : HistoryEntry()

    data class PlanCreated(val plan: AgentPlan) : HistoryEntry()

    data class StepCompleted(
        val stepIndex: Int,
        val summary: String,
    ) : HistoryEntry()

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

    @Serializable
    @SerialName("reply")
    data class Reply(
        val message: String,
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
