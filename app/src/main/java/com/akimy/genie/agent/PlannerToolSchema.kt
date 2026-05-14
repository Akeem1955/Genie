@file:Suppress(
    "unused",
    "FunctionName",
    "LocalVariableName",
    "SpellCheckingInspection",
)

package com.akimy.genie.agent

import com.akimy.genie.tools.ToolProfile
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool

private const val BOARD_OBJECT_TYPES_DESCRIPTION = "title, text, box, card, circle, line, arrow, code, or path"
private const val BOARD_OBJECT_TYPE_PARAM_DESCRIPTION =
    "The object type: title, text, box, card, circle, line, arrow, code, or path."

/**
 * Schema-only tool declarations exposed to LiteRT-LM for planning.
 *
 * These methods are never expected to execute because Genie keeps
 * automatic tool calling disabled and manually intercepts each tool call.
 */
class PlannerToolSchema : ToolSet {

    @Tool(description = "Click on a UI element by its visible text or content description.")
    fun click(@ToolParam(description = "The exact text or content description of the target element.") target: String) =
        declared()

    @Tool(description = "Click a UI element based on its numeric ID shown on the annotated screenshot.")
    fun click_element_by_id(@ToolParam(description = "The integer ID of the bounding box to click (e.g., 12).") id: String) =
        declared()

    @Tool(description = "Tap a screen coordinate using normalized Android coordinates. Use after take_screenshot when a control is visible but not accessible by text.")
    fun tap_at(
        @ToolParam(description = "Normalized x coordinate from 0 to 1000. 0 is the left edge, 1000 is the right edge.") x: String,
        @ToolParam(description = "Normalized y coordinate from 0 to 1000. 0 is the top edge, 1000 is the bottom edge.") y: String,
    ) = declared()

    @Tool(description = "Type text into the currently focused input field.")
    fun type_text(@ToolParam(description = "The text to type into the focused field.") text: String) =
        declared()

    @Tool(description = "Swipe in a direction.")
    fun swipe(@ToolParam(description = "The swipe direction: up, down, left, or right.") direction: String) =
        declared()

    @Tool(description = "Read all visible text on the current screen.")
    fun read_screen() = declared()

    @Tool(description = "Capture a screenshot of the current screen.")
    fun take_screenshot() = declared()

    @Tool(description = "Open an installed app by name.")
    fun open_app(@ToolParam(description = "The visible app name to open.") name: String) =
        declared()

    @Tool(description = "Press the system back button.")
    fun go_back() = declared()

    @Tool(description = "Press the system home button.")
    fun go_home() = declared()

    @Tool(description = "Move accessibility focus to the next navigable element.")
    fun focus_next() = declared()

    @Tool(description = "Move accessibility focus to the previous navigable element.")
    fun focus_previous() = declared()

    @Tool(description = "Move accessibility focus to the first navigable element on screen.")
    fun focus_first() = declared()

    @Tool(description = "Move accessibility focus to the first element matching visible text.")
    fun focus_by_text(@ToolParam(description = "The visible text or content description to focus.") target: String) =
        declared()

    @Tool(description = "Move accessibility focus to the first element matching a semantic role such as button, heading, switch, text_field, tab, or dialog.")
    fun focus_by_role(@ToolParam(description = "The semantic role to focus.") role: String) =
        declared()

    @Tool(description = "Activate the currently accessibility-focused element.")
    fun activate_focused() = declared()

    @Tool(description = "Scroll the current list or container forward using native accessibility actions.")
    fun scroll_forward() = declared()

    @Tool(description = "Scroll the current list or container backward using native accessibility actions.")
    fun scroll_backward() = declared()

    @Tool(description = "Read a compact semantic summary of the current screen.")
    fun read_screen_summary() = declared()

    @Tool(description = "Read recent meaningful accessibility events such as focus changes, dialogs, and notifications.")
    fun read_recent_events(@ToolParam(description = "How many recent events to read, usually 1 to 10.") limit: String = "5") =
        declared()

    @Tool(description = "Read a full orientation summary of the current screen, current focus, and major landmarks.")
    fun where_am_i() = declared()

