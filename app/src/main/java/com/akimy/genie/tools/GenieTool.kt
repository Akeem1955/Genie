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
     * Execute the tool with the given arguments.
     *
     * @param args Key-value arguments from the agent's Decision.Act
     * @param serviceContext The accessibility service context for OS operations
     * @return ToolOutcome - Ok, TransientErr, LogicErr, AuthErr, or FatalErr
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

    /** Consume PNG bytes from the most recent screenshot capture for multimodal planning */
    suspend fun consumeLatestScreenshotPngBytes(): ByteArray?

    /** Open an app by name */
    suspend fun openApp(name: String): Boolean

    /** Press the back button */
    suspend fun goBack(): Boolean

    /** Press the home button */
    suspend fun goHome(): Boolean

    /** Perform a tap at specific coordinates */
    suspend fun tap(x: Float, y: Float): Boolean

    /** Get a snapshot of the current screen state for risk assessment */
    suspend fun getScreenContext(): ScreenContext

    /** Get the current viewport dimensions in physical pixels */
    suspend fun getViewportInfo(): ViewportInfo

    /** Read the currently accessibility-focused element */
    suspend fun readFocusedNode(): String

    /** Move accessibility focus to the next navigable element */
    suspend fun focusNext(): Boolean

    /** Move accessibility focus to the previous navigable element */
    suspend fun focusPrevious(): Boolean

    /** Move accessibility focus to the first navigable element on screen */
    suspend fun focusFirst(): Boolean

    /** Move accessibility focus to the first matching element by visible text */
    suspend fun focusElementByText(target: String): Boolean

    /** Move accessibility focus to the first matching element by semantic role */
    suspend fun focusElementByRole(role: String): Boolean

    /** Activate the currently accessibility-focused element */
    suspend fun activateFocused(): Boolean

    /** Scroll the current container forward using native accessibility actions */
    suspend fun scrollForward(): Boolean

    /** Scroll the current container backward using native accessibility actions */
    suspend fun scrollBackward(): Boolean

    /** Read a compact semantic summary of the current screen */
    suspend fun readScreenSummary(): String

    /** Read recent awareness events such as focus, notification, and dialog changes */
    suspend fun readRecentEvents(limit: Int = 5): String

    /** Read a full orientation summary of the current screen and focus */
    suspend fun whereAmI(): String

    /** Read semantic context around the currently focused node */
    suspend fun readNearbyContext(): String

    /** Read the native accessibility actions available on the current item */
    suspend fun whatCanIDoHere(): String

    /** Read meaningful screen changes detected since the last awareness update */
    suspend fun readScreenChanges(): String

    /** Turn continuous reader narration on */
    suspend fun enableContinuousReader(): String

    /** Turn continuous reader narration off */
    suspend fun disableContinuousReader(): String

    /** Read whether continuous reader narration is on */
    suspend fun readContinuousReaderStatus(): String

    /** Repeat the last spoken continuous-reader hint */
    suspend fun repeatLastNarration(): String

    /** Read Genie's learned shortcuts and landmarks for the current screen */
    suspend fun readScreenMap(): String

    /** Save a user-provided note for the current screen map */
    suspend fun saveScreenHint(note: String): String

    /** Read the currently visible dialog if one exists */
    suspend fun readDialog(): String

    /** Read recent notification summaries */
    suspend fun readNotifications(limit: Int = 5): String

    /** Read the visible form fields, hints, errors, and focused state */
    suspend fun readFormState(): String

    /** Start audio recording for Scribe profile */
    suspend fun startAudioRecording(): Boolean

    /** Stop audio recording and return the file path */
    suspend fun stopAudioRecording(): String?

    /** Transcribe audio file to text using specified language */
    suspend fun transcribeAudio(audioFilePath: String, language: String): String

    /** Extract insights from transcription (summary, key points, action items) */
    suspend fun extractInsights(transcription: String, outputLanguage: String): GeneralInsights

    /** Format transcription into SOAP note structure */
    suspend fun formatSoapNote(transcription: String, outputLanguage: String): SoapNote

    /** Search local WHO health topics for matching names */
    suspend fun searchHealthTopics(query: String): List<String>

    /** Load a specific WHO health topic record by exact name */
    suspend fun getHealthTopic(name: String): HealthRecord?

}

data class ViewportInfo(
    val widthPx: Int,
    val heightPx: Int,
)
