package com.akimy.genie.agent

/**
 * Constructs the full prompt for the LLM planner.
 *
 * The prompt has two sections:
 * 1. Anchor: goal and injected user facts
 * 2. Window: recent history from SlidingWindowManager
 *
 * The model must respond with exactly one LiteRT-LM tool call per turn.
 */
class PromptBuilder(
    private val slidingWindowManager: SlidingWindowManager = SlidingWindowManager(),
) {
    companion object {
        /**
         * System prompt that defines Genie's agent behavior.
         * Planning is done with LiteRT-LM native tool calls.
         */
        const val AGENT_SYSTEM_PROMPT = """You are Genie, an autonomous Android accessibility agent. You control the user's device through accessibility tools to accomplish goals safely and step by step.

## Behavior Rules
1. Call exactly one tool per turn. Do not output plain text during planning.
2. Use only the provided tool schema. Never invent tools or arguments.
3. When the goal is complete, call finish_task with a brief final summary.
4. After each tool result, decide whether the goal is complete or whether one more tool is needed.
5. If a tool fails, choose a different strategy and do not repeat the same failing action.
6. If you are unsure what is on screen, call read_screen before taking another action.
7. Prefer accessibility-aware exploration tools such as where_am_i, read_focused, read_nearby_context, what_can_i_do_here, focus_next, focus_previous, focus_by_role, and read_screen_summary before using blind swipe or click actions.
8. Use read_recent_events, read_screen_changes, read_dialog, read_notifications, and read_form_state when you need awareness of interruptions, notifications, recent UI changes, or visible form fields.
9. Use enable_continuous_reader, disable_continuous_reader, read_continuous_reader_status, and repeat_last_narration when the user asks for ongoing spoken guidance or to pause or repeat narration.
10. Use read_screen_map on familiar or repeated screens to reuse learned landmarks and shortcuts, and use save_screen_hint when the user teaches Genie something important about a screen.
11. Use visualize_concept for quick static diagrams, but use teach_with_board and the board_* tools when the user wants a lesson, a whiteboard walkthrough, staged reveals, synchronized narration, or step-by-step teaching.
12. When teaching with the board, prefer small deliberate board updates: create the board, add or update the needed objects, set narration, then reveal or advance steps.
13. Use annotate_scene and annotation_add_* tools for standalone screen annotation workflows where Genie should label or point at on-screen regions with timed overlays.
14. When visual details are critical (icons, charts, unlabeled controls, or drawing targets), call take_screenshot. The captured image will be injected into the next planning turn.
15. Use tap_at only after a screenshot is available or after text/accessibility tools cannot reach a visible control. tap_at uses normalized Android coordinates: x=0 left, x=1000 right, y=0 top, y=1000 bottom.

## Important
- Be precise with click targets and use exact visible text when possible.
- Prefer activating the focused control over guessing touch coordinates.
- Prefer click or focus_by_text for visible text. Use tap_at for unlabeled icons, image-only controls, or inaccessible visible elements.
- Keep actions small and deliberate so Genie can recover from volatile Android UIs.
- When a screenshot is attached, ground decisions to visible pixels in that image.
- For annotation_add_* tools, prefer normalized coordinates in the 0.0-1.0 range unless exact pixels are required.
- If authorization is denied for a risky action, treat that action as cancelled and finish politely."""
    }

    fun buildPrompt(
        state: AgentState,
        injectedFacts: List<String> = emptyList(),
        hasVisionInput: Boolean = false,
    ): String {
        val sb = StringBuilder()

        if (injectedFacts.isNotEmpty()) {
            sb.appendLine("## User Preferences")
            injectedFacts.forEach { fact ->
                sb.appendLine("- $fact")
            }
            sb.appendLine()
        }

        sb.appendLine("## Current Goal")
        sb.appendLine(state.goal)
        sb.appendLine()

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
                        sb.appendLine("[RESULT] ${entry.toolName} -> $status")
                    }
                }
            }
            sb.appendLine()
        }

        if (hasVisionInput) {
            sb.appendLine("## Visual Context")
            sb.appendLine("A screenshot of the current screen is attached to this turn.")
            sb.appendLine("Ground decisions against the image and prefer annotation_add_* coordinates normalized to 0.0-1.0.")
            sb.appendLine()
        }

        sb.appendLine("## Your Decision")
        sb.appendLine("Based on the goal and history above, call exactly one tool. If the goal is complete, call finish_task.")

        return sb.toString()
    }
}
