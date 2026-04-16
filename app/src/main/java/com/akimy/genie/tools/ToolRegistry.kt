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
        register(ClickTool())
        register(TypeTextTool())
        register(SwipeTool())
        register(ScrollTool())
        register(ReadScreenTool())
        register(TakeScreenshotTool())
        register(OpenAppTool())
        register(GoBackTool())
        register(GoHomeTool())
        register(SaveFactTool())
        register(RetrieveFactTool())
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

    /**
     * Check if a tool requires HITL auth.
     */
    fun requiresAuth(name: String): Boolean = tools[name]?.requiresAuth ?: false
}
