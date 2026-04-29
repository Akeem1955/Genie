package com.akimy.genie.agent

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.akimy.genie.data.FactDao
import com.akimy.genie.data.Skill
import com.akimy.genie.data.SkillDao
import com.akimy.genie.engine.GenieEngine
import com.akimy.genie.telemetry.ErrorTaxonomy
import com.akimy.genie.telemetry.EventLogger
import com.akimy.genie.telemetry.GenieEvent
import com.akimy.genie.tools.AnnotationSessionStore
import com.akimy.genie.tools.BoardStyle
import com.akimy.genie.tools.HITLInterceptionWrapper
import com.akimy.genie.tools.RiskAssessor
import com.akimy.genie.tools.RiskVerdict
import com.akimy.genie.tools.SceneStoreResult
import com.akimy.genie.tools.ToolRegistry
import com.akimy.genie.tools.ToolServiceContext
import com.akimy.genie.tools.VisualizerSceneStore
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "GenieOrchestrator"
private const val MAX_VISUALIZER_JSON_CHARS = 24_000
private const val MAX_BOARD_OBJECTS_JSON_CHARS = 32_000
private const val MAX_BOARD_STEPS_JSON_CHARS = 16_000
private const val MAX_ANNOTATION_STYLE_JSON_CHARS = 4_000
private const val MAX_SANITIZED_PLANNER_ERROR_CHARS = 220
private val SCENE_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
private val OBJECT_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
private val ALLOWED_DIAGRAM_TYPES = setOf("flowchart", "cycle", "timeline", "mindmap", "table")
private val ALLOWED_BOARD_OBJECT_TYPES = setOf("title", "text", "box", "card", "circle", "line", "arrow", "code")

@Serializable
private data class GeneratedAgentPlan(
    val intent: AgentIntent,
    val entities: Map<String, String> = emptyMap(),
    val steps: List<PlanStep> = emptyList(),
)

/**
 * The main agent loop controller.
 *
 * Flow:
 * StateInit -> PlanGenerator -> AgentPlan
 * AgentPlan -> CurrentStepPrompt -> Planner -> [Act|StepFinish]
 * Act -> RegistryCheck -> SafetyWrapper -> Execute -> Observe -> same/current step
 * StepFinish -> AdvanceStep -> loop
 * AllStepsComplete -> NovelCheck -> [WriteSkill|End]
 *
 * Error handling:
 * - TransientErr -> retry with exponential backoff
 * - LogicErr -> replan
 * - AuthErr -> stop immediately and report cancellation
 * - FatalErr -> hard stop
 */
