package com.akimy.genie.tools

import com.akimy.genie.agent.ToolOutcome

/**
 * Interface for all Genie tools that the agent can invoke.
 *
 * Each tool represents an atomic OS-level action that the agent can execute
 * via the AccessibilityService. Tools are registered in the ToolRegistry
 * and dispatched by the AgentOrchestrator.
 */
interface GenieTool {
    /** Unique name matching the tool name in the agent's prompt */
    val name: String

    /** Human-readable description for prompt injection */
    val description: String

    /**
     * Whether this tool requires HITL biometric authentication.
     * When true, the HITLInterceptionWrapper will trigger a BiometricPrompt
     * before allowing execution. Used for destructive actions.
     */
    val requiresAuth: Boolean
        get() = false

    /**
     * Execute the tool with the given arguments.
     *
     * @param args Key-value arguments from the agent's Decision.Act
     * @param serviceContext The accessibility service context for OS operations
     * @return ToolOutcome — Ok, TransientErr, LogicErr, AuthErr, or FatalErr
     */
    suspend fun execute(
        args: Map<String, String>,
        serviceContext: ToolServiceContext,
    ): ToolOutcome
}

/**
 * Context provided to tools for accessing OS-level services.
 * This decouples tools from the AccessibilityService directly.
 */
interface ToolServiceContext {
    /** Perform a click on a UI element identified by text or content description */
    suspend fun clickElement(target: String): Boolean

    /** Type text into the currently focused field */
    suspend fun typeText(text: String): Boolean

    /** Perform a swipe gesture */
    suspend fun swipe(direction: String): Boolean

    /** Perform a scroll action */
    suspend fun scroll(direction: String): Boolean

    /** Read all text currently visible on screen */
    suspend fun readScreenText(): String

    /** Capture a screenshot and return a description or path */
    suspend fun takeScreenshot(): String

    /** Open an app by name */
    suspend fun openApp(name: String): Boolean

    /** Press the back button */
    suspend fun goBack(): Boolean

    /** Press the home button */
    suspend fun goHome(): Boolean

    /** Perform a tap at specific coordinates */
    suspend fun tap(x: Float, y: Float): Boolean
}
