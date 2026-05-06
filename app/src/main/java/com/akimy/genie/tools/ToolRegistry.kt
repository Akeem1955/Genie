package com.akimy.genie.tools

import android.util.Log
import com.akimy.genie.agent.ToolOutcome
import com.akimy.genie.tools.impl.*

private const val TAG = "GenieToolRegistry"

/**
 * Central registry of all tools available to the Genie agent.
 *
 * Maps tool names → GenieTool implementations.
 * Validates tool existence before execution.
 */
class ToolRegistry {

    private val tools = mutableMapOf<String, GenieTool>()

    init {
        // Register all built-in tools
        register(ReplyTool())
        register(ClickTool())
        register(ClickElementByIdTool())
        register(TapAtTool())
        register(TypeTextTool())
        register(SwipeTool())
        register(ScrollTool())
        register(ReadScreenTool())
        register(TakeScreenshotTool())
        register(OpenAppTool())
        register(GoBackTool())
        register(GoHomeTool())
        register(ReadFocusedTool())
        register(FocusNextTool())
        register(FocusPreviousTool())
        register(FocusFirstTool())
        register(FocusByTextTool())
        register(FocusByRoleTool())
        register(ActivateFocusedTool())
        register(ScrollForwardTool())
        register(ScrollBackwardTool())
        register(ReadScreenSummaryTool())
        register(ReadRecentEventsTool())
        register(WhereAmITool())
        register(ReadNearbyContextTool())
        register(WhatCanIDoHereTool())
        register(ReadScreenChangesTool())
        register(EnableContinuousReaderTool())
        register(DisableContinuousReaderTool())
        register(ReadContinuousReaderStatusTool())
        register(RepeatLastNarrationTool())
        register(ReadScreenMapTool())
        register(SaveScreenHintTool())
        register(ReadDialogTool())
        register(ReadNotificationsTool())
        register(ReadFormStateTool())
        register(SaveFactTool())
        register(RetrieveFactTool())
        register(ReadPdfPageRangeTool())
        register(ListDevicePdfsTool())
        register(DetectOpenPdfTool())
        register(VisualizeConceptTool())
        register(TeachWithBoardTool())
        register(BoardAddObjectTool())
        register(BoardUpdateObjectTool())
        register(BoardRemoveObjectTool())
        register(BoardFocusObjectTool())
        register(BoardRevealStepTool())
        register(BoardNextStepTool())
        register(BoardPrevStepTool())
        register(BoardReplayStepTool())
        register(BoardSetNarrationTool())
        register(AnnotateSceneTool())
        register(AnnotationAddBoxTool())
        register(AnnotationAddLabelTool())
        register(AnnotationAddPointerTool())
        register(AnnotationClearTool())
        register(AnnotationReplayTool())
    }

    /**
     * Register a tool in the registry.
     */
    fun register(tool: GenieTool) {
        tools[tool.name] = tool
        Log.d(TAG, "Registered tool: ${tool.name}")
    }

    /**
     * Look up a tool by name.
     * @return The tool, or null if not found
     */
    fun getTool(name: String): GenieTool? = tools[name]

    /**
     * Execute a tool by name with the given arguments.
     *
     * @param name The tool name from the agent's Decision.Act
     * @param args The arguments map
     * @param serviceContext The accessibility service context
     * @return ToolOutcome — includes LogicErr if tool not found
     */
    suspend fun execute(
        name: String,
        args: Map<String, String>,
        serviceContext: ToolServiceContext,
    ): ToolOutcome {
        val tool = tools[name]
        if (tool == null) {
            Log.w(TAG, "Tool not found: '$name'. Available: ${tools.keys}")
            return ToolOutcome.LogicErr(
                "Unknown tool '$name'. Available tools: ${tools.keys.joinToString()}"
            )
        }

        return tool.execute(args, serviceContext)
    }

    /**
     * Get all registered tool names (for prompt injection).
     */
    fun getToolNames(): Set<String> = tools.keys.toSet()

    /**
     * Get all registered tools.
     */
    fun getAllTools(): Collection<GenieTool> = tools.values
}
