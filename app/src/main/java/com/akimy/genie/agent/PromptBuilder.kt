package com.akimy.genie.agent

/**
 * Constructs the full prompt for the LLM planner.
 *
 * The prompt has two sections:
 * 1. **Anchor** (static): goal, injected user facts, tool definitions, output format
 * 2. **Window** (dynamic): recent history from SlidingWindowManager
 *
 * The model must output strict JSON matching the Decision sealed class.
 */
class PromptBuilder(
    private val slidingWindowManager: SlidingWindowManager = SlidingWindowManager(),
) {
    companion object {
        /**
         * System prompt that defines Genie's agent behavior.
         * Forces structured JSON output for the LangGraph planner.
         */
        const val AGENT_SYSTEM_PROMPT = """You are Genie, an autonomous Android accessibility agent. You control the user's device through accessibility tools to accomplish their goals.

## Behavior Rules
1. You MUST output exactly ONE JSON object per turn. No extra text.
2. You make step-by-step decisions — one tool call per turn.
3. After each tool result, evaluate whether the goal is met.
4. If the goal is met, output a "finish" decision.
5. If a tool fails, try a different approach — do NOT repeat the same failing action.
6. NEVER invent tools that don't exist. Only use tools from the Available Tools list.

## Output Format
You MUST respond with valid JSON in one of these two formats:

To execute a tool:
{"action": "act", "tool": "<tool_name>", "args": {"<key>": "<value>"}}

To complete the goal:
{"action": "finish", "summary": "<brief description of what was accomplished>"}

## Available Tools
- click: Click on a UI element. Args: {"target": "text or description of element"}
- type_text: Type text into a field. Args: {"text": "the text to type"}
- swipe: Swipe gesture. Args: {"direction": "up|down|left|right"}
- read_screen: Read all text on screen. Args: {}
- take_screenshot: Capture the screen. Args: {}
- open_app: Open an app by name. Args: {"name": "app name"}
- go_back: Press the back button. Args: {}
- go_home: Press the home button. Args: {}
- save_fact: Remember a user preference. Args: {"key": "fact_key", "value": "fact_value"}
- retrieve_fact: Recall a user preference. Args: {"key": "fact_key"}
- scroll: Scroll the screen. Args: {"direction": "up|down"}

## Important
- Be precise with click targets. Use the exact text visible on screen.
- After reading the screen, plan your next action based on what you see.
- If you're unsure, read the screen first."""
    }

    /**
     * Build the full prompt text for the planner.
     *
     * @param state The current agent state
     * @param injectedFacts User facts from the Room database to inject
     * @return The assembled prompt string
     */
    fun buildPrompt(
        state: AgentState,
        injectedFacts: List<String> = emptyList(),
    ): String {
        val sb = StringBuilder()

        // -- User Facts Section (if any persisted facts exist) --
        if (injectedFacts.isNotEmpty()) {
            sb.appendLine("## User Preferences")
            injectedFacts.forEach { fact ->
                sb.appendLine("- $fact")
            }
            sb.appendLine()
        }

        // -- Goal Section --
        sb.appendLine("## Current Goal")
        sb.appendLine(state.goal)
        sb.appendLine()

        // -- History Window --
        val window = slidingWindowManager.getWindow(state.history)
        if (window.isNotEmpty()) {
            sb.appendLine("## Action History")
            for (entry in window) {
                when (entry) {
                    is HistoryEntry.UserMessage -> {
                        sb.appendLine("[USER] ${entry.text}")
                    }
                    is HistoryEntry.ModelDecision -> {
                        when (val decision = entry.decision) {
                            is Decision.Act -> {
                                sb.appendLine("[AGENT] Called tool: ${decision.tool}(${decision.args})")
                            }
                            is Decision.Finish -> {
                                sb.appendLine("[AGENT] Finished: ${decision.summary}")
                            }
                        }
                    }
                    is HistoryEntry.ToolResult -> {
                        val status = when (entry.outcome) {
                            is ToolOutcome.Ok -> "OK: ${entry.outcome.result}"
                            is ToolOutcome.TransientErr -> "TRANSIENT_ERROR: ${entry.outcome.message}"
                            is ToolOutcome.LogicErr -> "LOGIC_ERROR: ${entry.outcome.message}"
                            is ToolOutcome.AuthErr -> "AUTH_DENIED: ${entry.outcome.message}"
                            is ToolOutcome.FatalErr -> "FATAL: ${entry.outcome.message}"
                        }
                        sb.appendLine("[RESULT] ${entry.toolName} → $status")
                    }
                }
            }
            sb.appendLine()
        }

        sb.appendLine("## Your Decision")
        sb.appendLine("Based on the goal and history above, output your next JSON decision:")

        return sb.toString()
    }
}
