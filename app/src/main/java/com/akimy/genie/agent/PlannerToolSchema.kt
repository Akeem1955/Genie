package com.akimy.genie.agent

import com.akimy.genie.tools.ToolProfile
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool

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

    @Tool(description = "Read the currently accessibility-focused element with role and state.")
    fun read_focused() = declared()

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

    @Tool(description = "Create or update a conceptual canvas scene using a structured diagram payload.")
    fun visualize_concept(
        @ToolParam(description = "The scene operation: create_scene, update_scene, highlight, clear_scene, or export_scene.") operation: String,
        @ToolParam(description = "The scene identifier using only letters, numbers, underscore, and hyphen.") scene_id: String,
        @ToolParam(description = "The diagram type for create_scene: flowchart, cycle, timeline, mindmap, or table.") diagram_type: String = "",
        @ToolParam(description = "Optional scene title.") title: String = "",
        @ToolParam(description = "Optional nodes JSON payload string.") nodes: String = "",
        @ToolParam(description = "Optional edges JSON payload string.") edges: String = "",
        @ToolParam(description = "Optional comma-separated focus node ids.") focus_node_ids: String = "",
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

    @Tool(description = "Add one object to an interactive teaching board scene.")
    fun board_add_object(
        @ToolParam(description = "The board scene identifier.") scene_id: String,
        @ToolParam(description = "The unique object identifier.") object_id: String,
        @ToolParam(description = "The object type: title, text, box, card, circle, line, arrow, or code.") object_type: String,
        @ToolParam(description = "The x position in canvas pixels.") x: String,
        @ToolParam(description = "The y position in canvas pixels.") y: String,
        @ToolParam(description = "Optional display text for the object.") text: String = "",
        @ToolParam(description = "Optional width in canvas pixels.") width: String = "",
        @ToolParam(description = "Optional height in canvas pixels.") height: String = "",
        @ToolParam(description = "Optional style JSON string controlling fillColor, strokeColor, textColor, strokeWidth, cornerRadius, textSize, textAlign, dashed, and alpha.") style: String = "",
        @ToolParam(description = "Optional lesson step id that reveals this object.") step_id: String = "",
        @ToolParam(description = "Optional animation hint such as reveal, pulse, trace, or drift.") animation: String = "",
    ) = declared()

    @Tool(description = "Update an existing teaching board object.")
    fun board_update_object(
        @ToolParam(description = "The board scene identifier.") scene_id: String,
        @ToolParam(description = "The existing object identifier.") object_id: String,
        @ToolParam(description = "Optional replacement object type.") object_type: String = "",
        @ToolParam(description = "Optional replacement x position in canvas pixels.") x: String = "",
        @ToolParam(description = "Optional replacement y position in canvas pixels.") y: String = "",
        @ToolParam(description = "Optional replacement text.") text: String = "",
        @ToolParam(description = "Optional replacement width in canvas pixels.") width: String = "",
        @ToolParam(description = "Optional replacement height in canvas pixels.") height: String = "",
        @ToolParam(description = "Optional replacement style JSON string.") style: String = "",
        @ToolParam(description = "Optional replacement step id. Use __clear__ to remove step assignment.") step_id: String = "",
        @ToolParam(description = "Optional replacement animation hint. Use __clear__ to remove animation.") animation: String = "",
    ) = declared()

    @Tool(description = "Remove one object from the teaching board scene.")
    fun board_remove_object(
        @ToolParam(description = "The board scene identifier.") scene_id: String,
        @ToolParam(description = "The object identifier to remove.") object_id: String,
    ) = declared()

    @Tool(description = "Focus one object on the teaching board so the lesson visually emphasizes it.")
    fun board_focus_object(
        @ToolParam(description = "The board scene identifier.") scene_id: String,
        @ToolParam(description = "The object identifier to focus.") object_id: String,
    ) = declared()

    @Tool(description = "Jump the teaching board to a named lesson step and reveal that stage of the lesson.")
    fun board_reveal_step(
        @ToolParam(description = "The board scene identifier.") scene_id: String,
        @ToolParam(description = "The lesson step identifier to reveal.") step_id: String,
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

    @Tool(description = "Start a standalone screen annotation session for the current screen.")
    fun annotate_scene(
        @ToolParam(description = "The annotation session identifier using only letters, numbers, underscore, and hyphen.") session_id: String,
        @ToolParam(description = "Optional annotation title shown in the canvas visualizer.") title: String = "",
    ) = declared()

    @Tool(description = "Add a highlighted annotation box to the active annotation session.")
    fun annotation_add_box(
        @ToolParam(description = "The annotation session identifier.") session_id: String,
        @ToolParam(description = "The unique operation identifier.") op_id: String,
        @ToolParam(description = "Delay in milliseconds before this operation is shown.") delay_ms: String = "0",
        @ToolParam(description = "Box anchor x coordinate, normalized (0-1 or 0-1000) or pixels.") x: String,
        @ToolParam(description = "Box anchor y coordinate, normalized (0-1 or 0-1000) or pixels.") y: String,
        @ToolParam(description = "Box width, normalized (0-1 or 0-1000) or pixels.") width: String = "",
        @ToolParam(description = "Box height, normalized (0-1 or 0-1000) or pixels.") height: String = "",
        @ToolParam(description = "Optional box far edge x coordinate as x2 for convenience.") x2: String = "",
        @ToolParam(description = "Optional box far edge y coordinate as y2 for convenience.") y2: String = "",
        @ToolParam(description = "Optional label text for this box.") label: String = "",
        @ToolParam(description = "Optional style JSON controlling fillColor, strokeColor, textColor, strokeWidth, cornerRadius, textAlign, and alpha.") style: String = "",
    ) = declared()

    @Tool(description = "Add a text label annotation to the active annotation session.")
    fun annotation_add_label(
        @ToolParam(description = "The annotation session identifier.") session_id: String,
        @ToolParam(description = "The unique operation identifier.") op_id: String,
        @ToolParam(description = "Delay in milliseconds before this operation is shown.") delay_ms: String = "0",
        @ToolParam(description = "Label x coordinate, normalized (0-1 or 0-1000) or pixels.") x: String,
        @ToolParam(description = "Label y coordinate, normalized (0-1 or 0-1000) or pixels.") y: String,
        @ToolParam(description = "Label text.") text: String,
        @ToolParam(description = "Optional style JSON controlling textColor, textSize, fillColor, and textAlign.") style: String = "",
    ) = declared()

    @Tool(description = "Add a pointer annotation with an optional label to the active annotation session.")
    fun annotation_add_pointer(
        @ToolParam(description = "The annotation session identifier.") session_id: String,
        @ToolParam(description = "The unique operation identifier.") op_id: String,
        @ToolParam(description = "Delay in milliseconds before this operation is shown.") delay_ms: String = "0",
        @ToolParam(description = "Pointer anchor x coordinate, normalized (0-1 or 0-1000) or pixels.") x: String,
        @ToolParam(description = "Pointer anchor y coordinate, normalized (0-1 or 0-1000) or pixels.") y: String,
        @ToolParam(description = "Optional target x coordinate for arrow direction.") target_x: String = "",
        @ToolParam(description = "Optional target y coordinate for arrow direction.") target_y: String = "",
        @ToolParam(description = "Optional pointer label.") text: String = "",
        @ToolParam(description = "Optional style JSON controlling strokeColor, fillColor, and textColor.") style: String = "",
    ) = declared()

    @Tool(description = "Clear the active annotation session and remove all annotation overlays.")
    fun annotation_clear(
        @ToolParam(description = "The annotation session identifier.") session_id: String,
    ) = declared()

    @Tool(description = "Replay the current annotation session from the first annotation step.")
    fun annotation_replay(
        @ToolParam(description = "The annotation session identifier.") session_id: String,
    ) = declared()

    @Tool(description = "Mark completion. During plan execution this completes the current plan step; when no plan step is active this finishes the whole goal.")
    fun finish_task(@ToolParam(description = "A brief completion summary, or JSON when the prompt explicitly asks for a JSON plan.") summary: String) =
        declared()

    private fun declared(): Map<String, String> = mapOf("status" to "schema_only")
}

class MessagingPlannerToolSchema : ToolSet {
    @Tool(description = "Open an installed app by name.")
    fun open_app(@ToolParam(description = "The visible app name to open.") name: String) = declared()

    @Tool(description = "Read all visible text on the current screen.")
    fun read_screen() = declared()

    @Tool(description = "Read a compact semantic summary of the current screen.")
    fun read_screen_summary() = declared()

    @Tool(description = "Read visible form fields, hints, validation errors, and focus state.")
    fun read_form_state() = declared()

    @Tool(description = "Click on a UI element by its visible text or content description.")
    fun click(@ToolParam(description = "The exact text or content description of the target element.") target: String) =
        declared()

    @Tool(description = "Move accessibility focus to the first element matching visible text.")
    fun focus_by_text(@ToolParam(description = "The visible text or content description to focus.") target: String) =
        declared()

    @Tool(description = "Move accessibility focus to the first navigable element on screen.")
    fun focus_first() = declared()

    @Tool(description = "Move accessibility focus to the next navigable element.")
    fun focus_next() = declared()

    @Tool(description = "Activate the currently accessibility-focused element.")
    fun activate_focused() = declared()

    @Tool(description = "Type text into the currently focused input field.")
    fun type_text(@ToolParam(description = "The text to type into the focused field.") text: String) =
        declared()

    @Tool(description = "Tap a screen coordinate using normalized Android coordinates from 0 to 1000.")
    fun tap_at(
        @ToolParam(description = "Normalized x coordinate from 0 to 1000. 0 is left, 1000 is right.") x: String,
        @ToolParam(description = "Normalized y coordinate from 0 to 1000. 0 is top, 1000 is bottom.") y: String,
    ) = declared()

    @Tool(description = "Capture a screenshot of the current screen.")
    fun take_screenshot() = declared()

    @Tool(description = "Press the system back button.")
    fun go_back() = declared()

    @Tool(description = "Press the system home button.")
    fun go_home() = declared()

    @Tool(description = "Scroll the current screen up or down.")
    fun scroll(@ToolParam(description = "The scroll direction: up or down.") direction: String) = declared()

    @Tool(description = "Swipe in a direction.")
    fun swipe(@ToolParam(description = "The swipe direction: up, down, left, or right.") direction: String) = declared()

    @Tool(description = "Mark completion. During plan execution this completes the current plan step; when no plan step is active this finishes the whole goal.")
    fun finish_task(@ToolParam(description = "A brief completion summary, or JSON when the prompt explicitly asks for a JSON plan.") summary: String) =
        declared()

    private fun declared(): Map<String, String> = mapOf("status" to "schema_only")
}

class ScreenControlPlannerToolSchema : ToolSet {
    @Tool(description = "Open an installed app by name.")
    fun open_app(@ToolParam(description = "The visible app name to open.") name: String) = declared()

    @Tool(description = "Press the system back button.")
    fun go_back() = declared()

    @Tool(description = "Press the system home button.")
    fun go_home() = declared()

    @Tool(description = "Swipe in a direction.")
    fun swipe(@ToolParam(description = "The swipe direction: up, down, left, or right.") direction: String) = declared()

    @Tool(description = "Scroll the current screen up or down.")
    fun scroll(@ToolParam(description = "The scroll direction: up or down.") direction: String) = declared()

    @Tool(description = "Scroll the current list or container forward using native accessibility actions.")
    fun scroll_forward() = declared()

    @Tool(description = "Scroll the current list or container backward using native accessibility actions.")
    fun scroll_backward() = declared()

    @Tool(description = "Read all visible text on the current screen.")
    fun read_screen() = declared()

    @Tool(description = "Read a compact semantic summary of the current screen.")
    fun read_screen_summary() = declared()

    @Tool(description = "Read a full orientation summary of the current screen, current focus, and major landmarks.")
    fun where_am_i() = declared()

    @Tool(description = "Read the currently accessibility-focused element with role and state.")
    fun read_focused() = declared()

    @Tool(description = "Click on a UI element by its visible text or content description.")
    fun click(@ToolParam(description = "The exact text or content description of the target element.") target: String) =
        declared()

    @Tool(description = "Move accessibility focus to the first element matching visible text.")
    fun focus_by_text(@ToolParam(description = "The visible text or content description to focus.") target: String) =
        declared()

    @Tool(description = "Move accessibility focus to the first element matching a semantic role such as button, heading, switch, text_field, tab, or dialog.")
    fun focus_by_role(@ToolParam(description = "The semantic role to focus.") role: String) = declared()

    @Tool(description = "Move accessibility focus to the first navigable element on screen.")
    fun focus_first() = declared()

    @Tool(description = "Move accessibility focus to the next navigable element.")
    fun focus_next() = declared()

    @Tool(description = "Move accessibility focus to the previous navigable element.")
    fun focus_previous() = declared()

    @Tool(description = "Activate the currently accessibility-focused element.")
    fun activate_focused() = declared()

    @Tool(description = "Type text into the currently focused input field.")
    fun type_text(@ToolParam(description = "The text to type into the focused field.") text: String) = declared()

    @Tool(description = "Tap a screen coordinate using normalized Android coordinates from 0 to 1000.")
    fun tap_at(
        @ToolParam(description = "Normalized x coordinate from 0 to 1000.") x: String,
        @ToolParam(description = "Normalized y coordinate from 0 to 1000.") y: String,
    ) = declared()

    @Tool(description = "Capture a screenshot of the current screen.")
    fun take_screenshot() = declared()

    @Tool(description = "Mark completion. During plan execution this completes the current plan step; when no plan step is active this finishes the whole goal.")
    fun finish_task(@ToolParam(description = "A brief completion summary, or JSON when the prompt explicitly asks for a JSON plan.") summary: String) =
        declared()

    private fun declared(): Map<String, String> = mapOf("status" to "schema_only")
}

class ReaderPlannerToolSchema : ToolSet {
    @Tool(description = "Read all visible text on the current screen.")
    fun read_screen() = declared()

    @Tool(description = "Read a compact semantic summary of the current screen.")
    fun read_screen_summary() = declared()

    @Tool(description = "Read a full orientation summary of the current screen, current focus, and major landmarks.")
    fun where_am_i() = declared()

    @Tool(description = "Read the currently accessibility-focused element with role and state.")
    fun read_focused() = declared()

    @Tool(description = "Read semantic context around the currently focused item.")
    fun read_nearby_context() = declared()

    @Tool(description = "Read recent meaningful accessibility events such as focus changes, dialogs, and notifications.")
    fun read_recent_events(@ToolParam(description = "How many recent events to read, usually 1 to 10.") limit: String = "5") =
        declared()

    @Tool(description = "Read the currently visible dialog and its contents.")
    fun read_dialog() = declared()

    @Tool(description = "Read recent notification summaries.")
    fun read_notifications(@ToolParam(description = "How many recent notifications to read, usually 1 to 10.") limit: String = "5") =
        declared()

    @Tool(description = "Read visible form fields, hints, validation errors, and focus state.")
    fun read_form_state() = declared()

    @Tool(description = "Turn continuous reader narration on so Genie automatically speaks important UI changes.")
    fun enable_continuous_reader() = declared()

    @Tool(description = "Turn continuous reader narration off.")
    fun disable_continuous_reader() = declared()

    @Tool(description = "Read whether continuous reader narration is currently on or off.")
    fun read_continuous_reader_status() = declared()

    @Tool(description = "Repeat the last spoken continuous-reader hint.")
    fun repeat_last_narration() = declared()

    @Tool(description = "Press the system back button.")
    fun go_back() = declared()

    @Tool(description = "Mark completion. During plan execution this completes the current plan step; when no plan step is active this finishes the whole goal.")
    fun finish_task(@ToolParam(description = "A brief completion summary, or JSON when the prompt explicitly asks for a JSON plan.") summary: String) =
        declared()

    private fun declared(): Map<String, String> = mapOf("status" to "schema_only")
}

class TeachingPlannerToolSchema : ToolSet {
    @Tool(description = "Create or update a conceptual canvas scene using a structured diagram payload.")
    fun visualize_concept(
        @ToolParam(description = "The scene operation: create_scene, update_scene, highlight, clear_scene, or export_scene.") operation: String,
        @ToolParam(description = "The scene identifier using only letters, numbers, underscore, and hyphen.") scene_id: String,
        @ToolParam(description = "The diagram type for create_scene: flowchart, cycle, timeline, mindmap, or table.") diagram_type: String = "",
        @ToolParam(description = "Optional scene title.") title: String = "",
        @ToolParam(description = "Optional nodes JSON payload string.") nodes: String = "",
        @ToolParam(description = "Optional edges JSON payload string.") edges: String = "",
        @ToolParam(description = "Optional comma-separated focus node ids.") focus_node_ids: String = "",
    ) = declared()

    @Tool(description = "Create or replace an interactive teaching board scene with lesson steps, whiteboard objects, and synchronized narration.")
    fun teach_with_board(
        @ToolParam(description = "The board scene identifier using only letters, numbers, underscore, and hyphen.") scene_id: String,
        @ToolParam(description = "The teaching board title shown at the top of the visualizer.") title: String,
        @ToolParam(description = "Optional board theme such as dark_classroom, blueprint, neon_lab, or light.") board_theme: String = "",
        @ToolParam(description = "A JSON array string of board objects.") objects: String = "",
        @ToolParam(description = "A JSON array string of ordered lesson steps.") steps: String = "",
        @ToolParam(description = "Optional initial narration text for the current board state.") narration_text: String = "",
    ) = declared()

    @Tool(description = "Add one object to an interactive teaching board scene.")
    fun board_add_object(
        @ToolParam(description = "The board scene identifier.") scene_id: String,
        @ToolParam(description = "The unique object identifier.") object_id: String,
        @ToolParam(description = "The object type: title, text, box, card, circle, line, arrow, or code.") object_type: String,
        @ToolParam(description = "The x position in canvas pixels.") x: String,
        @ToolParam(description = "The y position in canvas pixels.") y: String,
        @ToolParam(description = "Optional display text for the object.") text: String = "",
    ) = declared()

    @Tool(description = "Advance the teaching board to the next lesson step.")
    fun board_next_step(@ToolParam(description = "The board scene identifier.") scene_id: String) = declared()

    @Tool(description = "Set or update the narration text for the current teaching board state.")
    fun board_set_narration(
        @ToolParam(description = "The board scene identifier.") scene_id: String,
        @ToolParam(description = "The narration text Genie should associate with the current board state.") narration_text: String,
    ) = declared()

    @Tool(description = "Save a user preference or fact to long-term memory.")
    fun save_fact(
        @ToolParam(description = "The fact key to save.") key: String,
        @ToolParam(description = "The fact value to save.") value: String,
    ) = declared()

    @Tool(description = "Mark completion. During plan execution this completes the current plan step; when no plan step is active this finishes the whole goal.")
    fun finish_task(@ToolParam(description = "A brief completion summary, or JSON when the prompt explicitly asks for a JSON plan.") summary: String) =
        declared()

    private fun declared(): Map<String, String> = mapOf("status" to "schema_only")
}

class AnnotationPlannerToolSchema : ToolSet {
    @Tool(description = "Start a standalone screen annotation session for the current screen.")
    fun annotate_scene(
        @ToolParam(description = "The annotation session identifier using only letters, numbers, underscore, and hyphen.") session_id: String,
        @ToolParam(description = "Optional annotation title shown in the canvas visualizer.") title: String = "",
    ) = declared()

    @Tool(description = "Add a highlighted annotation box to the active annotation session.")
    fun annotation_add_box(
        @ToolParam(description = "The annotation session identifier.") session_id: String,
        @ToolParam(description = "The unique operation identifier.") op_id: String,
        @ToolParam(description = "Box anchor x coordinate, normalized (0-1 or 0-1000) or pixels.") x: String,
        @ToolParam(description = "Box anchor y coordinate, normalized (0-1 or 0-1000) or pixels.") y: String,
        @ToolParam(description = "Box width, normalized (0-1 or 0-1000) or pixels.") width: String = "",
        @ToolParam(description = "Box height, normalized (0-1 or 0-1000) or pixels.") height: String = "",
        @ToolParam(description = "Optional label text for this box.") label: String = "",
    ) = declared()

    @Tool(description = "Add a text label annotation to the active annotation session.")
    fun annotation_add_label(
        @ToolParam(description = "The annotation session identifier.") session_id: String,
        @ToolParam(description = "The unique operation identifier.") op_id: String,
        @ToolParam(description = "Label x coordinate, normalized (0-1 or 0-1000) or pixels.") x: String,
        @ToolParam(description = "Label y coordinate, normalized (0-1 or 0-1000) or pixels.") y: String,
        @ToolParam(description = "Label text.") text: String,
    ) = declared()

    @Tool(description = "Add a pointer annotation with an optional label to the active annotation session.")
    fun annotation_add_pointer(
        @ToolParam(description = "The annotation session identifier.") session_id: String,
        @ToolParam(description = "The unique operation identifier.") op_id: String,
        @ToolParam(description = "Pointer anchor x coordinate, normalized (0-1 or 0-1000) or pixels.") x: String,
        @ToolParam(description = "Pointer anchor y coordinate, normalized (0-1 or 0-1000) or pixels.") y: String,
        @ToolParam(description = "Optional pointer label.") text: String = "",
    ) = declared()

    @Tool(description = "Clear the active annotation session and remove all annotation overlays.")
    fun annotation_clear(@ToolParam(description = "The annotation session identifier.") session_id: String) = declared()

    @Tool(description = "Replay the current annotation session from the first annotation step.")
    fun annotation_replay(@ToolParam(description = "The annotation session identifier.") session_id: String) = declared()

    @Tool(description = "Capture a screenshot of the current screen.")
    fun take_screenshot() = declared()

    @Tool(description = "Read all visible text on the current screen.")
    fun read_screen() = declared()

    @Tool(description = "Mark completion. During plan execution this completes the current plan step; when no plan step is active this finishes the whole goal.")
    fun finish_task(@ToolParam(description = "A brief completion summary, or JSON when the prompt explicitly asks for a JSON plan.") summary: String) =
        declared()

    private fun declared(): Map<String, String> = mapOf("status" to "schema_only")
}

class DocumentPlannerToolSchema : ToolSet {
    @Tool(description = "Read text from a local PDF file page range.")
    fun read_pdf_page_range(
        @ToolParam(description = "The absolute path to the local PDF file.") file_path: String,
        @ToolParam(description = "The 1-based start page number.") start_page: String,
        @ToolParam(description = "The 1-based end page number.") end_page: String,
        @ToolParam(description = "Optional maximum number of characters to return.") max_chars: String = "",
    ) = declared()

    @Tool(description = "Read all visible text on the current screen.")
    fun read_screen() = declared()

    @Tool(description = "Save a user preference or fact to long-term memory.")
    fun save_fact(
        @ToolParam(description = "The fact key to save.") key: String,
        @ToolParam(description = "The fact value to save.") value: String,
    ) = declared()

    @Tool(description = "Retrieve a previously saved user preference or fact.")
    fun retrieve_fact(@ToolParam(description = "The fact key to retrieve.") key: String) = declared()

    @Tool(description = "Mark completion. During plan execution this completes the current plan step; when no plan step is active this finishes the whole goal.")
    fun finish_task(@ToolParam(description = "A brief completion summary, or JSON when the prompt explicitly asks for a JSON plan.") summary: String) =
        declared()

    private fun declared(): Map<String, String> = mapOf("status" to "schema_only")
}

fun geniePlannerToolProviders(profile: ToolProfile = ToolProfile.DEFAULT): List<ToolProvider> {
    return when (profile) {
        ToolProfile.Messaging -> listOf(tool(MessagingPlannerToolSchema()))
        ToolProfile.ScreenControl -> listOf(tool(ScreenControlPlannerToolSchema()))
        ToolProfile.Reader -> listOf(tool(ReaderPlannerToolSchema()))
        ToolProfile.Teaching -> listOf(tool(TeachingPlannerToolSchema()))
        ToolProfile.Annotation -> listOf(tool(AnnotationPlannerToolSchema()))
        ToolProfile.Document -> listOf(tool(DocumentPlannerToolSchema()))
        ToolProfile.General -> listOf(tool(PlannerToolSchema()))
    }
}
