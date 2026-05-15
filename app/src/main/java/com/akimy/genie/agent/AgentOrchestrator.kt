package com.akimy.genie.agent

import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.akimy.genie.QuizActivity
import com.akimy.genie.VisualizerCanvasActivity
import com.akimy.genie.data.FactDao
import com.akimy.genie.data.Skill
import com.akimy.genie.data.SkillDao
import com.akimy.genie.engine.AgentResponse
import com.akimy.genie.engine.GenieEngine
import com.akimy.genie.telemetry.ErrorTaxonomy
import com.akimy.genie.telemetry.EventLogger
import com.akimy.genie.telemetry.GenieEvent
import com.akimy.genie.tools.BoardObject
import com.akimy.genie.tools.BoardPoint
import com.akimy.genie.tools.BoardSize
import com.akimy.genie.tools.BoardStep
import com.akimy.genie.tools.BoardStyle
import com.akimy.genie.tools.HITLInterceptionWrapper
import com.akimy.genie.tools.RiskAssessor
import com.akimy.genie.tools.RiskVerdict
import com.akimy.genie.tools.BOARD_OBJECT_TYPES
import com.akimy.genie.tools.QuizQuestion
import com.akimy.genie.tools.QuizSession
import com.akimy.genie.tools.QuizStore
import com.akimy.genie.tools.SceneStoreResult
import com.akimy.genie.tools.ToolRegistry
import com.akimy.genie.tools.ToolProfile
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