    @Tool(description = "Read semantic context around the currently focused item.")
    fun read_nearby_context() = declared()

    @Tool(description = "Read the native accessibility actions available on the current focused item or nearby actionable controls.")
    fun what_can_i_do_here() = declared()

    @Tool(description = "Read meaningful screen changes detected since the last awareness update, such as focus moves, dialogs, or keyboard state changes.")
    fun read_screen_changes() = declared()

    @Tool(description = "Turn continuous reader narration on so Genie automatically speaks important UI changes.")
    fun enable_continuous_reader() = declared()

    @Tool(description = "Turn continuous reader narration off.")
    fun disable_continuous_reader() = declared()

    @Tool(description = "Read whether continuous reader narration is currently on or off.")
    fun read_continuous_reader_status() = declared()

    @Tool(description = "Repeat the last spoken continuous-reader hint.")
    fun repeat_last_narration() = declared()

    @Tool(description = "Read Genie's learned shortcuts, landmarks, and remembered hints for the current screen.")
    fun read_screen_map() = declared()

    @Tool(description = "Save a user-provided note for the current screen so Genie remembers it later.")
    fun save_screen_hint(@ToolParam(description = "The note Genie should remember for the current screen.") note: String) =
        declared()

    @Tool(description = "Read the currently visible dialog and its contents.")
    fun read_dialog() = declared()

    @Tool(description = "Read recent notification summaries.")
    fun read_notifications(@ToolParam(description = "How many recent notifications to read, usually 1 to 10.") limit: String = "5") =
        declared()

    @Tool(description = "Read visible form fields, hints, validation errors, and focus state.")
    fun read_form_state() = declared()

    @Tool(description = "Save a user preference or fact to long-term memory.")
    fun save_fact(
        @ToolParam(description = "The fact key to save.") key: String,
        @ToolParam(description = "The fact value to save.") value: String,
    ) = declared()

    @Tool(description = "Retrieve a previously saved user preference or fact.")
    fun retrieve_fact(@ToolParam(description = "The fact key to retrieve.") key: String) =
        declared()

    @Tool(description = "Scroll the current screen up or down.")
    fun scroll(@ToolParam(description = "The scroll direction: up or down.") direction: String) =
        declared()

    @Tool(description = "Read text from a local PDF file page range.")
    fun read_pdf_page_range(
        @ToolParam(description = "The absolute path to the local PDF file.") file_path: String,
        @ToolParam(description = "The 1-based start page number.") start_page: String,
        @ToolParam(description = "The 1-based end page number.") end_page: String,
        @ToolParam(description = "Optional maximum number of characters to return.") max_chars: String = "",
    ) = declared()

    @Tool(description = "Draw a diagram (flowchart, timeline, mindmap, cycle, or table) to visualize a concept.")
    fun visualize_concept(
        @ToolParam(description = "The operation: create, update, highlight, clear, or export.") operation: String,
        @ToolParam(description = "The scene identifier using only letters, numbers, underscore, and hyphen.") scene_id: String,
        @ToolParam(description = "Diagram type for create: flowchart, cycle, timeline, mindmap, or table.") diagram_type: String = "",
        @ToolParam(description = "Optional scene title.") title: String = "",
        @ToolParam(description = "Nodes JSON array string, e.g. [{\"id\":\"A\",\"label\":\"Step 1\"}].") nodes: String = "",
        @ToolParam(description = "Edges JSON array string, e.g. [{\"from\":\"A\",\"to\":\"B\"}].") edges: String = "",
        @ToolParam(description = "Optional comma-separated focus node ids.") focus_node_ids: String = "",
    ) = declared()

    @Tool(description = "Focus one node in an existing visualizer concept scene.")
    fun visualize_focus_node(
        @ToolParam(description = "The visualizer scene identifier.") scene_id: String,
        @ToolParam(description = "The node id to focus.") node_id: String,
    ) = declared()

