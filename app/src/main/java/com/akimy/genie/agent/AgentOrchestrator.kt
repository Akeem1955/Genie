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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "GenieOrchestrator"
private const val MAX_VISUALIZER_JSON_CHARS = 24_000
private const val MAX_BOARD_OBJECTS_JSON_CHARS = 32_000
private const val MAX_BOARD_STEPS_JSON_CHARS = 16_000
private const val MAX_ANNOTATION_STYLE_JSON_CHARS = 4_000
private val SCENE_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
private val OBJECT_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
private val ALLOWED_DIAGRAM_TYPES = setOf("flowchart", "cycle", "timeline", "mindmap", "table")
private val ALLOWED_BOARD_OBJECT_TYPES = setOf("title", "text", "box", "card", "circle", "line", "arrow", "code")

/**
 * The main agent loop controller.
 *
 * Flow:
 * StateInit -> Window -> PromptBuilder -> Planner -> [Act|Finish]
 * Act -> RegistryCheck -> SafetyWrapper -> Execute -> Evaluate -> UpdateState -> loop
 * Finish -> NovelCheck -> [WriteSkill|End]
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
    private val promptBuilder: PromptBuilder,
    private val planner: GeniePlanner,
    private val factDao: FactDao,
    private val skillDao: SkillDao,
    private val appContext: Context,
) {
    private val slidingWindowManager = SlidingWindowManager()
    private val json = Json { encodeDefaults = true }
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
    ): String = executeGoalInternal(
        goal = goal,
        serviceContext = serviceContext,
        onStatusUpdate = onStatusUpdate,
        allowSkillLookup = true,
        isNovelPlan = true,
    )

    private suspend fun executeGoalInternal(
        goal: String,
        serviceContext: ToolServiceContext,
        onStatusUpdate: (String) -> Unit,
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
                    Log.d(TAG, "Found cached skill with ${skillMatch.steps.size} steps")
                    state.isNovelPlan = false
                    return@withContext executeCachedPlan(
                        state = state,
                        skillMatch = skillMatch,
                        serviceContext = serviceContext,
                        onStatusUpdate = onStatusUpdate,
                    )
                }

                SkillMatch.None -> Unit
            }
        }

        var loopCount = 0
        val maxLoops = 20
        val pendingVisionInputs = mutableListOf<ByteArray>()

        while (loopCount < maxLoops) {
            loopCount++
            Log.d(TAG, "--- Loop iteration $loopCount ---")

            val facts = try {
                factDao.getAllFactsSnapshot().map { "${it.key}: ${it.value}" }
            } catch (e: Exception) {
                emptyList()
            }

            val visionInputsForTurn = pendingVisionInputs.toList()
            pendingVisionInputs.clear()
            val prompt = promptBuilder.buildPrompt(
                state = state,
                injectedFacts = facts,
                hasVisionInput = visionInputsForTurn.isNotEmpty(),
            )

            EventLogger.emit(GenieEvent.StateTransition("building_prompt", "planning"))
            when (val planResult = planner.plan(prompt, visionInputsForTurn)) {
                is PlanResult.Success -> {
                    val decision = planResult.decision
                    state.history.add(HistoryEntry.ModelDecision(decision))

                    when (decision) {
                        is Decision.Act -> {
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
                                }

                                is ToolOutcome.TransientErr -> {
                                    state.history.add(
                                        HistoryEntry.ToolResult(decision.tool, decision.args, outcome)
                                    )
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
                                        Log.w(TAG, "Max retries exceeded")
                                        onStatusUpdate("I couldn't complete this step after multiple attempts.")
                                        return@withContext "Failed: max retries exceeded for ${decision.tool}"
                                    }
                                }

                                is ToolOutcome.LogicErr -> {
                                    state.history.add(
                                        HistoryEntry.ToolResult(decision.tool, decision.args, outcome)
                                    )
                                    if (state.replanCount < state.maxReplans) {
                                        Log.d(
                                            TAG,
                                            "LogicErr - replanning (attempt ${state.replanCount + 1}/${state.maxReplans})"
                                        )
                                        state.replanCount++
                                    } else {
                                        Log.w(TAG, "Max replans exceeded")
                                        onStatusUpdate("I couldn't figure out how to do this.")
                                        return@withContext "Failed: max replans exceeded"
                                    }
                                }

                                is ToolOutcome.AuthErr -> {
                                    state.history.add(
                                        HistoryEntry.ToolResult(decision.tool, decision.args, outcome)
                                    )
                                    onStatusUpdate("Action requires your authorization, which was denied.")
                                    return@withContext "Stopped: user denied authorization for ${decision.tool}"
                                }

                                is ToolOutcome.FatalErr -> {
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
                            Log.d(TAG, "=== Goal Complete: ${decision.summary} ===")
                            EventLogger.emit(GenieEvent.StateTransition("executing", "finished"))

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
                    state.history.add(
                        HistoryEntry.ToolResult(
                            "planner",
                            emptyMap(),
                            ToolOutcome.LogicErr("Could not parse your response. ${planResult.message}"),
                        )
                    )
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
     * Execute a tool with dynamic HITL safety check based on screen context.
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
    ): String {
        for (step in skillMatch.steps) {
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
}
