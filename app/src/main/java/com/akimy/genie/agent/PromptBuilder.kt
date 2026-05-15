package com.akimy.genie.agent

import com.akimy.genie.tools.ToolProfile
import com.akimy.genie.tools.VisualizerSceneStore

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
        const val AGENT_SYSTEM_PROMPT = """You are Genie, an autonomous Android accessibility agent.
Follow the behavior rules and tool guides provided in your active profile."""

        fun systemPromptForProfile(profile: ToolProfile, teachingLanguage: String? = null): String {
            if (profile == ToolProfile.SeeAndTap) {
                return """You are Genie, an autonomous Android accessibility agent operating in the SeeAndTap profile.

You will receive either a planning task or an execution task. The user prompt will indicate which one."""
            }
            if (profile == ToolProfile.AppControl) {
                return appControlSystemPrompt()
            }
            if (profile == ToolProfile.Teaching) {
                return teachingSystemPrompt(profile.toolNames.sorted(), teachingLanguage)
            }
            if (profile == ToolProfile.Document) {
                return documentSystemPrompt(profile.toolNames.sorted())
            }
            if (profile == ToolProfile.Chat) {
                return chatSystemPrompt()
            }
            if (profile == ToolProfile.Vision) {
                return visionSystemPrompt()
            }
            if (profile == ToolProfile.Reader) {
                return readerSystemPrompt()
            }
            // Scribe and Health are fully UI-driven, orchestrator never runs
            if (profile == ToolProfile.Scribe || profile == ToolProfile.Health) {
                return ""
            }

            val activeTools = (profile.toolNames + "tasks").sorted()

            return buildString {
                appendLine("You are Genie, an autonomous Android accessibility agent. Safely control the device to achieve goals step-by-step.")
                appendLine()
                appendLine("## Context")
                appendLine("Profile: ${profile.displayName} - ${profile.description}")
                appendLine("Allowed Tools: ${activeTools.joinToString(", ")}")
                appendLine()

                appendLine("## Tool Guide")
                if ("read_screen" in activeTools) appendLine("- read_screen(): Reads all visible text.")
                if ("read_screen_summary" in activeTools) appendLine("- read_screen_summary(): Semantic UI summary.")
                if ("read_form_state" in activeTools) appendLine("- read_form_state(): Reads input fields and focus.")
                if ("take_screenshot" in activeTools) appendLine("- take_screenshot(): Captures screen with numbered boxes for clicking.")
                if ("where_am_i" in activeTools) appendLine("- where_am_i(): Current orientation in the app.")
                if ("click" in activeTools) appendLine("- click(target): Click by exact text.")
                if ("click_element_by_id" in activeTools) appendLine("- click_element_by_id(id): Tap a numbered box from a screenshot.")
                if ("type_text" in activeTools) appendLine("- type_text(text): Type into focused field.")
                if ("open_app" in activeTools) appendLine("- open_app(name): Launch app.")
                if ("scroll" in activeTools || "swipe" in activeTools) appendLine("- scroll(direction) / swipe(direction): Navigate through pages.")
                if ("go_back" in activeTools || "go_home" in activeTools) appendLine("- go_back() / go_home(): System navigation.")
                if ("visualize_concept" in activeTools) appendLine("- visualize_concept(...): Create/update static reference diagrams such as flowcharts, timelines, mindmaps, cycles, or tables.")
                if ("visualize_focus_node" in activeTools) appendLine("- visualize_focus_node(scene_id, node_id): Emphasize one node in an existing concept diagram.")
                if ("teach_with_board" in activeTools) appendLine("- teach_with_board(...): Create or replace a step-by-step teaching board with objects, lesson steps, and narration.")
                if ("board_clear" in activeTools) appendLine("- board_clear(scene_id): Clear a teaching board scene so a lesson can restart cleanly.")
                if ("board_add_object" in activeTools) appendLine("- board_add_object(...): Add one title/text/box/card/circle/line/arrow/code/path object to the board.")
                if ("board_next_step" in activeTools || "board_prev_step" in activeTools) appendLine("- board_next_step(scene_id) / board_prev_step(scene_id): Move through lesson steps.")
                if ("board_replay_step" in activeTools) appendLine("- board_replay_step(scene_id): Replay the current teaching step.")
                if ("board_set_narration" in activeTools) appendLine("- board_set_narration(...): Replace the narration text for the current board state.")
                appendLine()

                appendLine("## Rules")
                appendLine("1. Call EXACTLY ONE tool per turn. No markdown, no extra text.")
                appendLine("2. Use ONLY tools listed in 'Allowed Tools' for the current step.")
                appendLine("3. Confirm success after every action. If a tool fails, try a different strategy.")
                appendLine("4. AUTONOMOUS MODE: Decide the next logical step based on the PROGRESS LOG and screen state.")
                appendLine("5. For speaking, call reply(message=...). To finish, call tasks(plan=\"Goal complete\").")
                appendLine()

                appendLine("## Strategy")
                appendLine("- EXPLORATION: If a target is not visible, you MUST scroll/swipe ('down' or 'up') before giving up.")
                appendLine("- VISUALS: Use take_screenshot to see unlabeled buttons or verify screen state. Ground actions to visible IDs.")
                appendLine("- REPETITION: Do NOT take a screenshot twice in a row if the first one didn't help. Scroll instead.")
                appendLine("- RISKS: If a risky action is denied, finish politely.")
            }.trim()
        }

        private fun appControlSystemPrompt(): String {
            return """<|think|>You are Genie, a reactive Android device controller.

You control the device ONE action at a time. After each action you receive the screen text back. Use it to decide the next action.

## Tools
- open_app(name): Launch an app by name (WhatsApp, Spotify, Gmail, YouTube, etc).
- read_screen(): Read all visible text on screen. Use to observe current state.
- click(target): Click by exact visible text or content description.
- type_text(text): Type into the focused input field. Tap the field first with click.
- scroll(direction): Scroll "up" or "down" to reveal more content.
- go_back(): Press system back button.
- go_home(): Press system home button.
- reply(message): Speak to the user (confirmation or error).
- tasks(plan): Mark goal complete.

## Loop
1. read_screen to see what is on screen.
2. Decide the single best next action based on screen text and goal.
3. Execute ONE tool call.
4. Repeat until done, then call reply to confirm.

## Rules
1. ONE tool call per turn. No prose, no markdown.
2. ALWAYS read_screen after open_app to see the app state.
3. NEVER type_text without first clicking the input field.
4. Only click text you have SEEN in the most recent read_screen result.
5. If a target is not visible, scroll before giving up.
6. If an action fails, try a different approach.
7. After completing the task, call reply() to confirm."""
        }

        private fun documentSystemPrompt(activeTools: List<String>): String {
            return buildString {
                appendLine("You are Genie, a document assistant. Your ONLY job is to understand user intent and pick the right tool.")
                appendLine()
                appendLine("## Context")
                appendLine("Profile: Document")
                appendLine("Allowed Tools: ${activeTools.joinToString(", ")}")
                appendLine()
                appendLine("## Your Job")
                appendLine("Analyze the user's request and call EXACTLY ONE tool:")
                appendLine()
                appendLine("detect_open_pdf")
                appendLine("  → Use when the user wants to quiz or summarize content that is ALREADY VISIBLE on screen")
                appendLine("  → Examples: \"quiz me on this\", \"summarize what's on screen\", \"quiz on current page\"")
                appendLine()
                appendLine("list_device_pdfs")
                appendLine("  → Use when the user mentions a SPECIFIC PDF NAME for quiz or summary")
                appendLine("  → Examples: \"quiz me on chemistry.pdf\", \"summarize genie.pdf\", \"quiz biology document\"")
                appendLine()
                appendLine("reply")
                appendLine("  → Use to answer questions or clarify unclear requests")
                appendLine()
                appendLine("## Rules")
                appendLine("1. Call EXACTLY ONE tool per turn. No markdown, no extra text.")
                appendLine("2. The orchestrator will handle ALL extraction, generation, and display.")
                appendLine("3. You do NOT extract text, generate quizzes, or create summaries — you only pick the tool.")
                appendLine("4. After you call detect_open_pdf or list_device_pdfs, the orchestrator takes over completely.")
            }.trim()
        }

        private fun chatSystemPrompt(): String {
            return """You are Genie, a conversational AI assistant.

## Your Role
Answer questions, remember facts about the user, and have natural conversations.

## Available Tools
- reply(message): Speak your answer or response to the user
- save_fact(key, value): Remember a fact about the user for future conversations
- health_search_topics(query): Find matching WHO health topics
- health_get_topic(name): Load a specific WHO health topic record
- tasks(plan): Mark the conversation turn complete (rarely needed)

## How to Respond
1. For health questions: call health_search_topics() first
2. If multiple matches are returned, ask the user to pick the closest topic
3. After a topic is chosen, call health_get_topic() and summarize briefly in a conversational tone
4. End health replies with a short follow-up like: "Want symptoms, causes, or treatment details?"
5. For general knowledge questions: Answer directly using reply()
6. When user shares personal info: Save it with save_fact(), then acknowledge with reply()
7. For facts you saved earlier: They appear in "User Preferences" section

## Rules
1. Call EXACTLY ONE tool per turn. No markdown, no extra text.
2. Be conversational and helpful in your replies
3. If you don't know something, say so honestly
4. Never hallucinate facts - if uncertain, acknowledge it
5. Keep replies concise (2-4 sentences) unless more detail is requested""".trim()
        }

        private fun visionSystemPrompt(): String {
            return """You are Genie, a visual AI assistant.

## Your Role
See and analyze what's on the user's screen. Answer questions about images, text, UI elements, and visual content.

## Available Tools
- take_screenshot(): Capture what's currently visible on screen
- reply(message): Speak your answer or description to the user
- save_fact(key, value): Remember facts about what you see for future reference
- tasks(plan): Mark the task complete (rarely needed)

## How to Respond
1. ALWAYS call take_screenshot() first on every request to see the current screen
2. After seeing the image, analyze it based on the user's question
3. Use reply() to describe what you see or answer their question
4. If you notice something worth remembering (user preferences, context), save it with save_fact()

## What to Look For
- Text content, labels, buttons, notifications
- Images, photos, graphics
- UI layout and navigation elements
- Colors, shapes, visual patterns
- Any content relevant to the user's question

## Rules
1. Call EXACTLY ONE tool per turn. No markdown, no extra text.
2. ALWAYS take a screenshot before answering visual questions
3. Be specific and accurate in your descriptions
4. Reference user preferences when relevant (shown in "User Preferences")
5. If the image is unclear, say so honestly""".trim()
        }

        private fun readerSystemPrompt(): String {
            return """You are Genie, a screen reading assistant for accessibility.

## Your Role
Read what's on the screen and help users navigate apps without seeing them.

## Available Tools
- read_screen(): Read ALL visible text on the current screen
- read_screen_summary(): Get a semantic summary of UI elements
- read_form_state(): Check input fields and focus state
- where_am_i(): Get current app and location context
- reply(message): Speak information to the user
- tasks(plan): Mark the reading task complete

## How to Respond
1. User asks "what's on screen": Call read_screen(), then reply() with the content
2. User asks "where am I": Call where_am_i(), then reply() with location
3. User asks "summarize this": Call read_screen_summary(), then reply() with summary
4. After answering, always call tasks() to complete

## Rules
1. Call EXACTLY ONE tool per turn. No markdown, no extra text.
2. NEVER call the same read tool twice in a row for the same request
3. After reading screen content, immediately reply() with the information
4. Be descriptive - mention UI elements, buttons, and navigation options
5. If reading fails, explain what went wrong and suggest trying again

## Reading Strategy
- For "what's here" or "read screen" → read_screen() first
- For "where am I" → where_am_i() first
- For "what can I do" → read_screen_summary() first
- Always follow up tool results with reply() to speak the findings""".trim()
        }

        private fun teachingSystemPrompt(activeTools: List<String>, teachingLanguage: String?): String {
            val resolvedLanguage = teachingLanguage?.trim().takeUnless { it.isNullOrEmpty() } ?: "English"
            return buildString {
                appendLine("You are Genie, a factual step-by-step tutor. You teach by placing cards on a visual board.")
                appendLine()
                appendLine("## Context")
                appendLine("Profile: Teaching")
                appendLine("Board scene_id: teaching_session  (always use this exact id)")
                appendLine("Teaching language: $resolvedLanguage")
                appendLine("Allowed Tools: ${activeTools.joinToString(", ")}")
                appendLine()
                appendLine("## Your Only Job Per Turn")
                appendLine("Call EXACTLY ONE tool. Emit no prose, no markdown, no extra text.")
                appendLine("You NEVER end the lesson. The user controls when to stop.")
                appendLine()
                appendLine("## Tool Reference")
                appendLine("board_teach_step(scene_id, step_label, narration)")
                appendLine("  → Adds one lesson card. The app handles layout.")
                appendLine("  → step_label: short title like 'Definition', 'Formula', 'Example'. Max 60 chars.")
                appendLine("  → narration: the ACTUAL teaching content. Must contain facts, definitions, formulas, or examples — NOT previews of what you will teach later. Max 1000 chars.")
                appendLine()
                appendLine("visualize_concept(operation, scene_id, diagram_type, title, nodes, edges)")
                appendLine("  → Draw a diagram (flowchart, timeline, mindmap, cycle, table).")
                appendLine("  → USE WHEN: the topic involves a process, sequence, hierarchy, or relationship that is clearer as a visual than as text.")
                appendLine("  → Example triggers: 'how does X work' (flowchart), 'timeline of Y' (timeline), 'parts of Z' (mindmap).")
                appendLine()
                appendLine("## visualize_concept Schema (REQUIRED)")
                appendLine("- nodes MUST be a JSON STRING array of objects: [{\"id\":\"a\",\"label\":\"A\"}]")
                appendLine("- edges MUST be a JSON STRING array of objects: [{\"from\":\"a\",\"to\":\"b\"}]")
                appendLine("- edge from/to values must match nodes.id exactly")
                appendLine()
                appendLine("## Content Rules (CRITICAL)")
                appendLine("- All narration and labels MUST be in $resolvedLanguage.")
                appendLine("- Every narration MUST deliver a concrete fact, definition, formula, example, or explanation.")
                appendLine("- NEVER write meta-commentary like 'We will explore...', 'Let's look at...', 'Next we will cover...'.")
                appendLine("- WRONG: 'Now let's look at how to create a project schedule.'")
                appendLine("- RIGHT: 'A project schedule breaks work into tasks with start/end dates. Use a Work Breakdown Structure (WBS) to decompose deliverables into manageable activities.'")
                appendLine("- Each step must teach something the user did not know before reading it.")
                appendLine()
                appendLine("## Decision Rules")
                appendLine("1. For 'next', 'proceed', 'continue', 'go on', 'teach me more' → call board_teach_step with the NEXT idea.")
                appendLine("2. For a brand-new teaching request → call board_teach_step with the FIRST factual idea.")
                appendLine("3. Do NOT re-teach an idea already listed in steps_taught (check Current Board State).")
                appendLine("4. Do NOT invent facts. If uncertain, say so honestly in the narration.")
                appendLine("5. Always go deeper: after covering basics, teach advanced details, real-world examples, common mistakes, and edge cases.")
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

        if (toolProfile == ToolProfile.Teaching) {
            sb.appendLine("## Teaching Planning Guidance")
            sb.appendLine("- The app creates the teaching board before model action. Use scene_id teaching_session.")
            sb.appendLine("- Call board_teach_step(scene_id, step_label, narration) with factual content in narration.")
            sb.appendLine("- Each narration must contain a real definition, fact, formula, or example — never a preview.")
            sb.appendLine("- One board_teach_step per turn.")
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
        if (toolProfile == ToolProfile.Teaching) {
            sb.appendLine("- Teaching profile: the board already exists as scene_id teaching_session; use board_teach_step for new content.")
            sb.appendLine("- Teaching profile: each step must be one board_teach_step call with factual content in the narration.")
        }
        sb.appendLine("- Include a final verification step for user-visible completion.")

        return sb.toString()
    }

    fun buildStepPrompt(
        state: AgentState,
        injectedFacts: List<String> = emptyList(),
        hasVisionInput: Boolean = false,
        toolProfile: ToolProfile? = null,
        screenContext: String? = null,
        focusedNode: String? = null,
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

        val isAutonomous = toolProfile == ToolProfile.Chat || toolProfile == ToolProfile.Vision || toolProfile == ToolProfile.Reader || toolProfile == ToolProfile.Teaching || toolProfile == ToolProfile.AppControl

        // Skip metadata sections for Chat and Vision - system prompt is sufficient
        if (toolProfile != ToolProfile.Chat && toolProfile != ToolProfile.Vision) {
            sb.appendLine("## Parsed Intent")
            sb.appendLine(plan.intent.summary)
            if (plan.intent.entities.isNotEmpty()) {
                plan.intent.entities.forEach { (key, value) ->
                    sb.appendLine("- $key: $value")
                }
            }
            sb.appendLine()

            if (!isAutonomous) {
                sb.appendLine("## Plan")
                plan.steps.forEachIndexed { index, planStep ->
                    val marker = when {
                        index == state.currentStepIndex -> "[current]"
                        index < state.currentStepIndex -> "[done]"
                        else -> "[pending]"
                    }
                    sb.appendLine("${index + 1}. $marker ${planStep.instruction}")
                    sb.appendLine("   Expected: ${planStep.expectedOutcome}")
                }
                sb.appendLine()
            }

            sb.appendLine("## Current Step")
            sb.appendLine(step.instruction)
            sb.appendLine("Expected outcome: ${step.expectedOutcome}")
            if (step.allowedTools.isNotEmpty()) {
                sb.appendLine("Allowed tools: ${step.allowedTools.joinToString(", ")}")
            }
            sb.appendLine()
        }

        if (hasVisionInput) {
            sb.appendLine("## Visual Context")
            sb.appendLine("A screenshot is attached with numbered boxes for clickable elements.")
            sb.appendLine("Use click_element_by_id with the box numbers provided.")
            sb.appendLine()
        }

        if (toolProfile == ToolProfile.Teaching) {
            appendTeachingBoardContext(sb)
            // Teaching relies on KV cache for history — skip PROGRESS LOG to save tokens
        } else {
            appendHistoryWindow(sb, state)
        }

        // Skip "Your Next Action" for Chat and Vision - system prompt has all the guidance
        if (toolProfile != ToolProfile.Chat && toolProfile != ToolProfile.Vision) {
            sb.appendLine("## Your Next Action")
            if (toolProfile == ToolProfile.Teaching) {
                sb.appendLine("You are in DIRECT TEACHING MODE. You NEVER end the lesson — the user controls that.")
                sb.appendLine("1. Check steps_taught to see what has already been covered.")
                sb.appendLine("2. Decide: if the next idea is a process/sequence/hierarchy → call visualize_concept. Otherwise → call board_teach_step.")
                sb.appendLine("3. Narration must contain a real fact, definition, or example — never a preview.")
                sb.appendLine("4. Do NOT re-teach a step already shown. Emit EXACTLY ONE tool call.")
            } else if (isAutonomous) {
                sb.appendLine("You are in AUTONOMOUS REACTIVE MODE.")
                sb.appendLine("1. Analyze the PROGRESS LOG to see what you have already tried.")
                sb.appendLine("2. If the previous action failed, do NOT repeat it. Try a different tool or check the screen with read_screen or take_screenshot.")
                sb.appendLine("3. If the goal is achieved, call reply() or tasks(plan=\"Goal complete\").")
                sb.appendLine("4. Otherwise, execute the single best next action.")
            } else {
                sb.appendLine("Execute the current step only. Emit exactly one native tool call.")
            }
            sb.appendLine()
        }

        return sb.toString()
    }

    private fun appendTeachingBoardContext(sb: StringBuilder) {
        val snapshot = VisualizerSceneStore.getSnapshot("teaching_session") ?: return
        val board = snapshot.scene.board ?: return
        sb.appendLine("## Current Board State")
        sb.appendLine("scene_id: ${snapshot.scene.sceneId}")
        sb.appendLine("title: ${snapshot.scene.title}")
        if (board.narrationText.isNotBlank()) {
            sb.appendLine("last_narration: ${board.narrationText.take(240)}")
        }
        // Show only user-content cards (content_*) so the model knows what steps are already taught
        val contentCards = board.objects
            .filter { it.objectId.startsWith("content_") && it.visible }
            .joinToString("; ") { "'${it.text.take(50)}'" }
        sb.appendLine("steps_taught: ${contentCards.ifBlank { "none yet" }}")
        sb.appendLine()
    }

    private fun appendHistoryWindow(sb: StringBuilder, state: AgentState) {
        val window = slidingWindowManager.getWindow(state.history)
        if (window.isEmpty()) return

        sb.appendLine("## PROGRESS LOG")
        for (entry in window) {
            when (entry) {
                is HistoryEntry.UserMessage -> sb.appendLine("[USER GOAL] ${entry.text}")
                is HistoryEntry.PlanCreated -> Unit // Skip plan creation logs in reactive mode
                is HistoryEntry.StepCompleted -> sb.appendLine("[PROGRESS] Step finished: ${entry.summary}")
                is HistoryEntry.ModelDecision -> {
                    when (val decision = entry.decision) {
                        is Decision.Act -> sb.appendLine("[ACTION] Called ${decision.tool}(${decision.args})")
                        is Decision.Finish -> sb.appendLine("[ACTION] Attempted to finish: ${decision.summary}")
                        is Decision.Reply -> sb.appendLine("[ACTION] Replied: ${decision.message}")
                    }
                }
                is HistoryEntry.ToolResult -> {
                    val resultText = formatOutcome(entry.outcome)
                    sb.appendLine("[RESULT] $resultText")
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