    @Tool(description = "Create or replace an interactive teaching board scene with lesson steps, whiteboard objects, and synchronized narration.")
    fun teach_with_board(
        @ToolParam(description = "The board scene identifier using only letters, numbers, underscore, and hyphen.") scene_id: String,
        @ToolParam(description = "The teaching board title shown at the top of the visualizer.") title: String,
        @ToolParam(description = "Optional board theme such as dark_classroom, blueprint, neon_lab, or light.") board_theme: String = "",
        @ToolParam(description = "A JSON array string of board objects. Each object can include objectId, objectType, text, position, size, style, stepId, and animation.") objects: String = "",
        @ToolParam(description = "A JSON array string of ordered lesson steps. Each step can include stepId, title, and narration.") steps: String = "",
        @ToolParam(description = "Optional initial narration text for the current board state.") narration_text: String = "",
    ) = declared()

    @Tool(description = "Clear a teaching board scene so a lesson can restart cleanly.")
    fun board_clear(@ToolParam(description = "The board scene identifier.") scene_id: String) = declared()

    @Tool(description = "Add one object to an interactive teaching board scene.")
    fun board_add_object(
        @ToolParam(description = "The board scene identifier.") scene_id: String,
        @ToolParam(description = "The unique object identifier.") object_id: String,
        @ToolParam(description = BOARD_OBJECT_TYPE_PARAM_DESCRIPTION) object_type: String,
        @ToolParam(description = "The x position in canvas pixels.") x: String,
        @ToolParam(description = "The y position in canvas pixels.") y: String,
        @ToolParam(description = "Optional display text for the object.") text: String = "",
        @ToolParam(description = "Optional width in canvas pixels.") width: String = "",
        @ToolParam(description = "Optional height in canvas pixels.") height: String = "",
        @ToolParam(description = "Optional style JSON string controlling fillColor, strokeColor, textColor, strokeWidth, cornerRadius, textSize, textAlign, dashed, and alpha.") style: String = "",
        @ToolParam(description = "Optional lesson step id that reveals this object.") step_id: String = "",
        @ToolParam(description = "Optional animation hint such as reveal, pulse, trace, or drift.") animation: String = "",
        @ToolParam(description = "Optional SVG-like path data for path objects, using commands like M, L, Q, C, A, or Z.") path_data: String = "",
    ) = declared()

    @Tool(description = "Advance the teaching board to the next lesson step.")
    fun board_next_step(@ToolParam(description = "The board scene identifier.") scene_id: String) = declared()

    @Tool(description = "Move the teaching board back to the previous lesson step.")
    fun board_prev_step(@ToolParam(description = "The board scene identifier.") scene_id: String) = declared()

    @Tool(description = "Replay the current teaching board step so its visual emphasis and narration can be repeated.")
    fun board_replay_step(@ToolParam(description = "The board scene identifier.") scene_id: String) = declared()

    @Tool(description = "Set or update the narration text for the current teaching board state.")
    fun board_set_narration(
        @ToolParam(description = "The board scene identifier.") scene_id: String,
        @ToolParam(description = "The narration text Genie should associate with the current board state.") narration_text: String,
    ) = declared()

    @Tool(description = "Speak a message to the user through text-to-speech. Use this to answer questions, explain what you found, or give feedback.")
    fun reply(@ToolParam(description = "The message to speak aloud to the user.") message: String) =
        declared()

    @Tool(description = "Submit a plan payload or mark completion. During plan execution this completes the current plan step; when no plan step is active this finishes the whole goal.")
    fun tasks(@ToolParam(description = "A brief completion note, or JSON when the prompt explicitly asks for a JSON plan.") plan: String) =
        declared()

    private fun declared(): Map<String, String> = mapOf("status" to "schema_only")
}

class SeeAndTapPlannerToolSchema : ToolSet {
    @Tool(description = "Capture a screenshot of the current screen so the next turn can ground the tap in the image.")
    fun take_screenshot() = declared()

    @Tool(description = "Click a UI element based on its numeric ID shown on the annotated screenshot.")
    fun click_element_by_id(@ToolParam(description = "The integer ID of the bounding box to click (e.g., 12).") id: String) =
        declared()

