package com.akimy.genie.tools

import android.content.Context

private const val PREFS_NAME = "genie_tool_profile"
private const val KEY_SELECTED_PROFILE = "selected_profile"

private val CORE_NAVIGATION_TOOLS = setOf(
    "open_app",
    "go_back",
    "go_home",
    "swipe",
    "scroll",
    "scroll_forward",
    "scroll_backward",
)

private val SCREEN_AWARENESS_TOOLS = setOf(
    "read_screen",
    "read_screen_summary",
    "where_am_i",
    "read_focused",
    "read_nearby_context",
    "what_can_i_do_here",
    "read_recent_events",
    "read_screen_changes",
    "read_dialog",
    "read_notifications",
    "read_form_state",
    "read_screen_map",
    "take_screenshot",
)

private val UI_INTERACTION_TOOLS = setOf(
    "click",
    "click_element_by_id",
    "focus_by_text",
    "focus_by_role",
    "focus_first",
    "focus_next",
    "focus_previous",
    "activate_focused",
    "type_text",
)

private val MEMORY_TOOLS = setOf(
    "save_fact",
    "retrieve_fact",
    "save_screen_hint",
)

private val TEACHING_TOOLS = setOf(
    "visualize_concept",
    "teach_with_board",
    "board_add_object",
    "board_update_object",
    "board_remove_object",
    "board_focus_object",
    "board_reveal_step",
    "board_next_step",
    "board_prev_step",
    "board_replay_step",
    "board_set_narration",
)

private val ANNOTATION_TOOLS = setOf(
    "annotate_scene",
    "annotation_add_box",
    "annotation_add_label",
    "annotation_add_pointer",
    "annotation_clear",
    "annotation_replay",
)

private val READER_TOOLS = setOf(
    "enable_continuous_reader",
    "disable_continuous_reader",
    "read_continuous_reader_status",
    "repeat_last_narration",
)

private val DOCUMENT_TOOLS = setOf(
    "read_pdf_page_range",
    "list_device_pdfs",
    "detect_open_pdf",
)

enum class ToolProfile(
    val id: String,
    val displayName: String,
    val description: String,
    val toolNames: Set<String>,
) {
    Messaging(
        id = "messaging",
        displayName = "Messaging",
        description = "Lean loadout for chat, search, typing, sending, and basic recovery.",
        toolNames = setOf(
            "reply",
            "open_app",
            "read_screen",
            "read_screen_summary",
            "read_form_state",
            "click",
            "focus_by_text",
            "focus_first",
            "focus_next",
            "activate_focused",
            "type_text",
            "click_element_by_id",
            "take_screenshot",
            "go_back",
            "go_home",
            "scroll",
            "swipe",
            "save_fact",
            "retrieve_fact",
        ),
    ),

    ScreenControl(
        id = "screen_control",
        displayName = "Screen Control",
        description = "General Android navigation and UI operation without teaching or document tools.",
        toolNames = setOf(
            "reply",
            "open_app",
            "go_back",
            "go_home",
            "swipe",
            "scroll",
            "scroll_forward",
            "scroll_backward",
            "read_screen",
            "read_screen_summary",
            "where_am_i",
            "read_focused",
            "click",
            "focus_by_text",
            "focus_by_role",
            "focus_first",
            "focus_next",
            "focus_previous",
            "activate_focused",
            "type_text",
            "click_element_by_id",
            "take_screenshot",
            "save_fact",
            "retrieve_fact",
        ),
    ),

    SeeAndTap(
        id = "see_and_tap",
        displayName = "See And Tap",
        description = "Isolated visual grounding profile: take a screenshot, then tap boxed items using click_element_by_id.",
        toolNames = setOf(
            "reply",
            "take_screenshot",
            "click_element_by_id",
        ),
    ),

    Reader(
        id = "reader",
        displayName = "Reader",
        description = "Screen reading, context awareness, notifications, forms, and narration.",
        toolNames = setOf(
            "reply",
            "read_screen",
            "read_screen_summary",
            "where_am_i",
            "read_focused",
            "read_nearby_context",
            "read_recent_events",
            "read_dialog",
            "read_notifications",
            "read_form_state",
            "enable_continuous_reader",
            "disable_continuous_reader",
            "read_continuous_reader_status",
            "repeat_last_narration",
            "go_back",
        ),
    ),

    Teaching(
        id = "teaching",
        displayName = "Teaching",
        description = "Visualizer and board tools for explanations and step-by-step lessons.",
        toolNames = setOf(
            "reply",
            "visualize_concept",
            "teach_with_board",
            "board_add_object",
            "board_next_step",
            "board_set_narration",
            "save_fact",
        ),
    ),

    Annotation(
        id = "annotation",
        displayName = "Annotation",
        description = "Screen annotation, labels, boxes, pointers, screenshots, and basic navigation.",
        toolNames = ANNOTATION_TOOLS + setOf("reply", "take_screenshot", "read_screen"),
    ),

    Document(
        id = "document",
        displayName = "Document",
        description = "PDF reading with compact screen awareness and memory helpers.",
        toolNames = DOCUMENT_TOOLS + setOf("reply", "read_screen", "save_fact", "retrieve_fact"),
    ),

    General(
        id = "general",
        displayName = "General",
        description = "All tools. Useful for exploration, but heavier for the model.",
        toolNames = setOf("reply") +
            CORE_NAVIGATION_TOOLS +
            SCREEN_AWARENESS_TOOLS +
            UI_INTERACTION_TOOLS +
            MEMORY_TOOLS +
            TEACHING_TOOLS +
            ANNOTATION_TOOLS +
            READER_TOOLS +
            DOCUMENT_TOOLS,
    ),

    Chat(
        id = "chat",
        displayName = "Chat",
        description = "Conversational assistant for answering general questions and remembering facts about the user.",
        toolNames = setOf(
            "reply",
            "save_fact",
        ),
    );

    companion object {
        val DEFAULT = Messaging

        fun fromId(id: String?): ToolProfile {
            return values().firstOrNull { it.id == id } ?: DEFAULT
        }
    }
}

object ToolProfilePrefs {
    fun getSelectedProfile(context: Context): ToolProfile {
        val id = context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_PROFILE, null)
        return ToolProfile.fromId(id)
    }

    fun setSelectedProfile(context: Context, profile: ToolProfile) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_PROFILE, profile.id)
            .apply()
    }
}