class AgentOrchestrator(
    private val engine: GenieEngine,
    private val toolRegistry: ToolRegistry,
    enabledToolNames: Set<String> = toolRegistry.getToolNames(),
    private val promptBuilder: PromptBuilder,
    private val planner: GeniePlanner,
    private val factDao: FactDao,
    private val skillDao: SkillDao,
    private val appContext: Context,
) {
    private val slidingWindowManager = SlidingWindowManager()
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val activeToolNames: Set<String> = enabledToolNames
        .filter { it in toolRegistry.getToolNames() }
        .toSet()
        .ifEmpty { toolRegistry.getToolNames() }
    private val annotationPlaybackBySession = ConcurrentHashMap<String, MutableList<AnnotationPlaybackOp>>()
    private val annotationSessionTitle = ConcurrentHashMap<String, String>()

    private sealed class AnnotationPlaybackOp {
        abstract val delayMs: Long

        data class Box(
            override val delayMs: Long,
            val opId: String,
            val x: Float,
            val y: Float,
            val width: Float,
            val height: Float,
            val label: String,
            val style: BoardStyle,
        ) : AnnotationPlaybackOp()

        data class Label(
            override val delayMs: Long,
            val opId: String,
            val x: Float,
            val y: Float,
            val text: String,
            val style: BoardStyle,
        ) : AnnotationPlaybackOp()

        data class Pointer(
            override val delayMs: Long,
            val opId: String,
            val x: Float,
            val y: Float,
            val targetX: Float,
            val targetY: Float,
            val text: String,
            val style: BoardStyle,
        ) : AnnotationPlaybackOp()
    }

    suspend fun executeGoal(
        goal: String,
        serviceContext: ToolServiceContext,
        onStatusUpdate: (String) -> Unit = {},
        onToolExecuting: (String, String?) -> Unit = { _, _ -> },
    ): String = executeGoalInternal(
        goal = goal,
        serviceContext = serviceContext,
        onStatusUpdate = onStatusUpdate,
        onToolExecuting = onToolExecuting,
        allowSkillLookup = true,
        isNovelPlan = true,
    )

    private suspend fun executeGoalInternal(
        goal: String,
        serviceContext: ToolServiceContext,
        onStatusUpdate: (String) -> Unit,
        onToolExecuting: (String, String?) -> Unit,
        allowSkillLookup: Boolean,
        isNovelPlan: Boolean,
    ): String = withContext(Dispatchers.Default) {
        Log.d(TAG, "=== New Goal: $goal ===")
        EventLogger.emit(GenieEvent.StateTransition("idle", "planning"))

        val state = AgentState(goal = goal, isNovelPlan = isNovelPlan)
        state.history.add(HistoryEntry.UserMessage(goal))

        if (allowSkillLookup) {
            when (val skillMatch = planner.findSkillMatch(goal)) {
                is SkillMatch.Cached -> {
                    if (skillMatch is SkillMatch.Stored && skillMatch.goalPattern.isGenericMessagingSkillPattern()) {
                        Log.d(TAG, "Skipping generic messaging skill until entity substitution is available")
                    } else if (requiresMessagingSend(state) && !cachedPlanHasSendAction(skillMatch.steps)) {
                        Log.d(TAG, "Skipping cached messaging skill because it has no Send action after typing")
                    } else {
                        val unavailableTools = skillMatch.steps
                            .map { it.tool }
                            .filter { it !in activeToolNames }
                            .distinct()
                        if (unavailableTools.isEmpty()) {
                            Log.d(TAG, "Found cached skill with ${skillMatch.steps.size} steps")
                            state.isNovelPlan = false
                            return@withContext executeCachedPlan(
                                state = state,
                                skillMatch = skillMatch,
                                serviceContext = serviceContext,
                                onStatusUpdate = onStatusUpdate,
                                onToolExecuting = onToolExecuting,
                            )
                        }
                        Log.d(
                            TAG,
                            "Skipping cached skill because current tool profile does not include: $unavailableTools"
                        )
                    }
                }

                SkillMatch.None -> Unit
            }
        }

        val initialFacts = loadFactsSnapshot()
        val initialPlan = generatePlan(
            goal = goal,
            facts = initialFacts,
            previousState = null,
            repairReason = null,
        )
        state.intent = initialPlan.intent
        state.plan = initialPlan
        state.currentStepIndex = 0
        state.history.add(HistoryEntry.PlanCreated(initialPlan))
        Log.d(TAG, "Generated plan with ${initialPlan.steps.size} steps: ${initialPlan.intent.summary}")

        var loopCount = 0
        val maxLoops = 30
        val pendingVisionInputs = mutableListOf<ByteArray>()

        while (loopCount < maxLoops) {
            loopCount++
            Log.d(TAG, "--- Loop iteration $loopCount ---")

            val activePlan = state.plan
            if (activePlan != null && state.currentStepIndex >= activePlan.steps.size) {
                val summary = "Completed: ${activePlan.intent.summary}"
                Log.d(TAG, "=== Goal Complete: $summary ===")
                EventLogger.emit(GenieEvent.StateTransition("executing", "finished"))

                if (state.isNovelPlan) {
                    writeSkill(state)
                }

                onStatusUpdate(summary)
                return@withContext summary
            }

            val facts = loadFactsSnapshot()

            val visionInputsForTurn = pendingVisionInputs.toList()
            pendingVisionInputs.clear()
            val prompt = promptBuilder.buildStepPrompt(
                state = state,
                injectedFacts = facts,
                hasVisionInput = visionInputsForTurn.isNotEmpty(),
            )

            EventLogger.emit(GenieEvent.StateTransition("building_prompt", "planning"))
            when (val planResult = planner.plan(prompt, visionInputsForTurn)) {
                is PlanResult.Success -> {
                    val decision = normalizeDecision(planResult.decision)
                    state.history.add(HistoryEntry.ModelDecision(decision))

                    when (decision) {
                        is Decision.Act -> {
                            val humanReadable = getHumanReadableToolAction(decision.tool, decision.args)
                            onToolExecuting(humanReadable.first, humanReadable.second)

                            val outcome = executeToolWithSafety(
                                decision,
                                state,
                                serviceContext,
                                onStatusUpdate,
                            )

                            when (outcome) {
                                is ToolOutcome.Ok -> {
                                    state.history.add(
                                        HistoryEntry.ToolResult(decision.tool, decision.args, outcome)
                                    )
                                    recordStepObservation(state, decision.tool, outcome)
                                    if (decision.tool == "take_screenshot") {
                                        val screenshotBytes = serviceContext.consumeLatestScreenshotPngBytes()
                                        if (screenshotBytes != null) {
                                            pendingVisionInputs.add(screenshotBytes)
                                            Log.d(
                                                TAG,
                                                "Queued screenshot for multimodal planning turn (${screenshotBytes.size} bytes)"
                                            )
                                        } else {
                                            Log.w(
                                                TAG,
                                                "take_screenshot returned success but no PNG bytes were available"
                                            )
                                        }
                                    }
                                    slidingWindowManager.pruneAfterSuccess(state.history)
                                    state.retryCount = 0
                                    maybeAutoCompleteCurrentStep(state, decision, outcome)
                                }

                                is ToolOutcome.TransientErr -> {
                                    state.history.add(
                                        HistoryEntry.ToolResult(decision.tool, decision.args, outcome)
                                    )
                                    recordStepObservation(state, decision.tool, outcome)
                                    if (state.retryCount < state.maxRetries) {
                                        val backoffMs = (1000L * (1 shl state.retryCount))
                                            .coerceAtMost(8000L)
                                        Log.d(
                                            TAG,
                                            "TransientErr - retrying in ${backoffMs}ms (attempt ${state.retryCount + 1}/${state.maxRetries})"
                                        )
                                        state.retryCount++
                                        delay(backoffMs)
                                    } else {
                                        Log.w(TAG, "Max retries exceeded for ${decision.tool}, requesting plan repair")
                                        val repairedPlan = generatePlan(
                                            goal = state.goal,
                                            facts = loadFactsSnapshot(),
                                            previousState = state,
                                            repairReason = "Current step failed after retries: ${outcome.message}",
                                        )
                                        state.plan = repairedPlan
                                        state.intent = repairedPlan.intent
                                        state.currentStepIndex = 0
                                        state.retryCount = 0
                                        state.replanCount = 0
                                        state.history.add(HistoryEntry.PlanCreated(repairedPlan))
                                    }
                                }

                                is ToolOutcome.LogicErr -> {
                                    state.history.add(
                                        HistoryEntry.ToolResult(decision.tool, decision.args, outcome)
                                    )
                                    recordStepObservation(state, decision.tool, outcome)
                                    if (state.replanCount < state.maxReplans) {
                                        Log.d(
                                            TAG,
                                            "LogicErr - replanning (attempt ${state.replanCount + 1}/${state.maxReplans})"
                                        )
                                        state.replanCount++
                                    } else {
                                        Log.w(TAG, "Max replans exceeded for ${decision.tool}, requesting plan repair")
                                        val repairedPlan = generatePlan(
                                            goal = state.goal,
                                            facts = loadFactsSnapshot(),
                                            previousState = state,
                                            repairReason = "Current step hit repeated logic errors: ${outcome.message}",
                                        )
                                        state.plan = repairedPlan
                                        state.intent = repairedPlan.intent
                                        state.currentStepIndex = 0
                                        state.retryCount = 0
                                        state.replanCount = 0
                                        state.history.add(HistoryEntry.PlanCreated(repairedPlan))
                                    }
                                }

                                is ToolOutcome.AuthErr -> {
                                    state.history.add(
                                        HistoryEntry.ToolResult(decision.tool, decision.args, outcome)
                                    )
                                    recordStepObservation(state, decision.tool, outcome)
                                    onStatusUpdate("Action requires your authorization, which was denied.")
                                    return@withContext "Stopped: user denied authorization for ${decision.tool}"
                                }

                                is ToolOutcome.FatalErr -> {
                                    recordStepObservation(state, decision.tool, outcome)
                                    Log.e(TAG, "FatalErr - hard stop")
                                    EventLogger.emit(
                                        GenieEvent.ErrorOccurred(
                                            ErrorTaxonomy.FatalErr(outcome.message),
                                            "AgentOrchestrator.executeGoal",
                                        )
                                    )
                                    onStatusUpdate("Something went wrong. Please try again.")
                                    return@withContext "Fatal error: ${outcome.message}"
                                }
                            }
                        }

                        is Decision.Finish -> {
                            val activePlan = state.plan
                            if (activePlan == null) {
                                Log.d(TAG, "=== Goal Complete: ${decision.summary} ===")
                                EventLogger.emit(GenieEvent.StateTransition("executing", "finished"))

                                if (state.isNovelPlan) {
                                    writeSkill(state)
                                }

                                onStatusUpdate(decision.summary)
                                return@withContext decision.summary
                            }

                            when (val finishError = stepFinishPreconditionError(state)) {
                                null -> {
                                    val completedIndex = state.currentStepIndex
                                    state.history.add(HistoryEntry.StepCompleted(completedIndex, decision.summary))
                                    state.stepObservations.add(
                                        StepObservation(
                                            stepIndex = completedIndex,
                                            toolName = "finish_task",
                                            outcome = ToolOutcome.Ok(decision.summary),
                                        )
                                    )
                                    state.currentStepIndex++
                                    state.retryCount = 0
                                    state.replanCount = 0
                                    Log.d(TAG, "Plan step ${completedIndex + 1}/${activePlan.steps.size} complete: ${decision.summary}")
                                }

                                else -> {
                                    val outcome = ToolOutcome.LogicErr(finishError)
                                    state.history.add(
                                        HistoryEntry.ToolResult(
                                            "finish_task",
                                            mapOf("summary" to decision.summary),
                                            outcome,
                                        )
                                    )
                                    recordStepObservation(state, "finish_task", outcome)
                                    if (state.replanCount < state.maxReplans) {
                                        Log.d(
                                            TAG,
                                            "Finish precondition failed - replanning (attempt ${state.replanCount + 1}/${state.maxReplans})"
                                        )
                                        state.replanCount++
                                    } else {
                                        Log.w(TAG, "Finish precondition failed repeatedly; requesting plan repair")
                                        val repairedPlan = generatePlan(
                                            goal = state.goal,
                                            facts = loadFactsSnapshot(),
                                            previousState = state,
                                            repairReason = finishError,
                                        )
                                        state.plan = repairedPlan
                                        state.intent = repairedPlan.intent
                                        state.currentStepIndex = 0
                                        state.retryCount = 0
                                        state.replanCount = 0
                                        state.history.add(HistoryEntry.PlanCreated(repairedPlan))
                                    }
                                }
                            }
                        }
                    }
                }

                is PlanResult.ParseError -> {
                    Log.w(TAG, "Parse error: ${planResult.message}")
                    val correction = buildPlannerCorrection(planResult.message)
                    state.history.add(
                        HistoryEntry.ToolResult(
                            "planner",
                            emptyMap(),
                            ToolOutcome.LogicErr(correction),
                        )
                    )
                    if (state.replanCount < state.maxReplans) {
                        state.replanCount++
                    } else {
                        Log.w(TAG, "Planner parse errors exceeded repair threshold; requesting revised plan")
                        val repairedPlan = generatePlan(
                            goal = state.goal,
                            facts = loadFactsSnapshot(),
                            previousState = state,
                            repairReason = "Planner repeatedly returned invalid tool calls: $correction",
                        )
                        state.plan = repairedPlan
                        state.intent = repairedPlan.intent
                        state.currentStepIndex = 0
                        state.retryCount = 0
                        state.replanCount = 0
                        state.history.add(HistoryEntry.PlanCreated(repairedPlan))
                    }
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

    private suspend fun generatePlan(
        goal: String,
        facts: List<String>,
        previousState: AgentState?,
        repairReason: String?,
    ): AgentPlan {
        val prompt = if (previousState == null) {
            promptBuilder.buildPlanPrompt(
                goal = goal,
                injectedFacts = facts,
                availableTools = validToolNames(includeFinish = true),
            )
        } else {
            buildRepairPlanPrompt(goal, facts, previousState, repairReason)
        }

        return when (val result = planner.plan(prompt)) {
            is PlanResult.Success -> {
                val finish = result.decision as? Decision.Finish
                if (finish == null) {
                    Log.w(TAG, "Plan generation returned action instead of plan: ${result.decision}")
                    fallbackPlan(goal)
                } else {
                    parseAgentPlan(finish.summary) ?: fallbackPlan(goal)
                }
            }

            is PlanResult.ParseError -> {
                val plainTextPlan = result.rawText?.let { parseAgentPlan(it) }
                if (plainTextPlan != null) {
                    Log.d(TAG, "Parsed generated plan from plain text JSON")
                    plainTextPlan
                } else {
                    Log.w(TAG, "Plan generation parse error: ${result.message}")
                    fallbackPlan(goal)
                }
            }

            is PlanResult.Error -> {
                Log.w(TAG, "Plan generation error: ${result.message}")
                fallbackPlan(goal)
            }
        }
    }

    private fun fallbackPlan(goal: String): AgentPlan {
        val tools = validToolNames(includeFinish = false).sorted()
        return AgentPlan(
            intent = AgentIntent(
                summary = "Complete the user goal: $goal",
                entities = emptyMap(),
            ),
            steps = listOf(
                PlanStep(
                    instruction = "Inspect the current screen and complete the user's goal safely.",
                    expectedOutcome = "The user's goal is completed or a clear blocking reason is found.",
                    allowedTools = tools,
                )
            ),
        )
    }

    private fun buildRepairPlanPrompt(
        goal: String,
        facts: List<String>,
        state: AgentState,
        repairReason: String?,
    ): String {
        val sb = StringBuilder()
        sb.append(promptBuilder.buildPlanPrompt(goal, facts, validToolNames(includeFinish = true)))
        sb.appendLine()
        sb.appendLine("## Repair Context")
        if (!repairReason.isNullOrBlank()) {
            sb.appendLine("Reason: $repairReason")
        }
        state.plan?.let { plan ->
            sb.appendLine("Previous plan:")
            plan.steps.forEachIndexed { index, step ->
                val status = if (index < state.currentStepIndex) "done" else "not_done"
                sb.appendLine("${index + 1}. [$status] ${step.instruction}")
            }
        }
        val recent = state.history.takeLast(6)
        if (recent.isNotEmpty()) {
            sb.appendLine("Recent observations:")
            recent.forEach { entry -> sb.appendLine("- ${compactHistoryLine(entry)}") }
        }
        sb.appendLine("Create a revised plan from the current device state. Keep completed work in mind; do not repeat failed tactics unless necessary.")
        return sb.toString()
    }

    private fun parseAgentPlan(raw: String): AgentPlan? {
        val jsonText = extractFirstJsonObject(raw) ?: run {
            Log.w(TAG, "Failed to find JSON object in generated plan: raw=${raw.take(240)}")
            return null
        }

        return try {
            val generated = json.decodeFromString<GeneratedAgentPlan>(jsonText)
            val mergedIntent = generated.intent.copy(
                entities = generated.intent.entities + generated.entities
            )

            val validTools = validToolNames(includeFinish = true)
            val usableSteps = generated.steps
                .filter { it.instruction.isNotBlank() }
                .map { step ->
                    step.copy(
                        allowedTools = step.allowedTools
                            .map(String::trim)
                            .filter { it in validTools }
                            .distinct()
                    )
                }
            if (usableSteps.isEmpty()) {
                Log.w(TAG, "Generated plan had no usable steps")
                null
            } else {
                AgentPlan(
                    intent = mergedIntent,
                    steps = usableSteps,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse generated plan JSON: ${e.message}; json=${jsonText.take(300)}")
            null
        }
    }

    private fun extractFirstJsonObject(raw: String): String? {
        val withoutFence = Regex("```(?:json)?\\s*(.*?)```", RegexOption.DOT_MATCHES_ALL)
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?: raw

        val start = withoutFence.indexOf('{')
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false

        for (index in start until withoutFence.length) {
            val ch = withoutFence[index]

            when {
                escaped -> escaped = false
                ch == '\\' && inString -> escaped = true
                ch == '"' -> inString = !inString
                !inString && ch == '{' -> depth++
                !inString && ch == '}' -> {
                    depth--
                    if (depth == 0) {
                        return withoutFence.substring(start, index + 1).trim()
                    }
                }
            }
        }

        return null
    }

    private fun recordStepObservation(
        state: AgentState,
        toolName: String,
        outcome: ToolOutcome,
    ) {
        state.stepObservations.add(
            StepObservation(
                stepIndex = state.currentStepIndex,
                toolName = toolName,
                outcome = outcome,
            )
        )
    }

    private fun maybeAutoCompleteCurrentStep(
        state: AgentState,
        decision: Decision.Act,
        outcome: ToolOutcome.Ok,
    ) {
        val plan = state.plan ?: return
        val step = plan.steps.getOrNull(state.currentStepIndex) ?: return
        val stepText = "${step.instruction} ${step.expectedOutcome}".lowercase()
        val resultText = outcome.result.lowercase()

        val shouldComplete = when (decision.tool) {
            "open_app" -> {
                val appName = decision.args["name"]?.lowercase().orEmpty()
                appName.isNotBlank() &&
                    (stepText.contains("open") || stepText.contains("launch")) &&
                    (stepText.contains(appName) || resultText.contains(appName))
            }

            "click", "activate_focused", "tap_at" -> {
                val target = decision.args["target"]?.lowercase().orEmpty()
                val opensOrSelects = listOf("open", "select", "choose", "find", "enter")
                    .any { stepText.contains(it) }
                val sendsMessage = stepText.contains("send") &&
                    (target.isLikelySendTarget() || decision.tool == "tap_at" || decision.tool == "activate_focused")
                sendsMessage ||
                    opensOrSelects && (
                        target.isBlank() ||
                            stepText.contains(target) ||
                            resultText.contains(target) ||
                            decision.tool == "tap_at" ||
                            decision.tool == "activate_focused"
                        )
            }

            "type_text" -> {
                val messageText = decision.args["text"]?.lowercase().orEmpty()
                val typingStep = listOf("type", "write", "enter", "compose", "input")
                    .any { stepText.contains(it) }
                typingStep &&
                    messageText.isNotBlank() &&
                    (resultText.contains("typed") || resultText.contains(messageText.take(24)))
            }

            "read_form_state" -> {
                val verifiesInput = listOf("verify", "confirm", "input", "message field", "focused")
                    .any { stepText.contains(it) }
                verifiesInput && formStateHasFocusedInput(resultText)
            }

            else -> false
        }

        if (!shouldComplete) return

        val completedIndex = state.currentStepIndex
        val summary = "Auto-completed after ${decision.tool}: ${step.instruction}"
        state.history.add(HistoryEntry.StepCompleted(completedIndex, summary))
        state.stepObservations.add(
            StepObservation(
                stepIndex = completedIndex,
                toolName = "auto_step_complete",
                outcome = ToolOutcome.Ok(summary),
            )
        )
        state.currentStepIndex++
        state.retryCount = 0
        state.replanCount = 0
        Log.d(TAG, "Plan step ${completedIndex + 1}/${plan.steps.size} auto-complete: $summary")
    }

    private suspend fun loadFactsSnapshot(): List<String> {
        return try {
            factDao.getAllFactsSnapshot().map { "${it.key}: ${it.value}" }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun compactHistoryLine(entry: HistoryEntry): String {
        return when (entry) {
            is HistoryEntry.UserMessage -> "user: ${entry.text}"
            is HistoryEntry.PlanCreated -> "plan created: ${entry.plan.steps.size} steps"
            is HistoryEntry.StepCompleted -> "step ${entry.stepIndex + 1} complete: ${entry.summary}"
            is HistoryEntry.ModelDecision -> when (val decision = entry.decision) {
                is Decision.Act -> "called ${decision.tool}(${decision.args})"
                is Decision.Finish -> "step finish: ${decision.summary}"
            }
            is HistoryEntry.ToolResult -> "${entry.toolName}: ${compactOutcome(entry.outcome)}"
        }.replace(Regex("\\s+"), " ").take(360)
    }

    private fun compactOutcome(outcome: ToolOutcome): String {
        return when (outcome) {
            is ToolOutcome.Ok -> "OK ${outcome.result}"
            is ToolOutcome.TransientErr -> "TRANSIENT ${outcome.message}"
            is ToolOutcome.LogicErr -> "LOGIC ${outcome.message}"
            is ToolOutcome.AuthErr -> "AUTH ${outcome.message}"
            is ToolOutcome.FatalErr -> "FATAL ${outcome.message}"
        }.replace(Regex("\\s+"), " ").take(240)
    }

    /**
     * Execute a tool with dynamic HITL safety check based on screen context.
     */
    private suspend fun executeToolWithSafety(
        decision: Decision.Act,
        state: AgentState,
        serviceContext: ToolServiceContext,
        onStatusUpdate: (String) -> Unit,
    ): ToolOutcome {
        if (decision.tool !in activeToolNames) {
            return ToolOutcome.LogicErr(buildUnknownToolCorrection(decision.tool))
        }

        messagingPreconditionError(decision, state)?.let { return ToolOutcome.LogicErr(it) }

        val tool = toolRegistry.getTool(decision.tool)
            ?: return ToolOutcome.LogicErr(buildUnknownToolCorrection(decision.tool))

        val startTime = System.currentTimeMillis()
        val screenContext = serviceContext.getScreenContext()
        val verdict = RiskAssessor.assess(decision.tool, decision.args, screenContext)

        val outcome = when (verdict) {
            is RiskVerdict.RequireBiometric -> {
                HITLInterceptionWrapper.executeWithAuth(
                    tool,
                    decision.args,
                    serviceContext,
                    appContext,
                    verdict.reason,
                )
            }

            is RiskVerdict.Allow -> {
                val rawOutcome = tool.execute(decision.args, serviceContext)
                handleSpecialTools(decision.tool, decision.args, rawOutcome, serviceContext)
            }
        }

        val duration = System.currentTimeMillis() - startTime
        EventLogger.emit(
            GenieEvent.ToolExecution(
                toolName = decision.tool,
                args = decision.args,
                durationMs = duration,
                success = outcome is ToolOutcome.Ok,
            )
        )

        return outcome
    }

    private fun normalizeDecision(decision: Decision): Decision {
        if (decision !is Decision.Act) return decision

        val normalizedTool = normalizeToolName(decision.tool) ?: return decision
        if (normalizedTool == decision.tool) return decision

        Log.w(TAG, "Normalized planner tool name '${decision.tool}' -> '$normalizedTool'")
        return decision.copy(tool = normalizedTool)
    }

    private fun normalizeToolName(rawToolName: String): String? {
        val toolName = rawToolName.trim()
        val validTools = validToolNames(includeFinish = false)
        if (toolName in validTools) return toolName

        return validTools.firstOrNull { valid ->
            val suffix = valid.substringAfterLast('_')
            toolName == "${valid}_$suffix"
        }
    }

    private fun buildPlannerCorrection(rawError: String): String {
        val sanitizedError = sanitizePlannerError(rawError)
        val malformedToolName = extractMalformedToolName(rawError)
        val nearest = malformedToolName?.let(::findNearestToolName)
        return buildString {
            append("Planner returned an invalid native tool call")
            if (sanitizedError.isNotBlank()) {
                append(": ")
                append(sanitizedError)
            }
            append(". ")
            if (nearest != null) {
                append("Nearest valid tool: '$nearest'. ")
            }
            append("Correction: call exactly one native LiteRT-LM tool. ")
            append("Do not write plain text, tool-call text, or duplicate argument values. ")
            append("Use only the current step's allowed tools from the tool schema.")
        }
    }

    private fun buildUnknownToolCorrection(toolName: String): String {
        val nearest = findNearestToolName(toolName)
        return buildString {
            append("Unknown tool '$toolName'. ")
            if (nearest != null) {
                append("Nearest valid tool: '$nearest'. ")
            }
            append("Correction: call exactly one valid native tool from the schema and use only its declared arguments.")
        }
    }

    private fun messagingPreconditionError(
        decision: Decision.Act,
        state: AgentState,
    ): String? {
        if (!isMessagingGoal(state)) return null

        val currentStep = state.plan
            ?.steps
            ?.getOrNull(state.currentStepIndex)
            ?.let { "${it.instruction} ${it.expectedOutcome}".lowercase() }
            .orEmpty()
        val isFallbackPlan = isGenericFallbackPlan(state)
        val target = decision.args["target"]?.lowercase().orEmpty()

        if (!isFallbackPlan && decision.tool == "type_text" && currentStep.containsAny("verify", "confirm", "check")) {
            return "Messaging precondition failed: current step is verification, not typing. Complete the verification step first, then type during the message-entry step."
        }

        if (decision.tool == "type_text" && !recentFormStateHasFocusedInput(state)) {
            return "Messaging precondition failed: before type_text, prove the chat message input is focused. Call read_form_state first. If no focused message input is present, open the contact/chat and focus the message field before typing."
        }

        if (decision.tool == "click" && target.isLikelySendTarget()) {
            if (!isFallbackPlan && !currentStep.contains("send")) {
                return "Messaging precondition failed: current step is not the Send step. Do not press Send until the plan reaches the Send step."
            }
            if (!recentSuccessfulTypeText(state)) {
                return "Messaging precondition failed: do not press Send until type_text has succeeded for the message body in the open chat."
            }
        }

        if (!isFallbackPlan && decision.tool == "type_text" && currentStep.containsAny("find", "select", "open contact", "open chat", "recipient")) {
            return "Messaging precondition failed: current step is still selecting/opening the recipient. Open the contact chat first; do not type the message while still on the chat list."
        }

        return null
    }

    private fun stepFinishPreconditionError(state: AgentState): String? {
        if (!requiresMessagingSend(state)) return null

        val plan = state.plan ?: return null
        val step = plan.steps.getOrNull(state.currentStepIndex) ?: return null
        val stepText = "${step.instruction} ${step.expectedOutcome}".lowercase()
        val isFinalStep = state.currentStepIndex >= plan.steps.lastIndex
        val isSendOrSentStep = stepText.containsAny(
            "press send",
            "tap send",
            "click send",
            "send the message",
            "message is sent",
            "was sent",
            "visibly sending",
            "complete the user's goal",
        )

        if ((isFinalStep || isSendOrSentStep) && !recentSuccessfulSendAction(state)) {
            return "Messaging finish precondition failed: the message-sending goal cannot finish until a Send action succeeds after type_text."
        }

        return null
    }

    private fun isGenericFallbackPlan(state: AgentState): Boolean {
        val plan = state.plan ?: return false
        val onlyStep = plan.steps.singleOrNull() ?: return false
        return onlyStep.instruction == "Inspect the current screen and complete the user's goal safely." &&
            onlyStep.expectedOutcome == "The user's goal is completed or a clear blocking reason is found."
    }

    private fun isMessagingGoal(state: AgentState): Boolean {
        val goal = state.goal.lowercase()
        val intentText = buildString {
            append(state.intent?.summary.orEmpty())
            append(' ')
            state.intent?.entities?.forEach { (key, value) ->
                append(key)
                append(' ')
                append(value)
                append(' ')
            }
        }.lowercase()
        val combined = "$goal $intentText"
        return combined.containsAny(
            "whatsapp",
            "message",
            "send ",
            "tell ",
            "dm ",
            "chat",
        )
    }

    private fun requiresMessagingSend(state: AgentState): Boolean {
        val goal = state.goal.lowercase()
        val entities = state.intent?.entities.orEmpty()
        val hasMessageEntity = entities["message"]?.isNotBlank() == true
        return hasMessageEntity || goal.containsAny(
            "tell ",
            "message ",
            "text ",
            "send ",
            "notify ",
            "dm ",
        )
    }

    private fun recentFormStateHasFocusedInput(state: AgentState): Boolean {
        return state.history
            .asReversed()
            .take(10)
            .filterIsInstance<HistoryEntry.ToolResult>()
            .firstOrNull { it.toolName == "read_form_state" && it.outcome is ToolOutcome.Ok }
            ?.let { entry ->
                val result = (entry.outcome as ToolOutcome.Ok).result.lowercase()
                formStateHasFocusedInput(result)
            }
            ?: false
    }

    private fun formStateHasFocusedInput(result: String): Boolean {
        val normalized = result.lowercase()
        if (normalized.contains("no form fields")) return false
        return normalized
            .lineSequence()
            .any { line -> line.contains("focused") && !line.contains("disabled") }
    }

    private fun recentSuccessfulTypeText(state: AgentState): Boolean {
        return state.history
            .asReversed()
            .take(12)
            .filterIsInstance<HistoryEntry.ToolResult>()
            .any { it.toolName == "type_text" && it.outcome is ToolOutcome.Ok }
    }

    private fun recentSuccessfulSendAction(state: AgentState): Boolean {
        val lastTypeIndex = state.history.indexOfLast {
            it is HistoryEntry.ToolResult &&
                it.toolName == "type_text" &&
                it.outcome is ToolOutcome.Ok
        }
        if (lastTypeIndex < 0) return false

        return state.history
            .drop(lastTypeIndex + 1)
            .filterIsInstance<HistoryEntry.ToolResult>()
            .any { entry ->
                entry.outcome is ToolOutcome.Ok &&
                    when (entry.toolName) {
                        "click" -> entry.args["target"].orEmpty().isLikelySendTarget()
                        "activate_focused", "tap_at" -> true
                        else -> false
                    }
            }
    }

    private fun cachedPlanHasSendAction(steps: List<Decision.Act>): Boolean {
        val lastTypeIndex = steps.indexOfLast { it.tool == "type_text" }
        if (lastTypeIndex < 0) return false

        return steps
            .drop(lastTypeIndex + 1)
            .any { step ->
                when (step.tool) {
                    "click" -> step.args["target"].orEmpty().isLikelySendTarget()
                    "activate_focused", "tap_at" -> true
                    else -> false
                }
            }
    }

    private fun String.isLikelySendTarget(): Boolean {
        val normalized = trim().lowercase()
        return normalized == "send" ||
            normalized == "send message" ||
            normalized.contains("send button") ||
            normalized.contains("send")
    }

    private fun String.containsAny(vararg needles: String): Boolean {
        return needles.any { contains(it) }
    }

    private fun sanitizePlannerError(rawError: String): String {
        val compact = rawError
            .replace(Regex("\\s+"), " ")
            .replace(Regex("Status Code: \\d+\\. Message: "), "")
            .replace(Regex("with error:.*$", RegexOption.IGNORE_CASE), "")
            .trim()

        val malformedCall = Regex("Failed to parse tool calls from response: (.*)")
            .find(compact)
            ?.groupValues
            ?.getOrNull(1)
            ?.substringBefore(" code block:")
            ?.trim()

        val summary = malformedCall?.let { "malformed tool call '$it'" } ?: compact
        return summary.take(MAX_SANITIZED_PLANNER_ERROR_CHARS)
    }

    private fun extractMalformedToolName(rawError: String): String? {
        return Regex("call:([A-Za-z0-9_]+)").find(rawError)?.groupValues?.getOrNull(1)
    }

    private fun findNearestToolName(rawToolName: String): String? {
        normalizeToolName(rawToolName)?.let { return it }

        val toolName = rawToolName.trim()
        if (toolName.isBlank()) return null

        return validToolNames(includeFinish = false)
            .minByOrNull { levenshteinDistance(toolName, it) }
    }

    private fun validToolNames(includeFinish: Boolean): Set<String> {
        return if (includeFinish) activeToolNames + "finish_task" else activeToolNames
    }

    private fun levenshteinDistance(left: String, right: String): Int {
        if (left == right) return 0
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length

        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)

        for (i in left.indices) {
            current[0] = i + 1
            for (j in right.indices) {
                val cost = if (left[i] == right[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + cost,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }

        return previous[right.length]
    }

    /**
     * Handle tools that need app-context side effects (DB/PDF parsing).
     */
    private suspend fun handleSpecialTools(
        toolName: String,
        args: Map<String, String>,
        rawOutcome: ToolOutcome,
        serviceContext: ToolServiceContext,
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
                } else {
                    rawOutcome
                }
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

            toolName == "read_pdf_page_range" -> {
                readPdfPageRange(args)
            }

            toolName == "visualize_concept" -> {
                handleVisualizeConcept(args)
            }

            toolName == "teach_with_board" ||
                toolName == "board_add_object" ||
                toolName == "board_update_object" ||
                toolName == "board_remove_object" ||
                toolName == "board_focus_object" ||
                toolName == "board_reveal_step" ||
                toolName == "board_next_step" ||
                toolName == "board_prev_step" ||
                toolName == "board_replay_step" ||
                toolName == "board_set_narration" -> {
                handleTeachingBoardTool(toolName, args)
            }

            toolName == "annotate_scene" ||
                toolName == "annotation_add_box" ||
                toolName == "annotation_add_label" ||
                toolName == "annotation_add_pointer" ||
                toolName == "annotation_clear" ||
                toolName == "annotation_replay" -> {
                handleAnnotationTool(toolName, args, serviceContext)
            }

            else -> rawOutcome
        }
    }

    private fun handleVisualizeConcept(args: Map<String, String>): ToolOutcome {
        val normalized = normalizeVisualizeArgs(args) ?: return ToolOutcome.LogicErr(
            "Invalid visualize_concept args. Require valid operation, scene_id, and bounded JSON payloads."
        )

        val result = when (normalized.operation) {
            "create_scene" -> {
                val diagramType = normalized.diagramType
                    ?: return ToolOutcome.LogicErr("Missing or invalid 'diagram_type' argument for create_scene")
                VisualizerSceneStore.createScene(
                    sceneId = normalized.sceneId,
                    diagramType = diagramType,
                    title = normalized.title,
                    nodesJson = normalized.nodesJson,
                    edgesJson = normalized.edgesJson,
                )
            }

            "update_scene" -> VisualizerSceneStore.updateScene(
                sceneId = normalized.sceneId,
                title = normalized.title,
                nodesJson = normalized.nodesJson,
                edgesJson = normalized.edgesJson,
            )

            "highlight" -> VisualizerSceneStore.highlightScene(
                sceneId = normalized.sceneId,
                focusCsv = normalized.focusNodeIds,
            )

            "clear_scene" -> VisualizerSceneStore.clearScene(normalized.sceneId)
            "export_scene" -> VisualizerSceneStore.exportScene(normalized.sceneId)

            else -> return ToolOutcome.LogicErr(
                "Invalid 'operation' '${normalized.operation}'. Must be one of: create_scene, update_scene, highlight, clear_scene, export_scene"
            )
        }

        return if (result.ok) ToolOutcome.Ok(result.message) else ToolOutcome.LogicErr(result.message)
    }

    private fun handleTeachingBoardTool(
        toolName: String,
        args: Map<String, String>,
    ): ToolOutcome {
        val sceneId = args["scene_id"]?.trim()
            ?: return ToolOutcome.LogicErr("Missing 'scene_id' argument")
        if (!SCENE_ID_PATTERN.matches(sceneId)) {
            return ToolOutcome.LogicErr("Invalid 'scene_id'. Use 1-64 chars: letters, numbers, underscore, hyphen")
        }

        val result = when (toolName) {
            "teach_with_board" -> {
                val objects = args["objects"]?.trim()
                val steps = args["steps"]?.trim()
                if ((objects?.length ?: 0) > MAX_BOARD_OBJECTS_JSON_CHARS ||
                    (steps?.length ?: 0) > MAX_BOARD_STEPS_JSON_CHARS
                ) {
                    return ToolOutcome.LogicErr("'objects' or 'steps' payload too large for teach_with_board")
                }
                VisualizerSceneStore.teachWithBoard(
                    sceneId = sceneId,
                    title = args["title"]?.trim()?.take(120),
                    theme = args["board_theme"]?.trim()?.take(40),
                    objectsJson = objects,
                    stepsJson = steps,
                    narrationText = args["narration_text"]?.trim()?.take(600),
                )
            }

            "board_add_object" -> {
                val objectId = args["object_id"]?.trim()
                    ?: return ToolOutcome.LogicErr("Missing 'object_id' argument")
                if (!OBJECT_ID_PATTERN.matches(objectId)) {
                    return ToolOutcome.LogicErr("Invalid 'object_id'. Use 1-64 chars: letters, numbers, underscore, hyphen")
                }
                val objectType = args["object_type"]?.trim()?.lowercase()
                    ?: return ToolOutcome.LogicErr("Missing 'object_type' argument")
                if (objectType !in ALLOWED_BOARD_OBJECT_TYPES) {
                    return ToolOutcome.LogicErr(
                        "Invalid 'object_type' '$objectType'. Must be one of: ${ALLOWED_BOARD_OBJECT_TYPES.joinToString()}"
                    )
                }
                val x = args["x"]?.toFloatOrNull()
                    ?: return ToolOutcome.LogicErr("Missing or invalid 'x' argument")
                val y = args["y"]?.toFloatOrNull()
                    ?: return ToolOutcome.LogicErr("Missing or invalid 'y' argument")
                val width = args["width"]?.trim()?.takeIf { it.isNotEmpty() }?.toFloatOrNull()
                    ?: if (args["width"].isNullOrBlank()) null else return ToolOutcome.LogicErr("Invalid 'width' argument")
                val height = args["height"]?.trim()?.takeIf { it.isNotEmpty() }?.toFloatOrNull()
                    ?: if (args["height"].isNullOrBlank()) null else return ToolOutcome.LogicErr("Invalid 'height' argument")
                VisualizerSceneStore.boardAddObject(
                    sceneId = sceneId,
                    objectId = objectId,
                    objectType = objectType,
                    text = args["text"]?.take(240),
                    x = x,
                    y = y,
                    width = width,
                    height = height,
                    styleJson = args["style"]?.takeIf { it.isNotBlank() }?.take(4_000),
                    stepId = args["step_id"]?.trim()?.takeIf { it.isNotEmpty() },
                    animation = args["animation"]?.trim()?.takeIf { it.isNotEmpty() }?.take(40),
                )
            }

            "board_update_object" -> {
                val objectId = args["object_id"]?.trim()
                    ?: return ToolOutcome.LogicErr("Missing 'object_id' argument")
                if (!OBJECT_ID_PATTERN.matches(objectId)) {
                    return ToolOutcome.LogicErr("Invalid 'object_id'. Use 1-64 chars: letters, numbers, underscore, hyphen")
                }
                val objectType = args["object_type"]?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()
                if (objectType != null && objectType !in ALLOWED_BOARD_OBJECT_TYPES) {
                    return ToolOutcome.LogicErr(
                        "Invalid 'object_type' '$objectType'. Must be one of: ${ALLOWED_BOARD_OBJECT_TYPES.joinToString()}"
                    )
                }
                val x = if (args["x"].isNullOrBlank()) null else parseOptionalFloatArg(args["x"])
                    ?: return ToolOutcome.LogicErr("Invalid 'x' argument")
                val y = if (args["y"].isNullOrBlank()) null else parseOptionalFloatArg(args["y"])
                    ?: return ToolOutcome.LogicErr("Invalid 'y' argument")
                val width = if (args["width"].isNullOrBlank()) null else parseOptionalFloatArg(args["width"])
                    ?: return ToolOutcome.LogicErr("Invalid 'width' argument")
                val height = if (args["height"].isNullOrBlank()) null else parseOptionalFloatArg(args["height"])
                    ?: return ToolOutcome.LogicErr("Invalid 'height' argument")
                VisualizerSceneStore.boardUpdateObject(
                    sceneId = sceneId,
                    objectId = objectId,
                    objectType = objectType,
                    text = args["text"]?.takeIf { it.isNotBlank() }?.take(240),
                    x = x,
                    y = y,
                    width = width,
                    height = height,
                    styleJson = args["style"]?.takeIf { it.isNotBlank() }?.take(4_000),
                    stepId = args["step_id"]?.takeIf { it.isNotBlank() }?.trim(),
                    animation = args["animation"]?.takeIf { it.isNotBlank() }?.trim(),
                )
            }

            "board_remove_object" -> {
                val objectId = args["object_id"]?.trim()
                    ?: return ToolOutcome.LogicErr("Missing 'object_id' argument")
                if (!OBJECT_ID_PATTERN.matches(objectId)) {
                    return ToolOutcome.LogicErr("Invalid 'object_id'. Use 1-64 chars: letters, numbers, underscore, hyphen")
                }
                VisualizerSceneStore.boardRemoveObject(sceneId, objectId)
            }

            "board_focus_object" -> {
                val objectId = args["object_id"]?.trim()
                    ?: return ToolOutcome.LogicErr("Missing 'object_id' argument")
                if (!OBJECT_ID_PATTERN.matches(objectId)) {
                    return ToolOutcome.LogicErr("Invalid 'object_id'. Use 1-64 chars: letters, numbers, underscore, hyphen")
                }
                VisualizerSceneStore.boardFocusObject(sceneId, objectId)
            }

            "board_reveal_step" -> {
                val stepId = args["step_id"]?.trim()
                    ?: return ToolOutcome.LogicErr("Missing 'step_id' argument")
                if (!OBJECT_ID_PATTERN.matches(stepId)) {
                    return ToolOutcome.LogicErr("Invalid 'step_id'. Use 1-64 chars: letters, numbers, underscore, hyphen")
                }
                VisualizerSceneStore.boardRevealStep(sceneId, stepId)
            }

            "board_next_step" -> VisualizerSceneStore.boardNextStep(sceneId)
            "board_prev_step" -> VisualizerSceneStore.boardPrevStep(sceneId)
            "board_replay_step" -> VisualizerSceneStore.boardReplayStep(sceneId)

            "board_set_narration" -> {
                val narrationText = args["narration_text"]?.trim()
                    ?: return ToolOutcome.LogicErr("Missing 'narration_text' argument")
                if (narrationText.length > 600) {
                    return ToolOutcome.LogicErr("'narration_text' is too long. Keep it under 600 characters")
                }
                VisualizerSceneStore.boardSetNarration(sceneId, narrationText)
            }

            else -> return ToolOutcome.LogicErr("Unknown teaching board tool '$toolName'")
        }

        return if (result.ok) ToolOutcome.Ok(result.message) else ToolOutcome.LogicErr(result.message)
    }

    private fun parseOptionalFloatArg(raw: String?): Float? {
        if (raw.isNullOrBlank()) return null
        return raw.trim().toFloatOrNull()
    }

    private suspend fun handleAnnotationTool(
        toolName: String,
        args: Map<String, String>,
        serviceContext: ToolServiceContext,
    ): ToolOutcome {
        val sessionId = args["session_id"]?.trim()
            ?: return ToolOutcome.LogicErr("Missing 'session_id' argument")
        if (!SCENE_ID_PATTERN.matches(sessionId)) {
            return ToolOutcome.LogicErr("Invalid 'session_id'. Use 1-64 chars: letters, numbers, underscore, hyphen")
        }

        val viewport = serviceContext.getViewportInfo()
        if (viewport.widthPx <= 0 || viewport.heightPx <= 0) {
            return ToolOutcome.TransientErr("Viewport dimensions are unavailable")
        }

        val result = when (toolName) {
            "annotate_scene" -> {
                val title = args["title"]?.trim()?.take(120)
                    ?: "Screen Annotation"
                val overlayStarted = serviceContext.annotationStartSession(sessionId, title)
                if (!overlayStarted) {
                    return ToolOutcome.TransientErr("Failed to start annotation overlay session")
                }
                annotationSessionTitle[sessionId] = title
                annotationPlaybackBySession[sessionId] = mutableListOf()
                AnnotationSessionStore.startSession(sessionId, title, viewport)
            }

            "annotation_add_box" -> {
                val opId = args["op_id"]?.trim()
                    ?: return ToolOutcome.LogicErr("Missing 'op_id' argument")
                if (!OBJECT_ID_PATTERN.matches(opId)) {
                    return ToolOutcome.LogicErr("Invalid 'op_id'. Use 1-64 chars: letters, numbers, underscore, hyphen")
                }
                val delayMs = parseDelay(args["delay_ms"])
                    ?: return ToolOutcome.LogicErr("Invalid 'delay_ms'. Use 0-20000 milliseconds")

                val rawX = parseRequiredFloat(args, "x")
                    ?: return ToolOutcome.LogicErr("Missing or invalid 'x' argument")
                val rawY = parseRequiredFloat(args, "y")
                    ?: return ToolOutcome.LogicErr("Missing or invalid 'y' argument")
                val rawWidth = parseOptionalFloatArg(args["width"])
                val rawHeight = parseOptionalFloatArg(args["height"])
                val rawX2 = parseOptionalFloatArg(args["x2"])
                val rawY2 = parseOptionalFloatArg(args["y2"])

                if ((rawWidth == null || rawHeight == null) && (rawX2 == null || rawY2 == null)) {
                    return ToolOutcome.LogicErr("Provide either width/height or x2/y2")
                }

                val x = AnnotationSessionStore.normalizedToPx(rawX, viewport.widthPx)
                val y = AnnotationSessionStore.normalizedToPx(rawY, viewport.heightPx)
                val width = if (rawWidth != null) {
                    AnnotationSessionStore.normalizedToPx(rawWidth, viewport.widthPx)
                } else {
                    AnnotationSessionStore.spanToPx(rawX, rawX2 ?: rawX, viewport.widthPx)
                }
                val height = if (rawHeight != null) {
                    AnnotationSessionStore.normalizedToPx(rawHeight, viewport.heightPx)
                } else {
                    AnnotationSessionStore.spanToPx(rawY, rawY2 ?: rawY, viewport.heightPx)
                }

                if (delayMs > 0L) delay(delayMs)

                val style = parseAnnotationStyle(args["style"])
                    ?: return ToolOutcome.LogicErr("Invalid 'style' JSON")
                val boxDrawn = serviceContext.annotationDrawBox(
                    sessionId = sessionId,
                    opId = opId,
                    delayMs = delayMs,
                    x = x,
                    y = y,
                    width = width,
                    height = height,
                    label = args["label"]?.trim().orEmpty(),
                    style = style,
                )
                if (!boxDrawn) {
                    return ToolOutcome.TransientErr("Failed to draw annotation box overlay")
                }
                annotationPlaybackBySession.getOrPut(sessionId) { mutableListOf() }
                    .add(
                        AnnotationPlaybackOp.Box(
                            delayMs = delayMs,
                            opId = opId,
                            x = x,
                            y = y,
                            width = width,
                            height = height,
                            label = args["label"]?.trim().orEmpty(),
                            style = style,
                        )
                    )
                AnnotationSessionStore.addBox(
                    sessionId = sessionId,
                    opId = opId,
                    delayMs = delayMs,
                    x = x,
                    y = y,
                    width = width,
                    height = height,
                    label = args["label"]?.trim().orEmpty(),
                    style = style,
                )
            }

            "annotation_add_label" -> {
                val opId = args["op_id"]?.trim()
                    ?: return ToolOutcome.LogicErr("Missing 'op_id' argument")
                if (!OBJECT_ID_PATTERN.matches(opId)) {
                    return ToolOutcome.LogicErr("Invalid 'op_id'. Use 1-64 chars: letters, numbers, underscore, hyphen")
                }
                val text = args["text"]?.trim()
                    ?: return ToolOutcome.LogicErr("Missing 'text' argument")
                if (text.isBlank()) {
                    return ToolOutcome.LogicErr("'text' cannot be blank")
                }
                val delayMs = parseDelay(args["delay_ms"])
                    ?: return ToolOutcome.LogicErr("Invalid 'delay_ms'. Use 0-20000 milliseconds")
                val rawX = parseRequiredFloat(args, "x")
                    ?: return ToolOutcome.LogicErr("Missing or invalid 'x' argument")
                val rawY = parseRequiredFloat(args, "y")
                    ?: return ToolOutcome.LogicErr("Missing or invalid 'y' argument")
                if (delayMs > 0L) delay(delayMs)
                val style = parseAnnotationStyle(args["style"])
                    ?: return ToolOutcome.LogicErr("Invalid 'style' JSON")
                val pxX = AnnotationSessionStore.normalizedToPx(rawX, viewport.widthPx)
                val pxY = AnnotationSessionStore.normalizedToPx(rawY, viewport.heightPx)
                val labelDrawn = serviceContext.annotationDrawLabel(
                    sessionId = sessionId,
                    opId = opId,
                    delayMs = delayMs,
                    x = pxX,
                    y = pxY,
                    text = text,
                    style = style,
                )
                if (!labelDrawn) {
                    return ToolOutcome.TransientErr("Failed to draw annotation label overlay")
                }
                annotationPlaybackBySession.getOrPut(sessionId) { mutableListOf() }
                    .add(
                        AnnotationPlaybackOp.Label(
                            delayMs = delayMs,
                            opId = opId,
                            x = pxX,
                            y = pxY,
                            text = text,
                            style = style,
                        )
                    )
                AnnotationSessionStore.addLabel(
                    sessionId = sessionId,
                    opId = opId,
                    delayMs = delayMs,
                    x = pxX,
                    y = pxY,
                    text = text,
                    style = style,
                )
            }

            "annotation_add_pointer" -> {
                val opId = args["op_id"]?.trim()
                    ?: return ToolOutcome.LogicErr("Missing 'op_id' argument")
                if (!OBJECT_ID_PATTERN.matches(opId)) {
                    return ToolOutcome.LogicErr("Invalid 'op_id'. Use 1-64 chars: letters, numbers, underscore, hyphen")
                }
                val delayMs = parseDelay(args["delay_ms"])
                    ?: return ToolOutcome.LogicErr("Invalid 'delay_ms'. Use 0-20000 milliseconds")
                val rawX = parseRequiredFloat(args, "x")
                    ?: return ToolOutcome.LogicErr("Missing or invalid 'x' argument")
                val rawY = parseRequiredFloat(args, "y")
                    ?: return ToolOutcome.LogicErr("Missing or invalid 'y' argument")
                if (delayMs > 0L) delay(delayMs)
                val style = parseAnnotationStyle(args["style"])
                    ?: return ToolOutcome.LogicErr("Invalid 'style' JSON")
                val pxX = AnnotationSessionStore.normalizedToPx(rawX, viewport.widthPx)
                val pxY = AnnotationSessionStore.normalizedToPx(rawY, viewport.heightPx)
                val rawTargetX = parseOptionalFloatArg(args["target_x"])
                val rawTargetY = parseOptionalFloatArg(args["target_y"])
                val pxTargetX = rawTargetX
                    ?.let { AnnotationSessionStore.normalizedToPx(it, viewport.widthPx) }
                    ?: (pxX + 120f).coerceIn(0f, viewport.widthPx.toFloat())
                val pxTargetY = rawTargetY
                    ?.let { AnnotationSessionStore.normalizedToPx(it, viewport.heightPx) }
                    ?: (pxY - 90f).coerceIn(0f, viewport.heightPx.toFloat())
                val pointerDrawn = serviceContext.annotationDrawPointer(
                    sessionId = sessionId,
                    opId = opId,
                    delayMs = delayMs,
                    x = pxX,
                    y = pxY,
                    targetX = pxTargetX,
                    targetY = pxTargetY,
                    text = args["text"]?.trim().orEmpty(),
                    style = style,
                )
                if (!pointerDrawn) {
                    return ToolOutcome.TransientErr("Failed to draw annotation pointer overlay")
                }
                annotationPlaybackBySession.getOrPut(sessionId) { mutableListOf() }
                    .add(
                        AnnotationPlaybackOp.Pointer(
                            delayMs = delayMs,
                            opId = opId,
                            x = pxX,
                            y = pxY,
                            targetX = pxTargetX,
                            targetY = pxTargetY,
                            text = args["text"]?.trim().orEmpty(),
                            style = style,
                        )
                    )
                AnnotationSessionStore.addPointer(
                    sessionId = sessionId,
                    opId = opId,
                    delayMs = delayMs,
                    x = pxX,
                    y = pxY,
                    targetX = pxTargetX,
                    targetY = pxTargetY,
                    text = args["text"]?.trim().orEmpty(),
                    style = style,
                )
            }

            "annotation_clear" -> {
                val overlayCleared = serviceContext.annotationClearSession(sessionId)
                annotationPlaybackBySession.remove(sessionId)
                annotationSessionTitle.remove(sessionId)
                val clearResult = AnnotationSessionStore.clearSession(sessionId)
                if (!overlayCleared) {
                    SceneStoreResult(
                        ok = false,
                        message = if (clearResult.ok) {
                            "Cleared persisted annotation session but failed to clear live overlay"
                        } else {
                            "Failed to clear live overlay and persisted session: ${clearResult.message}"
                        },
                    )
                } else {
                    clearResult
                }
            }

            "annotation_replay" -> {
                val overlayReplayed = serviceContext.annotationReplaySession(sessionId)
                val replayResult = AnnotationSessionStore.replaySession(sessionId)
                if (!overlayReplayed) {
                    SceneStoreResult(
                        ok = false,
                        message = if (replayResult.ok) {
                            "Replayed persisted annotation session but failed to replay live overlay"
                        } else {
                            "Failed to replay live overlay and persisted session: ${replayResult.message}"
                        },
                    )
                } else {
                    replayResult
                }
            }
            else -> return ToolOutcome.LogicErr("Unknown annotation tool '$toolName'")
        }

        return if (result.ok) ToolOutcome.Ok(result.message) else ToolOutcome.LogicErr(result.message)
    }

    private fun parseRequiredFloat(args: Map<String, String>, key: String): Float? {
        return args[key]?.trim()?.takeIf { it.isNotEmpty() }?.toFloatOrNull()
    }

    private fun parseDelay(rawDelay: String?): Long? {
        val value = rawDelay?.trim()?.takeIf { it.isNotEmpty() }?.toLongOrNull() ?: 0L
        if (value < 0L || value > 20_000L) return null
        return value
    }

    private fun parseAnnotationStyle(raw: String?): BoardStyle? {
        if (raw.isNullOrBlank()) return BoardStyle(
            strokeColor = "#38BDF8",
            textColor = "#F8FAFC",
            strokeWidth = 3f,
            fillColor = "#1E293B",
            alpha = 0.95f,
        )
        if (raw.length > MAX_ANNOTATION_STYLE_JSON_CHARS) return null
        return runCatching { json.decodeFromString<BoardStyle>(raw) }.getOrNull()
    }

    private fun normalizeVisualizeArgs(args: Map<String, String>): NormalizedVisualizeArgs? {
        val rawOp = args["operation"]?.trim()?.lowercase() ?: return null
        val operation = when (rawOp) {
            "create", "create_scene" -> "create_scene"
            "update", "update_scene" -> "update_scene"
            "highlight", "focus" -> "highlight"
            "clear", "clear_scene", "delete", "delete_scene" -> "clear_scene"
            "export", "export_scene" -> "export_scene"
            else -> null
        } ?: return null

        val sceneId = args["scene_id"]?.trim() ?: return null
        if (!SCENE_ID_PATTERN.matches(sceneId)) return null

        val rawNodes = args["nodes"]?.trim()
        val rawEdges = args["edges"]?.trim()
        if ((rawNodes?.length ?: 0) > MAX_VISUALIZER_JSON_CHARS) return null
        if ((rawEdges?.length ?: 0) > MAX_VISUALIZER_JSON_CHARS) return null

        val title = args["title"]?.trim()?.take(120)
        val diagramType = args["diagram_type"]
            ?.trim()
            ?.lowercase()
            ?.takeIf { it in ALLOWED_DIAGRAM_TYPES }
        val focusCsv = args["focus_node_ids"]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?.joinToString(",")

        return NormalizedVisualizeArgs(
            operation = operation,
            sceneId = sceneId,
            diagramType = diagramType,
            title = title,
            nodesJson = rawNodes,
            edgesJson = rawEdges,
            focusNodeIds = focusCsv,
        )
    }

    private data class NormalizedVisualizeArgs(
        val operation: String,
        val sceneId: String,
        val diagramType: String?,
        val title: String?,
        val nodesJson: String?,
        val edgesJson: String?,
        val focusNodeIds: String?,
    )

    private suspend fun readPdfPageRange(args: Map<String, String>): ToolOutcome {
        val filePath = args["file_path"] ?: args["path"]
            ?: return ToolOutcome.LogicErr("Missing 'file_path' argument")
        val startPage = args["start_page"]?.toIntOrNull()
            ?: return ToolOutcome.LogicErr("Missing or invalid 'start_page' argument")
        val endPage = args["end_page"]?.toIntOrNull()
            ?: return ToolOutcome.LogicErr("Missing or invalid 'end_page' argument")
        val maxChars = (args["max_chars"]?.toIntOrNull() ?: 8_000).coerceAtLeast(500)

        if (startPage <= 0 || endPage <= 0) {
            return ToolOutcome.LogicErr("'start_page' and 'end_page' must be >= 1")
        }
        if (startPage > endPage) {
            return ToolOutcome.LogicErr("'start_page' cannot be greater than 'end_page'")
        }

        return withContext(Dispatchers.IO) {
            val pdfFile = File(filePath)
            if (!pdfFile.exists()) {
                return@withContext ToolOutcome.LogicErr("PDF file not found: '$filePath'")
            }
            if (!pdfFile.isFile || !pdfFile.name.lowercase().endsWith(".pdf")) {
                return@withContext ToolOutcome.LogicErr("File must be a .pdf: '$filePath'")
            }

            try {
                ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        val pageCount = renderer.pageCount
                        if (pageCount <= 0) {
                            return@withContext ToolOutcome.TransientErr("PDF has no pages")
                        }

                        val clampedStart = startPage.coerceIn(1, pageCount)
                        val clampedEnd = endPage.coerceIn(1, pageCount)
                        if (clampedStart > clampedEnd) {
                            return@withContext ToolOutcome.LogicErr(
                                "Requested page range is outside document bounds (1..$pageCount)"
                            )
                        }

                        val missingPages = mutableListOf<Int>()
                        val sb = StringBuilder()

                        for (pageNumber in clampedStart..clampedEnd) {
                            if (sb.length >= maxChars) break

                            renderer.openPage(pageNumber - 1).use { page ->
                                val pageText = extractPageText(page)
                                if (pageText.isBlank()) {
                                    missingPages.add(pageNumber)
                                } else {
                                    val chunk = "[PAGE $pageNumber]\n$pageText\n\n"
                                    val remaining = maxChars - sb.length
                                    if (remaining > 0) {
                                        sb.append(chunk.take(remaining))
                                    }
                                }
                            }
                        }

                        if (sb.isEmpty()) {
                            val warning = if (Build.VERSION.SDK_INT < 35) {
                                "No extractable PDF text on this Android version. Use screenshot + image analysis fallback."
                            } else {
                                "No extractable text in requested pages. This PDF may be image-only."
                            }
                            return@withContext ToolOutcome.TransientErr(warning)
                        }

                        val header = "PDF range read: pages $clampedStart-$clampedEnd of $pageCount"
                        val suffix = if (missingPages.isNotEmpty()) {
                            "\n\n[WARNING] No text found on pages: ${missingPages.joinToString(", ")}. Use image analysis fallback for those pages."
                        } else {
                            ""
                        }

                        ToolOutcome.Ok("$header\n\n${sb.toString().trim()}$suffix")
                    }
                }
            } catch (e: Exception) {
                ToolOutcome.TransientErr("Failed to read PDF page range: ${e.message}")
            }
        }
    }

    private fun extractPageText(page: PdfRenderer.Page): String {
        if (Build.VERSION.SDK_INT < 35) return ""

        return try {
            @Suppress("NewApi")
            page.textContents
                .mapNotNull { it?.text?.toString() }
                .joinToString("\n")
                .trim()
        } catch (_: Exception) {
            ""
        }
    }

    private suspend fun executeCachedPlan(
        state: AgentState,
        skillMatch: SkillMatch.Cached,
        serviceContext: ToolServiceContext,
        onStatusUpdate: (String) -> Unit,
        onToolExecuting: (String, String?) -> Unit,
    ): String {
        for (step in skillMatch.steps) {
            val humanReadable = getHumanReadableToolAction(step.tool, step.args)
            onToolExecuting(humanReadable.first, humanReadable.second)

            val outcome = executeToolWithSafety(step, state, serviceContext, onStatusUpdate)
            state.history.add(HistoryEntry.ToolResult(step.tool, step.args, outcome))

            when (outcome) {
                is ToolOutcome.Ok -> Unit

                is ToolOutcome.TransientErr, is ToolOutcome.LogicErr -> {
                    Log.w(TAG, "Cached plan step failed: ${step.tool}, falling back to live planning")
                    return executeGoalInternal(
                        goal = state.goal,
                        serviceContext = serviceContext,
                        onStatusUpdate = onStatusUpdate,
                        onToolExecuting = onToolExecuting,
                        allowSkillLookup = false,
                        isNovelPlan = false,
                    )
                }

                is ToolOutcome.AuthErr -> {
                    onStatusUpdate("Action requires your authorization, which was denied.")
                    return "Stopped: user denied authorization for ${step.tool}"
                }

                is ToolOutcome.FatalErr -> {
                    onStatusUpdate("Something went wrong. Please try again.")
                    return "Fatal error: ${outcome.message}"
                }
            }
        }

        if (skillMatch is SkillMatch.Stored) {
            try {
                skillDao.incrementSuccessCount(skillMatch.skillId)
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "Failed to increment success count for skill ${skillMatch.skillId}: ${e.message}"
                )
            }
        }

        onStatusUpdate("Done!")
        return when (skillMatch) {
            is SkillMatch.Stored -> "Completed from cached skill"
            is SkillMatch.BuiltIn -> "Completed from built-in skill"
        }
    }

    private suspend fun writeSkill(state: AgentState) {
        try {
            if (requiresMessagingSend(state) && !recentSuccessfulSendAction(state)) {
                Log.w(TAG, "Skipping skill write because messaging goal has no successful Send action")
                return
            }

            val actSteps = mutableListOf<Decision.Act>()
            var pendingAct: Decision.Act? = null
            state.history.forEach { entry ->
                when (entry) {
                    is HistoryEntry.ModelDecision -> {
                        pendingAct = entry.decision as? Decision.Act
                    }
                    is HistoryEntry.ToolResult -> {
                        val act = pendingAct
                        if (act != null &&
                            act.tool == entry.toolName &&
                            act.args == entry.args &&
                            entry.outcome is ToolOutcome.Ok
                        ) {
                            actSteps.add(act)
                        }
                        pendingAct = null
                    }
                    else -> Unit
                }
            }

            if (actSteps.isEmpty()) return

            val planJson = json.encodeToString(actSteps)
            val goalPattern = skillGoalPatternFor(state, actSteps)
            val skill = Skill(
                goalPattern = goalPattern,
                planJson = planJson,
                successCount = 1,
            )
            skillDao.insert(skill)

            EventLogger.emit(
                GenieEvent.SkillWritten(
                    goalPattern = skill.goalPattern,
                    stepCount = actSteps.size,
                )
            )
            Log.d(TAG, "Written new skill: '${skill.goalPattern}' (${actSteps.size} steps)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write skill: ${e.message}")
        }
    }

    private fun skillGoalPatternFor(state: AgentState, actSteps: List<Decision.Act>): String {
        return if (isSuccessfulWhatsAppMessageSkill(state, actSteps)) {
            "send whatsapp message"
        } else {
            state.goal.lowercase().take(100)
        }
    }

    private fun isSuccessfulWhatsAppMessageSkill(
        state: AgentState,
        actSteps: List<Decision.Act>,
    ): Boolean {
        if (!requiresMessagingSend(state)) return false
        if (!recentSuccessfulSendAction(state)) return false
        if (!hasWhatsAppContext(state, actSteps)) return false

        val lastTypeIndex = actSteps.indexOfLast {
            it.tool == "type_text" && it.args["text"].orEmpty().isNotBlank()
        }
        if (lastTypeIndex < 0) return false

        return actSteps
            .drop(lastTypeIndex + 1)
            .any { it.isSendAction() }
    }

    private fun hasWhatsAppContext(
        state: AgentState,
        actSteps: List<Decision.Act>,
    ): Boolean {
        val intentText = buildString {
            append(state.goal)
            append(' ')
            append(state.intent?.summary.orEmpty())
            append(' ')
            state.intent?.entities?.forEach { (key, value) ->
                append(key)
                append(' ')
                append(value)
                append(' ')
            }
        }

        return intentText.contains("whatsapp", ignoreCase = true) ||
            actSteps.any { step ->
                step.tool == "open_app" &&
                    step.args["name"].orEmpty().contains("whatsapp", ignoreCase = true)
            }
    }

    private fun Decision.Act.isSendAction(): Boolean {
        return when (tool) {
            "click" -> args["target"].orEmpty().isLikelySendTarget()
            "activate_focused", "tap_at" -> true
            else -> false
        }
    }

    private fun String.isGenericMessagingSkillPattern(): Boolean {
        return trim().lowercase() == "send whatsapp message"
    }

    private fun getHumanReadableToolAction(toolName: String, args: Map<String, String>): Pair<String, String?> {
        return when (toolName) {
            "tap_at" -> "Tapping screen..." to listOfNotNull(args["x"], args["y"]).joinToString(", ").takeIf { it.isNotBlank() }
            "click" -> "Tapping element…" to args["target"]
            "type_text" -> "Typing text…" to args["text"]?.take(30)
            "open_app" -> "Opening app…" to args["name"]
            "read_screen_summary", "read_screen", "where_am_i", "read_nearby_context", "what_can_i_do_here" -> "Analyzing screen…" to null
            "swipe" -> "Swiping screen…" to args["direction"]
            "scroll", "scroll_forward", "scroll_backward" -> "Scrolling screen…" to null
            "focus_next", "focus_previous", "focus_first" -> "Navigating focus…" to null
            "focus_by_text", "focus_element_by_text" -> "Finding text…" to args["target"]
            "focus_by_role" -> "Finding element…" to args["role"]
            "activate_focused" -> "Activating focused item…" to null
            "take_screenshot" -> "Capturing screen…" to null
            "read_recent_events", "read_screen_changes" -> "Checking screen updates…" to null
            "read_form_state" -> "Reading form…" to null
            "save_fact" -> "Remembering detail…" to args["key"]
            "retrieve_fact" -> "Recalling memory…" to args["key"]
            "read_pdf_page_range" -> "Reading document…" to args["file_path"]?.substringAfterLast('/')
            "finish_task" -> "Completing step…" to args["summary"]
            else -> {
                if (toolName.startsWith("board_") || toolName == "teach_with_board") "Teaching concept…" to null
                else if (toolName.startsWith("annotation_") || toolName == "annotate_scene") "Drawing annotation…" to null
                else if (toolName.startsWith("visualize_")) "Visualizing concept…" to null
                else "Executing action…" to toolName
            }
        }
    }
}