    @Tool(description = "Speak a message to the user through text-to-speech. Use this to answer questions, explain what you found, or give feedback.")
    fun reply(@ToolParam(description = "The message to speak aloud to the user.") message: String) =
        declared()

    @Tool(description = "Submit a plan payload or mark completion. During plan execution this completes the current plan step; when no plan step is active this finishes the whole goal.")
    fun tasks(@ToolParam(description = "A brief completion note, or JSON when the prompt explicitly asks for a JSON plan.") plan: String) =
        declared()

    private fun declared(): Map<String, String> = mapOf("status" to "schema_only")
}

class ReaderPlannerToolSchema : ToolSet {
    @Tool(description = "Read all visible text on the current screen.")
    fun read_screen() = declared()

    @Tool(description = "Read a compact semantic summary of the current screen.")
    fun read_screen_summary() = declared()

    @Tool(description = "Read recent notification summaries.")
    fun read_notifications(@ToolParam(description = "How many recent notifications to read, usually 1 to 10.") limit: String = "5") =
        declared()

    @Tool(description = "Speak a message to the user through text-to-speech. Use this to answer questions, explain what you found, or give feedback.")
    fun reply(@ToolParam(description = "The message to speak aloud to the user.") message: String) =
        declared()

    @Tool(description = "Submit a plan payload or mark completion. During plan execution this completes the current plan step; when no plan step is active this finishes the whole goal.")
    fun tasks(@ToolParam(description = "A brief completion note, or JSON when the prompt explicitly asks for a JSON plan.") plan: String) =
        declared()

    private fun declared(): Map<String, String> = mapOf("status" to "schema_only")
}

class TeachingPlannerToolSchema : ToolSet {

    @Tool(
        description = "Teach one lesson step. Narration must contain a concrete fact, definition, formula, or worked example — never meta-commentary."
    )
    fun board_teach_step(
        @ToolParam(description = "The board scene identifier. Always teaching_session.") scene_id: String,
        @ToolParam(description = "A short title for this step, e.g. 'Definition' or 'Formula'.") step_label: String,
        @ToolParam(description = "The actual teaching content: a fact, definition, formula, or example. Never write 'we will explore' or 'let us look at'. Max 1000 chars.") narration: String,
    ) = declared()

    @Tool(description = "Draw a diagram (flowchart, timeline, mindmap, cycle, or table) to visualize a concept.")
    fun visualize_concept(
        @ToolParam(description = "The operation: create, update, highlight, clear, or export.") operation: String,
        @ToolParam(description = "The scene identifier. Use teaching_session for lessons.") scene_id: String,
        @ToolParam(description = "Diagram type for create: flowchart, cycle, timeline, mindmap, or table.") diagram_type: String = "",
        @ToolParam(description = "Optional scene title.") title: String = "",
        @ToolParam(description = "Nodes JSON array string, e.g. [{\"id\":\"A\",\"label\":\"Step 1\"}].") nodes: String = "",
        @ToolParam(description = "Edges JSON array string, e.g. [{\"from\":\"A\",\"to\":\"B\"}].") edges: String = "",
        @ToolParam(description = "Optional comma-separated focus node ids.") focus_node_ids: String = "",
    ) = declared()

    private fun declared(): Map<String, String> = mapOf("status" to "schema_only")
}