private const val TAG = "GenieOrchestrator"
private const val MAX_VISUALIZER_JSON_CHARS = 24_000
private const val MAX_BOARD_OBJECTS_JSON_CHARS = 32_000
private const val MAX_BOARD_STEPS_JSON_CHARS = 16_000
private const val MAX_SANITIZED_PLANNER_ERROR_CHARS = 220
private const val DEFAULT_TEACHING_SCENE_ID = "teaching_session"
private val SCENE_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
private val OBJECT_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
private val ALLOWED_DIAGRAM_TYPES = setOf("flowchart", "cycle", "timeline", "mindmap", "table")
private val ALLOWED_BOARD_OBJECT_TYPES = BOARD_OBJECT_TYPES

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
    private val toolProfile: ToolProfile? = null,
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
    // Maintain up to 10 back-and-forth turns for the Chat profile (20 entries total)
    private val persistentChatHistory = mutableListOf<HistoryEntry>()
    private val persistentTeachingHistory = mutableListOf<HistoryEntry>()
    private var teachingLessonTitle: String? = null

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
        val shouldResetTeachingSession = toolProfile == ToolProfile.Teaching &&
            (shouldPrepareTeachingBoard(goal) || VisualizerSceneStore.getSnapshot(DEFAULT_TEACHING_SCENE_ID) == null)
        if (toolProfile == ToolProfile.Chat) {
            state.history.addAll(persistentChatHistory)
        } else if (toolProfile == ToolProfile.Teaching) {
            if (shouldResetTeachingSession) {
                persistentTeachingHistory.clear()
            }
            state.history.addAll(persistentTeachingHistory)
        }
        state.history.add(HistoryEntry.UserMessage(goal))
        if (shouldResetTeachingSession) {
            val prepared = prepareTeachingBoard(goal)
            if (prepared.ok) {
                openTeachingBoardScene(DEFAULT_TEACHING_SCENE_ID)
            }
        }

        if (allowSkillLookup && !isReactiveProfile() && toolProfile != ToolProfile.Teaching) {
            when (val skillMatch = planner.findSkillMatch(goal)) {
                is SkillMatch.Cached -> {
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

                SkillMatch.None -> Unit
            }
        }

        val initialFacts = loadFactsSnapshot()
        var initialPlan: AgentPlan? = null
        var initialPlanRetries = 0
        var repairReason: String? = null

        if (isReactiveProfile()) {
            val directInstruction = if (toolProfile == ToolProfile.Teaching) {
                "Teach one factual step on scene_id=$DEFAULT_TEACHING_SCENE_ID. " +
                    "Topic: ${teachingLessonTitle ?: "current lesson"}. " +
                    "If the next idea is a process or sequence, use visualize_concept. Otherwise use board_teach_step. " +
                    "Content MUST be a real fact, definition, formula, or example — never a preview. " +
                    "Check steps_taught to avoid repeats. Emit EXACTLY ONE tool call."
            } else if (toolProfile == ToolProfile.AppControl) {
                "Execute the user's device control command step by step. " +
                    "Start with open_app, then read_screen to observe, then act. " +
                    "ONE tool call per turn. After task is done, call reply to confirm."
            } else if (toolProfile == ToolProfile.Chat || toolProfile == ToolProfile.Vision) {
                "" // System prompt is sufficient - no need to repeat instructions every turn
            } else {
                "Handle the user request reactively using the available tools. After any successful read_screen/read_screen_summary that answers the question, call reply. Do not call the same read tool twice in a row for the same request."
            }
            val directExpectedOutcome = if (toolProfile == ToolProfile.Teaching) {
                "Exactly one visible teaching-board update is made, then control returns to the user."
            } else {
                "The user's goal is fully achieved. Confirm success."
            }
            initialPlan = AgentPlan(
                intent = AgentIntent(summary = "Execute user request autonomously", entities = emptyMap()),
                steps = listOf(
                    PlanStep(
                        instruction = directInstruction,
                        expectedOutcome = directExpectedOutcome,
                        allowedTools = toolProfile?.toolNames?.toList()?:emptyList()
                    )
                )
            )
        } else {
            while (initialPlan == null && initialPlanRetries < 2) {
                initialPlan = generatePlan(
                    goal = goal,
                    facts = initialFacts,
                    previousState = null,
                    repairReason = repairReason,
                )
                if (initialPlan == null) {
                    repairReason = "Previous attempt returned malformed JSON or failed to parse. Please ensure valid JSON formatting matching the schema exactly."
                    initialPlanRetries++
                    Log.w(TAG, "Initial plan generation failed, retrying ($initialPlanRetries/2)...")
                }
            }
        }

        if (initialPlan == null) {
            val message = "Failed: plan generation error after retries"
            Log.w(TAG, "Planning failed; ending without fallback")
            onStatusUpdate("Planning failed. Ending without fallback.")
            return@withContext message
        }
        state.intent = initialPlan.intent
        state.plan = initialPlan
        state.currentStepIndex = 0
        state.history.add(HistoryEntry.PlanCreated(initialPlan))
        Log.d(TAG, "Generated plan with ${initialPlan.steps.size} steps: ${initialPlan.intent.summary}")

        var loopCount = 0
        val maxLoops = 50
        val pendingVisionInputs = mutableListOf<ByteArray>()
        var teachingBoardSuccessCount = 0
        var consecutiveUnknownToolCount = 0
        var lastUnknownToolName: String? = null
        var consecutiveFailureCount = 0

        while (loopCount < maxLoops) {
            loopCount++
            Log.d(TAG, "--- Loop iteration $loopCount ---")

            // Hard circuit breaker: if we accumulate too many consecutive failures,
            // stop the loop entirely to prevent hallucination cascades.
            if (consecutiveFailureCount >= 5) {
                val msg = "I ran into repeated errors and couldn't complete this action. Please try again."
                Log.e(TAG, "Circuit breaker: $consecutiveFailureCount consecutive failures, aborting loop")
                EventLogger.emit(
                    GenieEvent.ErrorOccurred(
                        ErrorTaxonomy.LogicErr("Consecutive failure circuit breaker triggered"),
                        "AgentOrchestrator.circuitBreaker",
                    )
                )
                onStatusUpdate(msg)
                if (toolProfile == ToolProfile.Teaching) rememberTeachingSessionHistory(state)
                return@withContext msg
            }

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

            val visionInputsForTurn = pendingVisionInputs.takeLast(1)
            pendingVisionInputs.clear()
            // Let the system breathe between turns
            kotlinx.coroutines.delay(500)

            val prompt = promptBuilder.buildStepPrompt(
                state,
                facts,
                visionInputsForTurn.isNotEmpty(),
                toolProfile,
                screenContext = null,
                focusedNode = null
            )

            EventLogger.emit(GenieEvent.StateTransition("building_prompt", "planning"))
            when (val planResult = planner.plan(prompt, visionInputsForTurn)) {
                is PlanResult.Success -> {
                    val decision = normalizeDecision(planResult.decision)
                    state.history.add(HistoryEntry.ModelDecision(decision))
                    if (state.pendingRepairReason != null) {
                        Log.d(TAG, "Clearing pending repair context")
                        state.pendingRepairReason = null
                    }

                    when (decision) {
                        is Decision.Act -> {
                            val humanReadable = getHumanReadableToolAction(decision.tool, decision.args)
                            onToolExecuting(humanReadable.first, humanReadable.second)

                            val outcome = executeToolWithSafety(
                                decision,
                                state,
                                serviceContext,
                                onStatusUpdate,
                                onToolExecuting,
                            )

                            when (outcome) {
                                is ToolOutcome.Ok -> {
                                    state.history.add(
                                        HistoryEntry.ToolResult(decision.tool, decision.args, outcome)
                                    )
                                    recordStepObservation(state, decision.tool, decision.args, outcome)

                                    // Document profile: orchestrator handled full flow, exit immediately
                                    if (toolProfile == ToolProfile.Document &&
                                        (decision.tool == "detect_open_pdf" || decision.tool == "list_device_pdfs")) {
                                        Log.d(TAG, "Document flow completed by orchestrator")
                                        EventLogger.emit(GenieEvent.StateTransition("executing", "finished"))
                                        return@withContext outcome.result
                                    }

                                    if (toolProfile == ToolProfile.Teaching && isTeachingBoardTool(decision.tool)) {
                                        openTeachingBoardScene(decision.args["scene_id"] ?: DEFAULT_TEACHING_SCENE_ID)
                                        teachingBoardSuccessCount++
                                        if (shouldPauseTeachingTurn(decision.tool, teachingBoardSuccessCount)) {
                                            val message = teachingPauseMessage(decision.tool)
                                            rememberTeachingSessionHistory(state)
                                            EventLogger.emit(GenieEvent.StateTransition("executing", "finished"))
                                            return@withContext message
                                        }
                                    }
                                    if (decision.tool == "take_screenshot" || decision.tool == "detect_open_pdf") {
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
                                                "${decision.tool} returned success but no PNG bytes were available"
                                            )
                                        }
                                    }
                                    slidingWindowManager.pruneAfterSuccess(state.history)
                                    state.retryCount = 0
                                    consecutiveFailureCount = 0
                                }

                                is ToolOutcome.TransientErr -> {
                                    consecutiveFailureCount++
                                    state.history.add(
                                        HistoryEntry.ToolResult(decision.tool, decision.args, outcome)
                                    )
                                    recordStepObservation(state, decision.tool, decision.args, outcome)
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
                                        if (isReactiveProfile()) {
                                            Log.w(TAG, "Max retries exceeded for ${decision.tool}; entering reactive repair")
                                            state.pendingRepairReason = "Current step failed after retries: ${outcome.message}"
                                            state.retryCount = 0
                                            state.replanCount = 0
                                        } else {
                                            Log.w(TAG, "Max retries exceeded for ${decision.tool}, requesting plan repair")
                                            val repairedPlan = generatePlan(
                                                goal = state.goal,
                                                facts = loadFactsSnapshot(),
                                                previousState = state,
                                                repairReason = "Current step failed after retries: ${outcome.message}",
                                            )
                                            if (repairedPlan == null) {
                                                val message = "Failed: plan generation error"
                                                Log.w(TAG, "Planning failed during retry repair; ending without fallback")
                                                onStatusUpdate("Planning failed. Ending without fallback.")
                                                return@withContext message
                                            }
                                            state.plan = repairedPlan
                                            state.intent = repairedPlan.intent
                                            state.currentStepIndex = 0
                                            state.retryCount = 0
                                            state.replanCount = 0
                                            state.history.add(HistoryEntry.PlanCreated(repairedPlan))
                                        }
                                    }
                                }

                                is ToolOutcome.LogicErr -> {
                                    consecutiveFailureCount++
                                    state.history.add(
                                        HistoryEntry.ToolResult(decision.tool, decision.args, outcome)
                                    )
                                    recordStepObservation(state, decision.tool, decision.args, outcome)

                                    // Circuit breaker: if the same tool keeps failing with "Unknown tool",
                                    // abort immediately rather than looping indefinitely.
                                    if (outcome.message.contains("Unknown tool")) {
                                        if (decision.tool == lastUnknownToolName) {
                                            consecutiveUnknownToolCount++
                                        } else {
                                            lastUnknownToolName = decision.tool
                                            consecutiveUnknownToolCount = 1
                                        }
                                        if (consecutiveUnknownToolCount >= 3) {
                                            val errorMsg = "Tool '${decision.tool}' is not registered and cannot be used. Stopping."
                                            Log.e(TAG, "Circuit breaker triggered: $errorMsg (failed $consecutiveUnknownToolCount times)")
                                            EventLogger.emit(
                                                GenieEvent.ErrorOccurred(
                                                    ErrorTaxonomy.LogicErr(errorMsg),
                                                    "AgentOrchestrator.circuitBreaker",
                                                )
                                            )
                                            onStatusUpdate("I can't complete this action because a required tool is unavailable.")
                                            return@withContext errorMsg
                                        }
                                    } else {
                                        // Reset counter for non-unknown-tool errors
                                        consecutiveUnknownToolCount = 0
                                        lastUnknownToolName = null
                                    }

                                    if (state.replanCount < state.maxReplans) {
                                        Log.d(
                                            TAG,
                                            "LogicErr - replanning (attempt ${state.replanCount + 1}/${state.maxReplans})"
                                        )
                                        state.replanCount++
                                    } else {
                                        if (isReactiveProfile()) {
                                            Log.w(TAG, "Max replans exceeded for ${decision.tool}; entering reactive repair")
                                            state.pendingRepairReason = "Current step hit repeated logic errors: ${outcome.message}"
                                            state.retryCount = 0
                                            state.replanCount = 0
                                        } else {
                                            Log.w(TAG, "Max replans exceeded for ${decision.tool}, requesting plan repair")
                                            val repairedPlan = generatePlan(
                                                goal = state.goal,
                                                facts = loadFactsSnapshot(),
                                                previousState = state,
                                                repairReason = "Current step hit repeated logic errors: ${outcome.message}",
                                            )
                                            if (repairedPlan == null) {
                                                val message = "Failed: plan generation error"
                                                Log.w(TAG, "Planning failed during logic repair; ending without fallback")
                                                onStatusUpdate("Planning failed. Ending without fallback.")
                                                return@withContext message
                                            }
                                            state.plan = repairedPlan
                                            state.intent = repairedPlan.intent
                                            state.currentStepIndex = 0
                                            state.retryCount = 0
                                            state.replanCount = 0
                                            state.history.add(HistoryEntry.PlanCreated(repairedPlan))
                                        }
                                    }
                                }

                                is ToolOutcome.AuthErr -> {
                                    state.history.add(
                                        HistoryEntry.ToolResult(decision.tool, decision.args, outcome)
                                    )
                                    recordStepObservation(state, decision.tool, decision.args, outcome)
                                    onStatusUpdate("Action requires your authorization, which was denied.")
                                    return@withContext "Stopped: user denied authorization for ${decision.tool}"
                                }

                                is ToolOutcome.FatalErr -> {
                                    recordStepObservation(state, decision.tool, decision.args, outcome)
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

                            // Reactive profiles: tasks() signals immediate goal completion
                            if (isReactiveProfile()) {
                                Log.d(TAG, "=== Goal Complete (Reactive): ${decision.summary} ===")
                                EventLogger.emit(GenieEvent.StateTransition("executing", "finished"))

                                if (state.isNovelPlan) {
                                    writeSkill(state)
                                }

                                onStatusUpdate(decision.summary)
                                return@withContext decision.summary
                            }

                            if (activePlan == null) {
                                Log.d(TAG, "=== Goal Complete: ${decision.summary} ===")
                                EventLogger.emit(GenieEvent.StateTransition("executing", "finished"))

                                if (state.isNovelPlan) {
                                    writeSkill(state)
                                }

                                onStatusUpdate(decision.summary)
                                return@withContext decision.summary
                            }

                            when (val finishError = stepFinishPreconditionError(state, decision.summary)) {
                                null -> {
                                    val completedIndex = state.currentStepIndex
                                    state.history.add(HistoryEntry.StepCompleted(completedIndex, decision.summary))
                                    state.stepObservations.add(
                                        StepObservation(
                                            stepIndex = completedIndex,
                                            toolName = "tasks",
                                            outcome = ToolOutcome.Ok(decision.summary),
                                        )
                                    )
                                    state.currentStepIndex++
                                    state.retryCount = 0
                                    state.replanCount = 0
                                    consecutiveFailureCount = 0
                                    Log.d(TAG, "Plan step ${completedIndex + 1}/${activePlan.steps.size} complete: ${decision.summary}")
                                }

                                else -> {
                                    val outcome = ToolOutcome.LogicErr(finishError)
                                    state.history.add(
                                        HistoryEntry.ToolResult(
                                            "tasks",
                                            mapOf("plan" to decision.summary),
                                            outcome,
                                        )
                                    )
                                    recordStepObservation(state, "tasks", mapOf("plan" to decision.summary), outcome)
                                    if (state.replanCount < state.maxReplans) {
                                        Log.d(
                                            TAG,
                                            "Finish precondition failed - replanning (attempt ${state.replanCount + 1}/${state.maxReplans})"
                                        )
                                        state.replanCount++
                                    } else {
                                        if (isReactiveProfile()) {
                                            Log.w(TAG, "Finish precondition failed repeatedly; entering reactive repair")
                                            state.pendingRepairReason = finishError
                                            state.retryCount = 0
                                            state.replanCount = 0
                                        } else {
                                            Log.w(TAG, "Finish precondition failed repeatedly; requesting plan repair")
                                            val repairedPlan = generatePlan(
                                                goal = state.goal,
                                                facts = loadFactsSnapshot(),
                                                previousState = state,
                                                repairReason = finishError,
                                            )
                                            state.plan = repairedPlan
                                            state.intent = repairedPlan?.intent
                                            state.currentStepIndex = 0
                                            state.retryCount = 0
                                            state.replanCount = 0
                                            state.history.add(HistoryEntry.PlanCreated(repairedPlan!!))
                                        }
                                    }
                                }
                            }
                        }

                        is Decision.Reply -> {
                            Log.d(TAG, "=== Goal Complete (Replied): ${decision.message} ===")
                            EventLogger.emit(GenieEvent.StateTransition("executing", "replied"))
                            
                            if (state.isNovelPlan) {
                                writeSkill(state)
                            }
                            
                            if (toolProfile == ToolProfile.Chat) {
                                persistentChatHistory.add(HistoryEntry.UserMessage(goal))
                                persistentChatHistory.add(HistoryEntry.ModelDecision(decision))
                                while (persistentChatHistory.size > 20) {
                                    persistentChatHistory.removeAt(0)
                                }
                            }
                            
                            onStatusUpdate(decision.message)
                            return@withContext decision.message
                        }
                    }
                }

                is PlanResult.ParseError -> {
                    consecutiveFailureCount++
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
                        if (isReactiveProfile()) {
                            Log.w(TAG, "Planner parse errors exceeded threshold; entering reactive repair")
                            state.pendingRepairReason = "Planner repeatedly returned invalid tool calls: $correction"
                            state.replanCount = 0
                        } else {
                            Log.w(TAG, "Planner parse errors exceeded repair threshold; requesting revised plan")
                            val repairedPlan = generatePlan(
                                goal = state.goal,
                                facts = loadFactsSnapshot(),
                                previousState = state,
                                repairReason = "Planner repeatedly returned invalid tool calls: $correction",
                            )
                            if (repairedPlan == null) {
                                val message = "Failed: plan generation error"
                                Log.w(TAG, "Planning failed during repair; ending without fallback")
                                onStatusUpdate("Planning failed. Ending without fallback.")
                                return@withContext message
                            }
                            state.plan = repairedPlan
                            state.intent = repairedPlan.intent
                            state.currentStepIndex = 0
                            state.retryCount = 0
                            state.replanCount = 0
                            state.history.add(HistoryEntry.PlanCreated(repairedPlan))
                        }
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
    ): AgentPlan? {
        val prompt = if (previousState == null) {
            val basePrompt = promptBuilder.buildPlanPrompt(
                goal = goal,
                injectedFacts = facts,
                availableTools = validToolNames(includeFinish = true),
                toolProfile = toolProfile,
            )
            if (repairReason != null) {
                "$basePrompt\n\n## Repair Context\nReason: $repairReason\nCreate a revised plan ensuring perfectly valid JSON."
            } else {
                basePrompt
            }
        } else {
            buildRepairPlanPrompt(goal, facts, previousState, repairReason)
        }

        return when (val result = planner.plan(prompt)) {
            is PlanResult.Success -> {
                val finish = result.decision as? Decision.Finish
                if (finish == null) {
                    Log.w(TAG, "Plan generation returned action instead of plan: ${result.decision}")
                    null
                } else {
                    parseAgentPlan(finish.summary)
                }
            }

            is PlanResult.ParseError -> {
                val plainTextPlan = result.rawText?.let { parseAgentPlan(it) }
                if (plainTextPlan != null) {
                    Log.d(TAG, "Parsed generated plan from plain text JSON")
                    plainTextPlan
                } else {
                    Log.w(TAG, "Plan generation parse error: ${result.message}")
                    null
                }
            }

            is PlanResult.Error -> {
                Log.w(TAG, "Plan generation error: ${result.message}")
                null
            }
        }
    }

    private fun buildRepairPlanPrompt(
        goal: String,
        facts: List<String>,
        state: AgentState,
        repairReason: String?,
    ): String {
        val sb = StringBuilder()
        sb.append(promptBuilder.buildPlanPrompt(goal, facts, validToolNames(includeFinish = true), toolProfile))
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
        args: Map<String, String>,
        outcome: ToolOutcome,
    ) {
        if (outcome !is ToolOutcome.Ok) return

        val plan = state.plan ?: return
        val step = plan.steps.getOrNull(state.currentStepIndex) ?: return

        // Reactive profiles don't use step-based completion — they loop until explicit reply/tasks
        if (isReactiveProfile()) return

        val stepText = "${step.instruction} ${step.expectedOutcome}".lowercase()
        val resultText = outcome.result.lowercase()

        val shouldComplete = when (toolName) {
            "open_app" -> {
                val appName = args["name"]?.lowercase().orEmpty()
                appName.isNotBlank() &&
                    (stepText.contains("open") || stepText.contains("launch")) &&
                    (stepText.contains(appName) || resultText.contains(appName))
            }

            "click", "activate_focused", "tap_at" -> {
                val target = args["target"]?.lowercase().orEmpty()
                val opensOrSelects = listOf("open", "select", "choose", "find", "enter")
                    .any { stepText.contains(it) }
                val sendsMessage = stepText.contains("send") &&
                    (target.isLikelySendTarget() || toolName == "tap_at" || toolName == "activate_focused")
                sendsMessage ||
                    opensOrSelects && (
                        target.isBlank() ||
                            stepText.contains(target) ||
                            resultText.contains(target) ||
                            toolName == "tap_at" ||
                            toolName == "activate_focused"
                        )
            }

            "type_text" -> {
                val messageText = args["text"]?.lowercase().orEmpty()
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

            "take_screenshot" -> {
                resultText.contains("screenshot captured") &&
                    "take_screenshot" in step.allowedTools &&
                    "tap_at" !in step.allowedTools &&
                    (stepText.contains("screenshot") || stepText.contains("screen") || stepText.contains("visual"))
            }

            "save_fact" -> resultText.contains("saved fact")
            "retrieve_fact" -> resultText.contains("=") || resultText.contains("no fact found")
            "right", "left" -> {
                val wantsJames = stepText.contains("james")
                val wantsEditable = stepText.contains("editable") ||
                    stepText.contains("text field") ||
                    stepText.contains("input") ||
                    stepText.contains("message input")
                val wantsSend = stepText.contains("send")

                when {
                    wantsJames -> resultText.contains("james")
                    wantsEditable -> resultText.containsAny(
                        "edittext",
                        "text field",
                        "input",
                        "message",
                        "type a message",
                        "compose",
                    )
                    wantsSend -> resultText.contains("send")
                    else -> false
                }
            }
            else -> false
        }

        if (!shouldComplete) return

        val completedIndex = state.currentStepIndex
        val summary = "Auto-completed after $toolName: ${step.instruction}"
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
                is Decision.Reply -> "replied: ${decision.message}"
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
        onToolExecuting: (String, String?) -> Unit = { _, _ -> },
    ): ToolOutcome {
        if (decision.tool !in activeToolNames) {
            return ToolOutcome.LogicErr(buildUnknownToolCorrection(decision.tool))
        }

        currentAllowedToolError(decision, state)?.let { return ToolOutcome.LogicErr(it) }

        // Document profile: intercept detect_open_pdf and list_device_pdfs
        if (toolProfile == ToolProfile.Document) {
            when (decision.tool) {
                "detect_open_pdf" -> {
                    return handleDocumentFlowForScreenPdf(state.goal, serviceContext, onStatusUpdate, onToolExecuting)
                }
                "list_device_pdfs" -> {
                    return handleDocumentFlowForListPdfs(state.goal, onStatusUpdate, onToolExecuting)
                }
            }
        }

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

    private fun currentAllowedToolError(
        decision: Decision.Act,
        state: AgentState,
    ): String? {
        val allowedTools = state.plan
            ?.steps
            ?.getOrNull(state.currentStepIndex)
            ?.allowedTools
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        if (decision.tool in allowedTools) return null

        return buildString {
            append("Tool '${decision.tool}' is not available in the current context. ")
            append("Allowed now: ${allowedTools.joinToString(", ")}. ")
            if (decision.tool == "type_text") {
                append("Do not call type_text until the focused-node observation says Available now includes type_text.")
            } else {
                append("Choose one allowed tool and continue from the focused-node observation.")
            }
        }
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
        val alias = when (toolName) {
            "move_right" -> "right"
            "move_left" -> "left"
            else -> null
        }
        if (alias != null && alias in validTools) return alias

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

    private fun stepFinishPreconditionError(state: AgentState, finishSummary: String): String? {
        // Visual verification is brittle on Android; allow the planner to claim step completion.
        if (toolProfile == ToolProfile.SeeAndTap) return null

        if (
            requiresTapActivation(state) &&
            !recentSuccessfulActivationAction(state) &&
            !finishSummaryIndicatesBlockingReason(finishSummary)
        ) {
            return "Tap finish precondition failed: the goal cannot finish until a tap/click/activation action succeeds after visual inspection."
        }

        return null
    }

    private fun requiresTapActivation(state: AgentState): Boolean {
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
            "tap ",
            "tap on",
            "click ",
            "click on",
            "press ",
            "press on",
        )
    }

    private fun recentSuccessfulActivationAction(state: AgentState): Boolean {
        return state.history
            .asReversed()
            .take(12)
            .filterIsInstance<HistoryEntry.ToolResult>()
            .any {
                it.outcome is ToolOutcome.Ok &&
                    it.toolName in setOf("tap_at", "click", "activate_focused", "open_app")
            }
    }

    private fun finishSummaryIndicatesBlockingReason(summary: String): Boolean {
        val normalized = summary.lowercase()
        return normalized.containsAny(
            "blocked",
            "cannot",
            "can't",
            "could not",
            "couldn't",
            "not found",
            "not visible",
            "unable",
        )
    }

    private fun isReactiveProfile(): Boolean {
        return toolProfile == ToolProfile.Chat ||
            toolProfile == ToolProfile.Vision ||
            toolProfile == ToolProfile.Reader ||
            toolProfile == ToolProfile.Teaching ||
            toolProfile == ToolProfile.Document ||
            toolProfile == ToolProfile.Scribe ||
            toolProfile == ToolProfile.AppControl
    }

    private fun formStateHasFocusedInput(result: String): Boolean {
        val normalized = result.lowercase()
        if (normalized.contains("no form fields")) return false
        return normalized
            .lineSequence()
            .any { line -> line.contains("focused") && !line.contains("disabled") }
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
        return if (includeFinish) activeToolNames + "tasks" else activeToolNames
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

            toolName == "list_device_pdfs" -> {
                listDevicePdfs()
            }

            toolName == "detect_open_pdf" -> {
                detectOpenPdf(serviceContext)
            }

            toolName == "visualize_concept" -> {
                handleVisualizeConcept(args)
            }

            toolName == "visualize_focus_node" -> {
                handleVisualizeFocusNode(args)
            }

            toolName == "board_teach_step" ||
                toolName == "teach_with_board" ||
                toolName == "board_clear" ||
                toolName == "board_add_object" ||
                toolName == "board_next_step" ||
                toolName == "board_prev_step" ||
                toolName == "board_replay_step" ||
                toolName == "board_set_narration" -> {
                handleTeachingBoardTool(toolName, args)
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
                "Invalid 'operation' '${normalized.operation}'. Use: create, update, highlight, clear, or export"
            )
        }

        return if (result.ok) ToolOutcome.Ok(result.message) else ToolOutcome.LogicErr(result.message)
    }

    private fun shouldPrepareTeachingBoard(goal: String): Boolean {
        val text = goal.lowercase()
        val controlWords = listOf(
            "next",
            "proceed",
            "continue",
            "go on",
            "back",
            "previous",
            "repeat",
            "replay",
            "focus",
            "remove",
            "delete",
            "update",
            "change",
            "clear",
        )
        if (controlWords.any { it in text }) return false

        val teachingWords = listOf(
            "teach",
            "learn",
            "explain",
            "show",
            "diagram",
            "visualize",
            "summarize",
            "compare",
            "what is",
            "how does",
            "how do",
        )
        return teachingWords.any { it in text }
    }

    private fun prepareTeachingBoard(goal: String): SceneStoreResult {
        val title = lessonTitleFromGoal(goal)
        teachingLessonTitle = title
        VisualizerSceneStore.clearScene(DEFAULT_TEACHING_SCENE_ID)

        val objects = listOf(
            BoardObject(
                objectId = "board_shell_title",
                objectType = "title",
                text = title,
                position = BoardPoint(48f, 28f),
                size = BoardSize(640f, 64f),
                style = BoardStyle(textAlign = "left", textSize = 30f),
            ),
            BoardObject(
                objectId = "board_shell_prompt",
                objectType = "card",
                text = "Ready to teach\n$title",
                position = BoardPoint(72f, 124f),
                size = BoardSize(620f, 124f),
                style = BoardStyle(textAlign = "left", textSize = 22f, cornerRadius = 16f),
                stepId = "step_1",
                animation = "reveal",
            ),
        )
        val steps = listOf(
            BoardStep("step_1", "Set up", ""),
            BoardStep("step_2", "Build", ""),
            BoardStep("step_3", "Apply", ""),
            BoardStep("step_4", "Recap", ""),
        )

        val result = VisualizerSceneStore.teachWithBoard(
            sceneId = DEFAULT_TEACHING_SCENE_ID,
            title = title,
            theme = "dark_classroom",
            objectsJson = json.encodeToString(objects),
            stepsJson = json.encodeToString(steps),
            narrationText = "",
        )
        Log.d(TAG, "Prepared default teaching board: ok=${result.ok}, msg=${result.message.take(160)}")
        return result
    }

    private suspend fun openTeachingBoardScene(sceneId: String) {
        withContext(Dispatchers.Main) {
            runCatching {
                val intent = Intent(appContext, VisualizerCanvasActivity::class.java).apply {
                    putExtra(VisualizerCanvasActivity.EXTRA_SCENE_ID, sceneId)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                appContext.startActivity(intent)
            }.onFailure { e ->
                Log.w(TAG, "Failed to open teaching board '$sceneId': ${e.message}")
            }
        }
    }

    private fun isTeachingBoardTool(toolName: String): Boolean {
        return toolName == "board_teach_step" ||
            toolName == "board_clear" ||
            toolName == "board_add_object" ||
            toolName == "board_next_step" ||
            toolName == "board_prev_step" ||
            toolName == "board_replay_step" ||
            toolName == "board_set_narration" ||
            toolName == "visualize_concept"
    }

    private fun teachingPauseMessage(toolName: String): String {
        return when (toolName) {
            "board_next_step" -> "Next step is ready. Press next when you want to continue."
            "board_prev_step" -> "Moved back one step. Press next when you are ready."
            "board_replay_step" -> "Replayed this step. Press next when you are ready."
            "board_clear" -> "Board cleared."
            "visualize_concept" -> "Diagram created. Press next when you are ready."
            else -> "Teaching board updated. Press next when you are ready."
        }
    }

    private fun shouldPauseTeachingTurn(toolName: String, successCount: Int): Boolean {
        if (toolName == "board_teach_step") return true
        if (toolName == "visualize_concept") return true
        if (toolName == "board_next_step" || toolName == "board_prev_step" || toolName == "board_replay_step") return true
        return successCount >= 2
    }

    private fun rememberTeachingSessionHistory(state: AgentState) {
        persistentTeachingHistory.clear()
        // Keep minimal history — KV cache holds the full conversation,
        // we only need the last user message + result for error recovery.
        persistentTeachingHistory.addAll(state.history.takeLast(4))
    }

    private fun lessonTitleFromGoal(goal: String): String {
        val cleaned = goal
            .replace(Regex("(?i)\\b(teach me|teach|learn|explain|show me|show|about|step by step)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(80)
        return if (cleaned.isBlank()) "Teaching Session" else cleaned.replaceFirstChar { it.titlecase() }
    }

    private fun handleVisualizeFocusNode(args: Map<String, String>): ToolOutcome {
        val sceneId = args["scene_id"]?.trim()
            ?: return ToolOutcome.LogicErr("Missing 'scene_id' argument")
        val nodeId = args["node_id"]?.trim()
            ?: return ToolOutcome.LogicErr("Missing 'node_id' argument")
        if (!SCENE_ID_PATTERN.matches(sceneId)) {
            return ToolOutcome.LogicErr("Invalid 'scene_id'. Use 1-64 chars: letters, numbers, underscore, hyphen")
        }
        if (!OBJECT_ID_PATTERN.matches(nodeId)) {
            return ToolOutcome.LogicErr("Invalid 'node_id'. Use 1-64 chars: letters, numbers, underscore, hyphen")
        }

        val result = VisualizerSceneStore.highlightScene(sceneId = sceneId, focusCsv = nodeId)
        return if (result.ok) ToolOutcome.Ok(result.message) else ToolOutcome.LogicErr(result.message)
    }

    /**
     * Semantic teaching tool handler — the app owns all layout math.
     *
     * Layout contract (all values in canvas pixels):
     *   - Board visible area: x 48..752, y 100..580
     *   - Shell title bar occupies y 28..92 (objectId prefix "board_shell_")
     *   - Each lesson card slot: x=48, y = 100 + slotIndex * 140, width=656, height=120
     *   - Max cards before the board is considered full: 3  (step 0-2 fit cleanly)
     *   - When full, we recycle slot 0 (oldest) and shift others up, keeping 3 visible at once
     */
    private fun handleBoardTeachStep(sceneId: String, args: Map<String, String>): SceneStoreResult {
        val stepLabel = args["step_label"]?.trim()?.take(60)
            ?: return SceneStoreResult(false, "Missing 'step_label' argument for board_teach_step")
        val narration = args["narration"]?.trim()?.take(1000)
            ?: return SceneStoreResult(false, "Missing 'narration' argument for board_teach_step")

        if (stepLabel.isBlank()) {
            return SceneStoreResult(false, "board_teach_step: 'step_label' must not be blank")
        }
        if (narration.isBlank()) {
            return SceneStoreResult(false, "board_teach_step: 'narration' must not be blank")
        }

        // Find next unique content ID by looking at max existing index
        val snapshot = VisualizerSceneStore.getSnapshot(sceneId)
        val board = snapshot?.scene?.board
        val existingContentIds = board?.objects
            ?.filter { it.objectId.startsWith("content_") }
            ?.map { it.objectId }
            ?: emptyList()

        val maxExistingIndex = existingContentIds
            .mapNotNull { it.removePrefix("content_").toIntOrNull() }
            .maxOrNull() ?: -1
        val slotIndex = maxExistingIndex + 1
        val objectId = "content_$slotIndex"

        // Fixed-grid layout — no model math required
        val cardX = 48f
        val cardY = 100f + slotIndex * 140f
        val cardWidth = 656f
        val cardHeight = 120f

        // If the slot would overflow the visible area, clear old cards first (rolling window)
        if (cardY + cardHeight > 580f && existingContentIds.isNotEmpty()) {
            // Remove the oldest card to make room
            val oldestId = existingContentIds.first()
            VisualizerSceneStore.boardRemoveObject(sceneId, oldestId)
            Log.d(TAG, "board_teach_step: removed oldest card '$oldestId' to make room")
        }

        // Recalculate slot after potential removal — use max index + 1 to avoid collisions
        val snapshot2 = VisualizerSceneStore.getSnapshot(sceneId)
        val board2 = snapshot2?.scene?.board
        val remainingContentIds = board2?.objects
            ?.filter { it.objectId.startsWith("content_") }
            ?.map { it.objectId }
            ?: emptyList()
        val maxIndexAfterPrune = remainingContentIds
            .mapNotNull { it.removePrefix("content_").toIntOrNull() }
            .maxOrNull() ?: -1
        val finalObjectId = "content_${maxIndexAfterPrune + 1}"
        val displaySlot = remainingContentIds.size
        val finalY = 100f + displaySlot * 140f

        Log.d(TAG, "board_teach_step: placing card '$finalObjectId' at y=$finalY label='$stepLabel'")

        val addResult = VisualizerSceneStore.boardAddObject(
            sceneId = sceneId,
            objectId = finalObjectId,
            objectType = "card",
            text = stepLabel,
            x = cardX,
            y = finalY,
            width = cardWidth,
            height = cardHeight,
            styleJson = null,
            stepId = null,
            animation = "reveal",
            pathData = null,
        )
        if (!addResult.ok) {
            Log.e(TAG, "board_teach_step: boardAddObject failed: ${addResult.message}")
            return addResult
        }

        // Get updated board state to append the new step
        val snapshot3 = VisualizerSceneStore.getSnapshot(sceneId)
        val board3 = snapshot3?.scene?.board ?: return SceneStoreResult(false, "Failed to retrieve board after adding object")

        // Create new step matching the card we just added
        val newStep = BoardStep(
            stepId = finalObjectId,
            title = stepLabel,
            narration = narration
        )

        // Clear placeholder steps if this is first real content
        val placeholderIds = setOf("step_1", "step_2", "step_3", "step_4")
        val isFirstContent = board3.steps.all { it.stepId in placeholderIds }
        val updatedSteps = if (isFirstContent) {
            listOf(newStep)
        } else {
            board3.steps + newStep
        }

        // If we cleared placeholders, null out stepId on objects that referenced them
        val updatedObjects = if (isFirstContent) {
            board3.objects.map { obj ->
                if (obj.stepId != null && obj.stepId in placeholderIds) obj.copy(stepId = null) else obj
            }
        } else {
            board3.objects
        }

        // Update board with new step and set as current
        val updatedBoard = board3.copy(
            objects = updatedObjects,
            steps = updatedSteps,
            currentStepId = finalObjectId,
            narrationText = narration
        )

        // Save updated board
        VisualizerSceneStore.saveScene(snapshot3.scene.copy(
            board = updatedBoard,
            updatedAt = System.currentTimeMillis()
        ))

        Log.d(TAG, "board_teach_step: done — card='$finalObjectId', step added to board")
        return SceneStoreResult(
            true,
            "Taught step '$stepLabel' on board '$sceneId' (card=$finalObjectId, y=${finalY.toInt()})"
        )
    }

    private fun handleTeachingBoardTool(
        toolName: String,
        args: Map<String, String>,
    ): ToolOutcome {
        Log.d(TAG, "━━━ handleTeachingBoardTool ENTER ━━━")
        Log.d(TAG, "  tool: $toolName")
        args.forEach { (key, value) ->
            Log.d(TAG, "  arg[$key]: ${value.take(300)}")
        }

        val sceneId = args["scene_id"]?.trim()
        if (sceneId.isNullOrBlank()) {
            Log.e(TAG, "  FAIL: Missing 'scene_id'. Raw value: '${args["scene_id"]}'")
            return ToolOutcome.LogicErr("Missing 'scene_id' argument")
        }
        if (!SCENE_ID_PATTERN.matches(sceneId)) {
            Log.e(TAG, "  FAIL: Invalid 'scene_id' pattern. Raw: '$sceneId'")
            return ToolOutcome.LogicErr("Invalid 'scene_id'. Use 1-64 chars: letters, numbers, underscore, hyphen")
        }

        val result = when (toolName) {
            "board_teach_step" -> handleBoardTeachStep(sceneId, args)
            "board_clear" -> VisualizerSceneStore.clearScene(sceneId)

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
                val x = args["x"]?.toFloatOrNull()?.coerceIn(40f, 760f)
                    ?: return ToolOutcome.LogicErr("Missing or invalid 'x' argument")
                val y = args["y"]?.toFloatOrNull()?.coerceIn(40f, 560f)
                    ?: return ToolOutcome.LogicErr("Missing or invalid 'y' argument")
                val width = args["width"]?.trim()?.takeIf { it.isNotEmpty() }?.toFloatOrNull()
                    ?: if (args["width"].isNullOrBlank()) null else return ToolOutcome.LogicErr("Invalid 'width' argument")
                val height = args["height"]?.trim()?.takeIf { it.isNotEmpty() }?.toFloatOrNull()
                    ?: if (args["height"].isNullOrBlank()) null else return ToolOutcome.LogicErr("Invalid 'height' argument")
                val rawStyleJson = args["style"]?.takeIf { it.isNotBlank() }?.take(4_000)
                Log.d(TAG, "  board_add_object parsed: id=$objectId type=$objectType x=$x y=$y w=$width h=$height")
                Log.d(TAG, "  board_add_object style JSON: '$rawStyleJson'")
                Log.d(TAG, "  board_add_object stepId=${args["step_id"]} animation=${args["animation"]}")
                VisualizerSceneStore.boardAddObject(
                    sceneId = sceneId,
                    objectId = objectId,
                    objectType = objectType,
                    text = args["text"]?.take(240),
                    x = x,
                    y = y,
                    width = width,
                    height = height,
                    styleJson = rawStyleJson,
                    stepId = args["step_id"]?.trim()?.takeIf { it.isNotEmpty() },
                    animation = args["animation"]?.trim()?.takeIf { it.isNotEmpty() }?.take(40),
                    pathData = args["path_data"]?.takeIf { it.isNotBlank() }?.take(2_000),
                )
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

        Log.d(TAG, "  result: ok=${result.ok}, msg=${result.message.take(200)}")
        Log.d(TAG, "━━━ handleTeachingBoardTool EXIT ━━━")
        return if (result.ok) ToolOutcome.Ok(result.message) else ToolOutcome.LogicErr(result.message)
    }

    private fun parseOptionalFloatArg(raw: String?): Float? {
        if (raw.isNullOrBlank()) return null
        return raw.trim().toFloatOrNull()
    }


    private fun normalizeVisualizeArgs(args: Map<String, String>): NormalizedVisualizeArgs? {
        val rawOp = args["operation"]?.trim()?.lowercase() ?: return null
        val operation = when {
            rawOp.startsWith("create") -> "create_scene"
            rawOp.startsWith("update") -> "update_scene"
            rawOp.startsWith("highlight") || rawOp == "focus" -> "highlight"
            rawOp.startsWith("clear") || rawOp.startsWith("delete") -> "clear_scene"
            rawOp.startsWith("export") -> "export_scene"
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

    private suspend fun listDevicePdfs(): ToolOutcome = withContext(Dispatchers.IO) {
        val dirs = listOf(
            "/storage/emulated/0/Download",
            "/storage/emulated/0/Documents",
            "/storage/emulated/0/DCIM",
        )
        val pdfs = mutableListOf<File>()
        dirs.forEach { dir ->
            File(dir).listFiles()?.filter { it.extension.equals("pdf", true) }?.let { pdfs.addAll(it) }
        }
        if (pdfs.isEmpty()) {
            return@withContext ToolOutcome.Ok("No PDF files found on device.")
        }
        val sb = StringBuilder("Found ${pdfs.size} PDF(s):\n")
        pdfs.take(30).forEachIndexed { i, f ->
            sb.appendLine("${i + 1}. ${f.name} (${f.length() / 1024}KB) — ${f.absolutePath}")
        }
        ToolOutcome.Ok(sb.toString().trim())
    }

    private suspend fun detectOpenPdf(serviceContext: ToolServiceContext): ToolOutcome {
        val screenshotResult = serviceContext.takeScreenshot()
        if (screenshotResult.startsWith("Error") || screenshotResult.startsWith("Failed")) {
            return ToolOutcome.TransientErr("Screenshot failed — check permissions")
        }
        return ToolOutcome.Ok(
            "Screenshot captured. The image of the current PDF page is attached to your next turn. " +
                "Extract and summarize all visible text from it."
        )
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

            val outcome = executeToolWithSafety(step, state, serviceContext, onStatusUpdate, onToolExecuting)
            state.history.add(HistoryEntry.ToolResult(step.tool, step.args, outcome))

            when (outcome) {
                is ToolOutcome.Ok -> Unit

                is ToolOutcome.TransientErr, is ToolOutcome.LogicErr -> {
                    Log.w(TAG, "Cached plan step failed: ${step.tool}, ending without fallback")
                    onStatusUpdate("Cached plan failed. Ending without fallback.")
                    return "Failed: cached plan step failed"
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
            val goalPattern = state.goal.lowercase().take(100)
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
            "tasks" -> "Completing step…" to args["plan"]
            else -> {
                if (toolName.startsWith("board_") || toolName == "teach_with_board") "Teaching concept…" to null
                else if (toolName.startsWith("visualize_")) "Visualizing concept…" to null
                else "Executing action…" to toolName
            }
        }
    }

    // ─── Document Profile Flow ──────────────────────────────────────────────────

    /**
     * Switch engine to freeform text mode (no tools, no constrained decoding).
     * Must call restoreAgentMode() after to resume normal agent operation.
     */
    private fun switchToFreeformMode(systemPrompt: String) {
        engine.resetConversation(
            systemPrompt = systemPrompt,
            tools = emptyList(),
            constrainedDecoding = false,
        )
        Log.d(TAG, "Document flow: switched to freeform mode")
    }

    /**
     * Restore engine to normal agent mode with tools and constrained decoding.
     */
    private fun restoreAgentMode() {
        val profile = toolProfile ?: ToolProfile.DEFAULT
        engine.resetConversation(
            systemPrompt = PromptBuilder.systemPromptForProfile(profile),
            tools = geniePlannerToolProviders(profile),
            constrainedDecoding = true,
        )
        Log.d(TAG, "Document flow: restored agent mode")
    }

    /**
     * Handles document flow when agent calls detect_open_pdf.
     * Orchestrator: screenshot → extract text → quiz/summary based on goal.
     */
    private suspend fun handleDocumentFlowForScreenPdf(
        goal: String,
        serviceContext: ToolServiceContext,
        onStatusUpdate: (String) -> Unit,
        onToolExecuting: (String, String?) -> Unit,
    ): ToolOutcome {
        val intent = detectDocumentIntent(goal)
        Log.d(TAG, "Document flow (screen): intent=$intent for goal: $goal")

        onStatusUpdate("Capturing screen…")
        val screenshotResult = serviceContext.takeScreenshot()
        if (screenshotResult.startsWith("Error") || screenshotResult.startsWith("Failed")) {
            return ToolOutcome.TransientErr("Screenshot failed: $screenshotResult")
        }
        val pngBytes = serviceContext.consumeLatestScreenshotPngBytes()
            ?: return ToolOutcome.TransientErr("No screenshot data available")

        onToolExecuting("Extracting text", "Reading screen content…")
        val extractedText = extractTextFromImage(pngBytes)
        if (extractedText.isNullOrBlank() || extractedText.length < 20) {
            restoreAgentMode()
            return ToolOutcome.LogicErr("Could not extract meaningful text from the screen")
        }
        Log.d(TAG, "Document flow: extracted ${extractedText.length} chars from screen")

        val result = when (intent) {
            DocumentIntent.QUIZ -> generateAndLaunchQuiz(extractedText, goal, onToolExecuting)
            DocumentIntent.SUMMARY -> generateSummary(extractedText, onToolExecuting)
        }
        restoreAgentMode()
        return result
    }

    /**
     * Handles document flow when agent calls list_device_pdfs.
     * Orchestrator: list PDFs → model picks path → extract internally → quiz/summary.
     */
    private suspend fun handleDocumentFlowForListPdfs(
        goal: String,
        onStatusUpdate: (String) -> Unit,
        onToolExecuting: (String, String?) -> Unit,
    ): ToolOutcome {
        val intent = detectDocumentIntent(goal)
        Log.d(TAG, "Document flow (named PDF): intent=$intent for goal: $goal")

        onStatusUpdate("Listing PDFs on device…")
        val listResult = listDevicePdfs()
        if (listResult !is ToolOutcome.Ok || listResult.result.contains("No PDF files found")) {
            return ToolOutcome.LogicErr("No PDF files found on device")
        }

        onStatusUpdate("Finding the right PDF…")
        switchToFreeformMode("You help pick the correct PDF from a list. Reply with ONLY the full file path, nothing else.")
        val pickedPath = askModelToPickPdfPath(goal, listResult.result)
        if (pickedPath.isNullOrBlank() || !pickedPath.lowercase().endsWith(".pdf")) {
            restoreAgentMode()
            return ToolOutcome.LogicErr("Could not determine which PDF you meant")
        }
        Log.d(TAG, "Document flow: model picked PDF path: $pickedPath")

        val pageCount = extractPageCountFromGoal(goal)
        onStatusUpdate("Reading $pageCount pages from PDF…")
        val readResult = readPdfPageRange(
            mapOf(
                "file_path" to pickedPath,
                "start_page" to "1",
                "end_page" to pageCount.toString(),
            )
        )
        if (readResult !is ToolOutcome.Ok || readResult.result.length < 20) {
            restoreAgentMode()
            return ToolOutcome.TransientErr("Failed to read PDF content")
        }
        Log.d(TAG, "Document flow: extracted ${readResult.result.length} chars from PDF")

        val result = when (intent) {
            DocumentIntent.QUIZ -> generateAndLaunchQuiz(readResult.result, goal, onToolExecuting)
            DocumentIntent.SUMMARY -> generateSummary(readResult.result, onToolExecuting)
        }
        restoreAgentMode()
        return result
    }

    private enum class DocumentIntent {
        QUIZ, SUMMARY
    }

    private fun detectDocumentIntent(goal: String): DocumentIntent {
        val lowered = goal.lowercase()
        return if (lowered.contains("quiz")) DocumentIntent.QUIZ else DocumentIntent.SUMMARY
    }

    /**
     * Vision extraction: reset to freeform mode, send image, collect text.
     */
    private suspend fun extractTextFromImage(pngBytes: ByteArray): String? {
        val extractionSystemPrompt = "You are a document reader. When given an image, extract all visible text exactly as it appears."
        switchToFreeformMode(extractionSystemPrompt)

        val prompt = "Extract ALL visible text from this image. Return ONLY the raw text content, no commentary."
        val text = StringBuilder()
        engine.sendAgentMessage(prompt, listOf(pngBytes)).collect { response ->
            when (response) {
                is AgentResponse.Token -> text.append(response.text)
                else -> Unit
            }
        }
        return text.toString().trim().takeIf { it.length > 20 }
    }

    /**
     * Stateless PDF path picker: model gets the list and picks the path.
     */
    private suspend fun askModelToPickPdfPath(goal: String, pdfList: String): String? {
        val prompt = buildString {
            append("The user said: \"$goal\"\n\n")
            append("Here are the available PDFs:\n$pdfList\n\n")
            append("Reply with ONLY the full file path of the PDF the user is referring to. ")
            append("If unsure, pick the closest match. Reply with just the path, nothing else.")
        }
        val text = StringBuilder()
        engine.sendAgentMessage(prompt).collect { response ->
            when (response) {
                is AgentResponse.Token -> text.append(response.text)
                else -> Unit
            }
        }
        return text.toString().trim().lines().firstOrNull()?.trim()
    }

    private fun extractPageCountFromGoal(goal: String): Int {
        val match = Regex("(\\d+)\\s*page").find(goal.lowercase())
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 5) ?: 3
    }

    /**
     * Generate quiz JSON from content, launch QuizActivity, return ToolOutcome.
     */
    private suspend fun generateAndLaunchQuiz(
        content: String,
        goal: String,
        onToolExecuting: (String, String?) -> Unit,
    ): ToolOutcome {
        onToolExecuting("Generating quiz", "Creating questions from content…")
        val quizSession = generateQuizFromContent(content)
            ?: return ToolOutcome.LogicErr("Failed to generate quiz questions from the content")

        QuizStore.setPending(quizSession)
        launchQuizActivity()
        return ToolOutcome.Ok("Quiz launched with ${quizSession.questions.size} questions: ${quizSession.title}")
    }

    /**
     * Generate summary from content. Resets conversation for a clean context.
     */
    private suspend fun generateSummary(
        content: String,
        onToolExecuting: (String, String?) -> Unit,
    ): ToolOutcome {
        onToolExecuting("Generating summary", "Analyzing content…")
        switchToFreeformMode("You are a document summarizer. Provide clear, concise summaries.")

        val truncated = content.take(6000)
        val prompt = buildString {
            append("Summarize the following content in 3-5 clear sentences. ")
            append("Focus on the main ideas and key points.\n\n")
            append("Content:\n$truncated")
        }
        val summary = StringBuilder()
        engine.sendAgentMessage(prompt).collect { response ->
            when (response) {
                is AgentResponse.Token -> summary.append(response.text)
                else -> Unit
            }
        }
        val result = summary.toString().trim()
        return if (result.isNotBlank()) {
            ToolOutcome.Ok("Summary:\n$result")
        } else {
            ToolOutcome.LogicErr("Failed to generate summary")
        }
    }

    /**
     * Quiz generation. Resets conversation for a clean context.
     */
    private suspend fun generateQuizFromContent(content: String): QuizSession? {
        switchToFreeformMode("You are a quiz generator. Output ONLY valid JSON, no other text.")
        val truncatedContent = content.take(6000)
        val prompt = buildString {
            append("Based on the following content, create a multiple-choice quiz.\n\n")
            append("Content:\n$truncatedContent\n\n")
            append("Rules:\n")
            append("- Generate between 5 and 10 questions based on how much content is available\n")
            append("- Each question has exactly 4 options\n")
            append("- Exactly one option is correct per question\n")
            append("- correctIndex is 0-based (0, 1, 2, or 3)\n")
            append("- Return ONLY valid JSON matching this schema, no other text:\n")
            append("{\n")
            append("  \"title\": \"short quiz title\",\n")
            append("  \"questions\": [\n")
            append("    {\n")
            append("      \"question\": \"the question text\",\n")
            append("      \"options\": [\"A\", \"B\", \"C\", \"D\"],\n")
            append("      \"correctIndex\": 0\n")
            append("    }\n")
            append("  ]\n")
            append("}\n")
        }

        val responseText = StringBuilder()
        engine.sendAgentMessage(prompt).collect { response ->
            when (response) {
                is AgentResponse.Token -> responseText.append(response.text)
                else -> Unit
            }
        }

        val rawJson = extractFirstJsonObject(responseText.toString())
        if (rawJson == null) {
            Log.w(TAG, "Document flow: failed to extract JSON from quiz generation")
            return null
        }

        return try {
            val session = json.decodeFromString<QuizSession>(rawJson)
            if (session.questions.isEmpty()) {
                Log.w(TAG, "Document flow: model returned empty questions list")
                null
            } else {
                val validQuestions = session.questions
                    .filter { it.options.size == 4 && it.correctIndex in 0..3 }
                    .take(10)
                if (validQuestions.isEmpty()) null
                else session.copy(questions = validQuestions)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Document flow: failed to parse quiz JSON: ${e.message}")
            null
        }
    }

    private suspend fun launchQuizActivity() {
        withContext(Dispatchers.Main) {
            runCatching {
                val intent = Intent(appContext, QuizActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(intent)
            }.onFailure { e ->
                Log.w(TAG, "Failed to launch QuizActivity: ${e.message}")
            }
        }
    }
}
