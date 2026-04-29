package com.akimy.genie.agent

import com.akimy.genie.tools.ToolProfile

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
3. When executing a plan, finish_task means the current plan step is complete. The orchestrator finishes the whole goal only after every plan step is complete.
4. After each tool result, decide whether the current plan step is complete or whether one more tool is needed.
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

        fun systemPromptForProfile(profile: ToolProfile): String {
            val activeTools = (profile.toolNames + "finish_task").sorted()
            val awarenessTools = activeTools.filter {
                it in setOf(
                    "read_screen",
                    "read_screen_summary",
                    "where_am_i",
                    "read_focused",
                    "read_nearby_context",
                    "read_form_state",
                    "take_screenshot",
                )
            }
            val interactionTools = activeTools.filter {
                it in setOf(
                    "click",
                    "focus_by_text",
                    "focus_by_role",
                    "focus_first",
                    "focus_next",
                    "focus_previous",
                    "activate_focused",
                    "type_text",
                    "tap_at",
                    "scroll",
                    "swipe",
                    "go_back",
                    "go_home",
                    "open_app",
                )
            }

            return buildString {
                appendLine("You are Genie, an autonomous Android accessibility agent. You control the user's device through accessibility tools to accomplish goals safely and step by step.")
                appendLine()
                appendLine("## Active Tool Profile")
                appendLine("Profile: ${profile.displayName}")
                appendLine("Purpose: ${profile.description}")
                appendLine("Available tools: ${activeTools.joinToString(", ")}")
                appendLine()
                appendLine("## Behavior Rules")
                appendLine("1. Call exactly one tool per turn. Do not output plain text during planning.")
                appendLine("2. Use only the provided tool schema and active profile tools. Never invent tools or arguments.")
                appendLine("3. When executing a plan, finish_task means the current plan step is complete. The orchestrator finishes the whole goal only after every plan step is complete.")
                appendLine("4. After each tool result, decide whether the current plan step is complete or whether one more tool is needed.")
                appendLine("5. If a tool fails, choose a different strategy and do not repeat the same failing action.")
                if (awarenessTools.isNotEmpty()) {
                    appendLine("6. If you are unsure what is on screen, use one of these awareness tools before taking another action: ${awarenessTools.joinToString(", ")}.")
                } else {
                    appendLine("6. If you are unsure what to do, complete the step only when the visible or remembered state supports it.")
                }
                if (interactionTools.isNotEmpty()) {
                    appendLine("7. Keep actions small and deliberate. Prefer exact visible text and focused controls before coordinate taps.")
                    appendLine("8. Interaction tools available in this profile: ${interactionTools.joinToString(", ")}.")
                } else {
                    appendLine("7. Keep actions small and deliberate, using only the active profile tools.")
                }
                appendLine("9. If authorization is denied for a risky action, treat that action as cancelled and finish politely.")
                appendLine()
                appendLine("## Important")
                appendLine("- Execute only the current plan step.")
                appendLine("- Never click the entire user command; decompose recipient, message, app, object, or target first.")
                appendLine("- If the goal is messaging, treat the contact name and message body as separate entities.")
                appendLine("- Only type the message body after the right input field is focused.")
                appendLine("- For messaging, never treat a visible contact name as opened. Confirm the contact chat is open and the message input exists before typing.")
                appendLine("- If a contact appears in read_screen but focus_by_text fails, try click(contact). If click fails, take_screenshot and use tap_at on the contact row.")
                if ("tap_at" in activeTools) {
                    appendLine("- Use tap_at only after a screenshot is available or after text/accessibility tools cannot reach a visible control. tap_at uses normalized Android coordinates: x=0 left, x=1000 right, y=0 top, y=1000 bottom.")
                }
                if (profile == ToolProfile.Annotation) {
                    appendLine("- For annotation_add_* tools, prefer normalized coordinates in the 0.0-1.0 range unless exact pixels are required.")
                }
            }.trim()
        }
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

                    is HistoryEntry.PlanCreated -> {
                        sb.appendLine("[PLAN] Created ${entry.plan.steps.size} execution steps")
                    }

                    is HistoryEntry.StepCompleted -> {
                        sb.appendLine("[STEP ${entry.stepIndex + 1}] COMPLETE: ${entry.summary}")
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

    fun buildPlanPrompt(
        goal: String,
        injectedFacts: List<String> = emptyList(),
        availableTools: Set<String> = emptySet(),
    ): String {
        val sb = StringBuilder()

        sb.appendLine("## Planning Task")
        sb.appendLine("Create a grounded execution plan for the user's goal. Do not execute tools yet.")
        sb.appendLine()

        sb.appendLine("## User Goal")
        sb.appendLine(goal)
        sb.appendLine()

        if (injectedFacts.isNotEmpty()) {
            sb.appendLine("## User Preferences")
            injectedFacts.forEach { fact -> sb.appendLine("- $fact") }
            sb.appendLine()
        }

        if (availableTools.isNotEmpty()) {
            sb.appendLine("## Available Tools")
            sb.appendLine(availableTools.sorted().joinToString(", "))
            sb.appendLine()
        }

        sb.appendLine("## Required Response")
        sb.appendLine("Call finish_task with summary set to JSON only. Do not call an action tool.")
        sb.appendLine("JSON schema:")
        sb.appendLine(
            """{"intent":{"summary":"...","entities":{"key":"value"}},"steps":[{"instruction":"...","expectedOutcome":"...","allowedTools":["tool_name"]}]}"""
        )
        sb.appendLine()
        sb.appendLine("Rules:")
        sb.appendLine("- Decompose the user's intent before planning actions.")
        sb.appendLine("- Keep steps small and verifiable.")
        sb.appendLine("- Include recipient/message/app entities when the goal implies them.")
        sb.appendLine("- For messaging goals, separate the recipient-selection step from the message-typing step.")
        sb.appendLine("- Add a verification/precondition step before typing: confirm the recipient chat is open and the message input is visible or focused.")
        sb.appendLine("- Add a verification/precondition step before sending: confirm the message body was typed and the Send control is visible.")
        sb.appendLine("- Include a final verification step for user-visible completion.")

        return sb.toString()
    }

    fun buildStepPrompt(
        state: AgentState,
        injectedFacts: List<String> = emptyList(),
        hasVisionInput: Boolean = false,
    ): String {
        val plan = state.plan
        val step = plan?.steps?.getOrNull(state.currentStepIndex)

        if (plan == null || step == null) {
            return buildPrompt(state, injectedFacts, hasVisionInput)
        }

        val sb = StringBuilder()

        if (injectedFacts.isNotEmpty()) {
            sb.appendLine("## User Preferences")
            injectedFacts.forEach { fact -> sb.appendLine("- $fact") }
            sb.appendLine()
        }

        sb.appendLine("## Current Goal")
        sb.appendLine(state.goal)
        sb.appendLine()

        sb.appendLine("## Parsed Intent")
        sb.appendLine(plan.intent.summary)
        if (plan.intent.entities.isNotEmpty()) {
            plan.intent.entities.forEach { (key, value) ->
                sb.appendLine("- $key: $value")
            }
        }
        sb.appendLine()

        sb.appendLine("## Plan")
        plan.steps.forEachIndexed { index, planStep ->
            val marker = when {
                index < state.currentStepIndex -> "[done]"
                index == state.currentStepIndex -> "[current]"
                else -> "[pending]"
            }
            sb.appendLine("${index + 1}. $marker ${planStep.instruction}")
            sb.appendLine("   Expected: ${planStep.expectedOutcome}")
        }
        sb.appendLine()

        sb.appendLine("## Current Step")
        sb.appendLine(step.instruction)
        sb.appendLine("Expected outcome: ${step.expectedOutcome}")
        if (step.allowedTools.isNotEmpty()) {
            sb.appendLine("Allowed/preferred tools for this step: ${step.allowedTools.joinToString(", ")}")
        }
        sb.appendLine()

        appendHistoryWindow(sb, state)

        if (hasVisionInput) {
            sb.appendLine("## Visual Context")
            sb.appendLine("A screenshot of the current screen is attached to this turn.")
            sb.appendLine("If using tap_at, use normalized Android coordinates: x=0 left, x=1000 right, y=0 top, y=1000 bottom.")
            sb.appendLine()
        }

        sb.appendLine("## Your Decision")
        sb.appendLine("Execute only the current step. Call exactly one native tool.")
        sb.appendLine("If the current step is already complete based on observations, call finish_task with a short step-completion summary.")
        sb.appendLine("Do not finish the overall goal unless this is the last plan step and its expected outcome is complete.")
        sb.appendLine("Messaging guardrails: do not call type_text until read_form_state confirms a focused/available message input. Do not press Send until type_text succeeded.")
        sb.appendLine("If visible text cannot be focused, use click on that exact text; if click fails, take_screenshot and then tap_at the visible row/control.")

        return sb.toString()
    }

    private fun appendHistoryWindow(sb: StringBuilder, state: AgentState) {
        val window = slidingWindowManager.getWindow(state.history)
        if (window.isEmpty()) return

        sb.appendLine("## Compact Action History")
        for (entry in window) {
            when (entry) {
                is HistoryEntry.UserMessage -> sb.appendLine("[USER] ${entry.text}")
                is HistoryEntry.PlanCreated -> sb.appendLine("[PLAN] ${entry.plan.steps.size} steps created")
                is HistoryEntry.StepCompleted -> sb.appendLine("[STEP ${entry.stepIndex + 1}] COMPLETE: ${entry.summary}")
                is HistoryEntry.ModelDecision -> {
                    when (val decision = entry.decision) {
                        is Decision.Act -> sb.appendLine("[AGENT] ${decision.tool}(${decision.args})")
                        is Decision.Finish -> sb.appendLine("[AGENT] Step completion claimed: ${decision.summary}")
                    }
                }
                is HistoryEntry.ToolResult -> {
                    sb.appendLine("[RESULT] ${entry.toolName} -> ${formatOutcome(entry.outcome)}")
                }
            }
        }
        sb.appendLine()
    }

    private fun formatOutcome(outcome: ToolOutcome): String {
        return when (outcome) {
            is ToolOutcome.Ok -> "OK: ${outcome.result.compactForPrompt()}"
            is ToolOutcome.TransientErr -> "TRANSIENT_ERROR: ${outcome.message.compactForPrompt(300)}"
            is ToolOutcome.LogicErr -> "LOGIC_ERROR: ${outcome.message.compactForPrompt(300)}"
            is ToolOutcome.AuthErr -> "AUTH_DENIED: ${outcome.message.compactForPrompt(300)}"
            is ToolOutcome.FatalErr -> "FATAL: ${outcome.message.compactForPrompt(300)}"
        }
    }

    private fun String.compactForPrompt(maxChars: Int = 1_200): String {
        return replace(Regex("\\s+"), " ")
            .trim()
            .let { if (it.length <= maxChars) it else it.take(maxChars) + "..." }
    }
}