class VisualizeConceptOpenApiTool : OpenApiTool {
    override fun getToolDescriptionJsonString(): String = """
        {
          "name": "visualize_concept",
          "description": "Create or update a conceptual canvas scene using typed diagram nodes and edges.",
          "parameters": {
            "type": "object",
            "properties": {
              "operation": {
                "type": "string",
                "description": "The operation: create, update, highlight, clear, or export."
              },
              "scene_id": {
                "type": "string",
                "description": "The scene identifier using only letters, numbers, underscore, and hyphen."
              },
              "diagram_type": {
                "type": "string",
                "description": "Diagram type for create: flowchart, cycle, timeline, mindmap, or table."
              },
              "title": {
                "type": "string",
                "description": "Optional scene title."
              },
              "nodes": {
                "type": "array",
                "description": "Concept nodes to render.",
                "items": {
                  "type": "object",
                  "properties": {
                    "id": {
                      "type": "string",
                      "description": "Stable node id."
                    },
                    "label": {
                      "type": "string",
                      "description": "Short visible node label."
                    },
                    "kind": {
                      "type": "string",
                      "description": "Optional node kind such as concept, input, process, output, or example."
                    }
                  },
                  "required": ["id", "label"]
                }
              },
              "edges": {
                "type": "array",
                "description": "Directed relationships between nodes.",
                "items": {
                  "type": "object",
                  "properties": {
                    "from": {
                      "type": "string",
                      "description": "Source node id."
                    },
                    "to": {
                      "type": "string",
                      "description": "Target node id."
                    },
                    "label": {
                      "type": "string",
                      "description": "Optional short edge label."
                    },
                    "style": {
                      "type": "string",
                      "description": "Optional edge style."
                    }
                  },
                  "required": ["from", "to"]
                }
              }
            },
            "required": ["operation", "scene_id"]
          }
        }
    """.trimIndent()

    override fun execute(params: String): String = """{"status":"schema_only"}"""
}

private fun boardObjectItemSchemaJson(): String = """
    {
      "type": "object",
      "properties": {
        "object_id": {
          "type": "string",
          "description": "Unique object id, for example lesson_title or card_1."
        },
        "object_type": {
          "type": "string",
          "description": "One of $BOARD_OBJECT_TYPES_DESCRIPTION."
        },
        "x": {
          "type": "number",
          "description": "X position in canvas pixels."
        },
        "y": {
          "type": "number",
          "description": "Y position in canvas pixels."
        },
        "width": {
          "type": "number",
          "description": "Optional width in canvas pixels."
        },
        "height": {
          "type": "number",
          "description": "Optional height in canvas pixels."
        },
        "text": {
          "type": "string",
          "description": "Short display text for the object."
        },
        "step_id": {
          "type": "string",
          "description": "Optional lesson step id that reveals this object."
        },
        "animation": {
          "type": "string",
          "description": "Optional animation hint such as reveal, pulse, trace, or drift."
        },
        "path_data": {
          "type": "string",
          "description": "Optional SVG-like path data for path objects."
        }
      },
      "required": ["object_id", "object_type", "x", "y"]
    }
""".trimIndent()

class TeachWithBoardOpenApiTool : OpenApiTool {
    override fun getToolDescriptionJsonString(): String = """
        {
          "name": "teach_with_board",
          "description": "Create or replace an interactive teaching board scene with typed lesson steps, whiteboard objects, and synchronized narration.",
          "parameters": {
            "type": "object",
            "properties": {
              "scene_id": {
                "type": "string",
                "description": "The board scene identifier using only letters, numbers, underscore, and hyphen."
              },
              "title": {
                "type": "string",
                "description": "The teaching board title shown at the top of the visualizer."
              },
              "board_theme": {
                "type": "string",
                "description": "Optional board theme such as dark_classroom, blueprint, neon_lab, or light."
              },
              "objects": {
                "type": "array",
                "description": "Visual board objects. Keep labels short. Omit this or pass an empty array when the app should auto-create cards from steps.",
                "items": ${boardObjectItemSchemaJson()}
              },
              "steps": {
                "type": "array",
                "description": "Ordered lesson steps. Use one concise idea per step.",
                "items": {
                  "type": "object",
                  "properties": {
                    "step_id": {
                      "type": "string",
                      "description": "Stable step id such as step_1."
                    },
                    "title": {
                      "type": "string",
                      "description": "Short step title."
                    },
                    "narration": {
                      "type": "string",
                      "description": "Concise narration for this step."
                    }
                  },
                  "required": ["step_id", "title", "narration"]
                }
              },
              "narration_text": {
                "type": "string",
                "description": "Optional initial narration text for the current board state."
              }
            },
            "required": ["scene_id", "title", "steps"]
          }
        }
    """.trimIndent()

    override fun execute(params: String): String = """{"status":"schema_only"}"""
}

class DocumentPlannerToolSchema : ToolSet {
    @Tool(description = "Capture and analyze a PDF currently visible on screen. Use when the user wants to quiz or summarize content that is already open.")
    fun detect_open_pdf() = declared()

