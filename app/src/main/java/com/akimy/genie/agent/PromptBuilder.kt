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
3. When executing a plan, tasks means the current plan step is complete. To answer a user's question, call reply(message=...).
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
            if (profile == ToolProfile.SeeAndTap) {
                return """You are Genie, an autonomous Android accessibility agent operating in the SeeAndTap profile.

You will receive either a planning task or an execution task. The user prompt will indicate which one."""
            }
            val activeTools = (profile.toolNames + "tasks").sorted()
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
                    "click_element_by_id",
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
            val memoryTools = activeTools.filter {
                it in setOf("save_fact", "retrieve_fact")
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
                appendLine("3. To answer a user's question or explain something, ALWAYS call reply(message=...). Only use tasks to silently mark an internal step complete.")
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
                if (memoryTools.isNotEmpty()) {
                    appendLine("9. Memory tools available in this profile: ${memoryTools.joinToString(", ")}.")
                    appendLine("10. If the user states a durable personal fact or preference, save it with save_fact; if the user asks what you remember, use retrieve_fact or injected user preferences.")
                    appendLine("11. If authorization is denied for a risky action, treat that action as cancelled and finish politely.")
                } else {
                    appendLine("9. If authorization is denied for a risky action, treat that action as cancelled and finish politely.")
                }
                appendLine()
                appendLine("## Important")
                appendLine("- Execute only the current plan step.")
                if (profile == ToolProfile.SeeAndTap) {
                    appendLine("- In this profile, use take_screenshot to see the screen, then use click_element_by_id using the numbered boxes drawn on the image.")
                    appendLine("- Do not guess coordinates before seeing a screenshot unless the user explicitly provided exact normalized coordinates.")
                }
                if ("save_fact" in activeTools) {
                    appendLine("- For user memory, do not search the current screen for the fact text. Use save_fact for statements like allergies, food restrictions, preferences, names, routines, or other durable user facts.")
                }
                appendLine("- Never click the entire user command; decompose recipient, message, app, object, or target first.")
                appendLine("- If the goal is messaging, treat the contact name and message body as separate entities.")
                appendLine("- Only type the message body after the right input field is focused.")
                appendLine("- For messaging, never treat a visible contact name as opened. Confirm the contact chat is open and the message input exists before typing.")
                if ("tap_at" in activeTools) {
                    appendLine("- If a contact appears in read_screen but focus_by_text fails, try click(contact). If click fails, take_screenshot and use tap_at on the contact row.")
                    appendLine("- Use tap_at only after a screenshot is available or after text/accessibility tools cannot reach a visible control. tap_at uses normalized Android coordinates: x=0 left, x=1000 right, y=0 top, y=1000 bottom.")
                } else {
                    appendLine("- If a contact appears in read_screen but focus_by_text fails, try click(contact).")
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

                            else -> {}
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
        sb.appendLine("Based on the goal and history above, call exactly one tool. If the goal is complete, call tasks.")

        return sb.toString()
    }

    fun buildPlanPrompt(
        goal: String,
        injectedFacts: List<String> = emptyList(),
        availableTools: Set<String> = emptySet(),
        toolProfile: ToolProfile? = null,
    ): String {
        val sb = StringBuilder()

        // SeeAndTap planning mode: explicit mode identification
        if (toolProfile == ToolProfile.SeeAndTap) {
            sb.appendLine("## MODE: PLANNING")
            sb.appendLine()
            sb.appendLine("You are in planning mode. Your job is to create a multi-step execution plan for the user's goal.")
            sb.appendLine()
            sb.appendLine("## User Goal")
            sb.appendLine(goal)
            sb.appendLine()
            if (availableTools.isNotEmpty()) {
                sb.appendLine("## Available Tools")
                sb.appendLine(availableTools.sorted().joinToString(", "))
                sb.appendLine()
            }
            if (injectedFacts.isNotEmpty()) {
                sb.appendLine("## User Preferences")
                injectedFacts.forEach { fact -> sb.appendLine("- $fact") }
                sb.appendLine()
            }
            sb.appendLine("## Planning Rules")
            sb.appendLine("1. Decompose the user's intent into a sequence of small steps.")
            sb.appendLine("2. Each step should be actionable (take_screenshot, click_element_by_id, etc).")
            sb.appendLine("3. For visual interactions, plan a take_screenshot step before clicking unless the user provided exact coordinates.")
            sb.appendLine("4. Keep steps small and verifiable so the agent can recover if something goes wrong.")
            sb.appendLine()
            sb.appendLine("## Required Response")
            sb.appendLine("Call tasks with plan set to JSON. Do not call any action tools during planning.")
            sb.appendLine("IMPORTANT: Use standard double quotes (\") for JSON. Do not include special token tags inside the JSON string.")
            sb.appendLine("JSON schema:")
            sb.appendLine(
                """{
  "intent": {
    "summary": "...",
    "entities": {
      "key": "value"
    }
  },
  "steps": [
    {
      "instruction": "...",
      "expectedOutcome": "...",
      "allowedTools": [
        "tool_name"
      ]
    }
  ]
}"""
            )
            sb.appendLine()
            return sb.toString()
        }

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
        sb.appendLine("Call tasks with plan set to JSON only. Do not call an action tool.")
        sb.appendLine("IMPORTANT: Use standard double quotes (\") for JSON keys and values. Do not use special token tags inside the JSON string.")
        sb.appendLine("JSON schema:")
        sb.appendLine(
            """{
  "intent": {
    "summary": "...",
    "entities": {
      "key": "value"
    }
  },
  "steps": [
    {
      "instruction": "...",
      "expectedOutcome": "...",
      "allowedTools": [
        "tool_name"
      ]
    }
  ]
}"""
        )
        sb.appendLine()
        sb.appendLine("Rules:")
        sb.appendLine("- Decompose the user's intent before planning actions.")
        sb.appendLine("- Keep steps small and verifiable.")
        if (availableTools == setOf("tasks", "take_screenshot", "tap_at") ||
            availableTools == setOf("take_screenshot", "tap_at", "tasks")
        ) {
            sb.appendLine("- For visual tap goals, plan a screenshot step before any tap_at step unless the user supplied exact normalized coordinates.")
            sb.appendLine("- Use tap_at only with normalized Android coordinates from 0 to 1000.")
        }
        if ("save_fact" in availableTools) {
            sb.appendLine("- If the user states a durable personal fact or preference, create a save_fact step with a stable key and exact value; do not inspect the screen for that fact.")
        }
        if ("retrieve_fact" in availableTools) {
            sb.appendLine("- If the user asks what Genie remembers or asks about a saved preference, create a retrieve_fact step or answer from injected User Preferences.")
        }
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
        toolProfile: ToolProfile? = null,
    ): String {
        val plan = state.plan
        val step = plan?.steps?.getOrNull(state.currentStepIndex)

        if (plan == null || step == null) {
            return buildPrompt(state, injectedFacts, hasVisionInput)
        }

        val sb = StringBuilder()

        // SeeAndTap execution mode: explicit mode identification
        if (toolProfile == ToolProfile.SeeAndTap) {
            sb.appendLine("## MODE: EXECUTION")
            sb.appendLine()
            sb.appendLine("You are executing step ${state.currentStepIndex + 1}/${plan.steps.size}.")
            sb.appendLine("Emit exactly ONE tool call. Do NOT create or update the plan.")
            sb.appendLine()
            sb.appendLine("## Current Goal")
            sb.appendLine(state.goal)
            sb.appendLine()
            sb.appendLine("## Step Details")
            sb.appendLine("Instruction: ${step.instruction}")
            sb.appendLine("Expected outcome: ${step.expectedOutcome}")
            if (step.allowedTools.isNotEmpty()) {
                sb.appendLine("Available tools for this step: ${step.allowedTools.joinToString(", ")}")
            }
            sb.appendLine()
            if (hasVisionInput) {
                sb.appendLine("## Visual Context")
                sb.appendLine("A screenshot is attached. It shows numbered boxes for clickable elements.")
                sb.appendLine("Use click_element_by_id with the box numbers you see.")
                sb.appendLine()
            }
            sb.appendLine("## Execution Rules for SeeAndTap")
            sb.appendLine("1. If you need to see the screen, call take_screenshot.")
            sb.appendLine("2. After take_screenshot, you will see numbered boxes on the image.")
            sb.appendLine("3. Call click_element_by_id(id=\\\\\"NUMBER\\\\\") to click the box with that number.")
            sb.appendLine("4. When this step is complete, call tasks(plan=\\\\\"step complete: <brief note>\\\\\").")
            sb.appendLine("5. Never guess or use coordinates before seeing a screenshot.")
            sb.appendLine()
            appendHistoryWindow(sb, state)
            sb.appendLine()
            sb.appendLine("## Your Next Action")
            sb.appendLine("Execute the current step only. Emit exactly one native tool call.")
            sb.appendLine()
            return sb.toString()
        }

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
            if ("tap_at" in step.allowedTools) {
                sb.appendLine("If using tap_at, use normalized Android coordinates: x=0 left, x=1000 right, y=0 top, y=1000 bottom.")
            }
            sb.appendLine()
        }

        sb.appendLine("## Your Decision")
        sb.appendLine("Execute only the current step. Call exactly one native tool.")
        sb.appendLine("CRITICAL: If you have gathered enough information to answer the user's question, you MUST call reply(message=...) to speak to the user.")
        sb.appendLine("Otherwise, if the current step is complete but you do not need to speak, call tasks with a short step-completion note.")
        sb.appendLine("Do not finish the overall goal unless this is the last plan step and its expected outcome is complete.")
        if ("save_fact" in step.allowedTools || "retrieve_fact" in step.allowedTools) {
            sb.appendLine("Memory guardrails: use save_fact for durable user facts/preferences stated by the user, and retrieve_fact for explicit memory questions. Do not look for those fact words on the current screen.")
        }
        if ("tap_at" in step.allowedTools && "take_screenshot" in step.allowedTools) {
            sb.appendLine("Visual tap guardrails: call take_screenshot before tap_at unless a screenshot is attached or the user gave exact normalized coordinates.")
        }
        if ("type_text" in step.allowedTools) {
            sb.appendLine("Messaging guardrails: do not call type_text until read_form_state confirms a focused/available message input. Do not press Send until type_text succeeded.")
        }
        if ("click" in step.allowedTools) {
            if ("tap_at" in step.allowedTools) {
                sb.appendLine("If visible text cannot be focused, use click on that exact text; if click fails, take_screenshot and then tap_at the visible row/control.")
            } else {
                sb.appendLine("If visible text cannot be focused, use click on that exact text.")
            }
        }

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
                        is Decision.Reply -> sb.appendLine("[AGENT] Replied: ${decision.message}")
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