    @Tool(description = "List PDF files on the device. Use when the user mentions a specific PDF name for quiz or summary.")
    fun list_device_pdfs() = declared()

    @Tool(description = "Speak a message to the user through text-to-speech. Use this to answer questions, explain what you found, or give feedback.")
    fun reply(@ToolParam(description = "The message to speak aloud to the user.") message: String) =
        declared()

    @Tool(description = "Submit a plan payload or mark completion. During plan execution this completes the current plan step; when no plan step is active this finishes the whole goal.")
    fun tasks(@ToolParam(description = "A brief completion note, or JSON when the prompt explicitly asks for a JSON plan.") plan: String) =
        declared()

    private fun declared(): Map<String, String> = mapOf("status" to "schema_only")
}

class ChatPlannerToolSchema : ToolSet {
    @Tool(description = "Speak a message to the user through text-to-speech. Use this to answer questions, explain what you found, or give feedback.")
    fun reply(@ToolParam(description = "The message to speak aloud to the user.") message: String) = declared()

    @Tool(description = "Save a durable fact or preference about the user into long-term memory so you can recall it later.")
    fun save_fact(
        @ToolParam(description = "A stable key representing the fact category (e.g. 'dietary_preference', 'name').") key: String,
        @ToolParam(description = "The exact value or text to remember.") value: String,
    ) = declared()

    @Tool(description = "Submit a plan payload or mark completion. During plan execution this completes the current plan step; when no plan step is active this finishes the whole goal.")
    fun tasks(@ToolParam(description = "A brief completion note, or JSON when the prompt explicitly asks for a JSON plan.") plan: String) = declared()

    private fun declared(): Map<String, String> = mapOf("status" to "schema_only")
}

class AppControlPlannerToolSchema : ToolSet {
    @Tool(description = "Open an installed app by name.")
    fun open_app(@ToolParam(description = "The visible app name to open (e.g. WhatsApp, Spotify, Gmail, YouTube).") name: String) =
        declared()

    @Tool(description = "Click a UI element by its exact visible text or content description.")
    fun click(@ToolParam(description = "The exact text or content description of the element.") target: String) =
        declared()

    @Tool(description = "Type text into the currently focused input field. The field must already be focused (tapped).")
    fun type_text(@ToolParam(description = "The text to type.") text: String) =
        declared()

    @Tool(description = "Read all visible text on the current screen. Use to verify state or find elements.")
    fun read_screen() = declared()

    @Tool(description = "Scroll the screen up or down to reveal more content.")
    fun scroll(@ToolParam(description = "The scroll direction: up or down.") direction: String) =
        declared()

    @Tool(description = "Press the system back button.")
    fun go_back() = declared()

    @Tool(description = "Press the system home button.")
    fun go_home() = declared()

    @Tool(description = "Speak a message to the user. Use to confirm completion or ask for clarification.")
    fun reply(@ToolParam(description = "The message to speak aloud.") message: String) =
        declared()

    @Tool(description = "Mark the current step or goal as complete.")
    fun tasks(@ToolParam(description = "A brief completion note.") plan: String) =
        declared()

    private fun declared(): Map<String, String> = mapOf("status" to "schema_only")
}

fun geniePlannerToolProviders(profile: ToolProfile = ToolProfile.DEFAULT): List<ToolProvider> {
    return when (profile) {
        ToolProfile.SeeAndTap -> listOf(tool(SeeAndTapPlannerToolSchema()))
        ToolProfile.Reader -> listOf(tool(ReaderPlannerToolSchema()))
        ToolProfile.Teaching -> listOf(tool(TeachingPlannerToolSchema()))
        ToolProfile.Document -> listOf(tool(DocumentPlannerToolSchema()))
        ToolProfile.Chat -> listOf(tool(ChatPlannerToolSchema()))
        ToolProfile.Scribe -> emptyList()
        ToolProfile.Health -> emptyList()
        ToolProfile.AppControl -> listOf(tool(AppControlPlannerToolSchema()))
    }
}
