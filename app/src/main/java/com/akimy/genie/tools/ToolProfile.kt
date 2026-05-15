package com.akimy.genie.tools

import android.content.Context

private const val PREFS_NAME = "genie_tool_profile"
private const val KEY_SELECTED_PROFILE = "selected_profile"

private val DOCUMENT_TOOLS = setOf(
    "list_device_pdfs",
    "detect_open_pdf",
)

enum class ToolProfile(
    val id: String,
    val displayName: String,
    val description: String,
    val toolNames: Set<String>,
) {
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
            "read_notifications",
        ),
    ),

    Teaching(
        id = "teaching",
        displayName = "Teaching",
        description = "Visualizer and board tools for explanations and step-by-step lessons.",
        toolNames = setOf(
            "board_teach_step",
            "visualize_concept",
        ),
    ),

    Document(
        id = "document",
        displayName = "Document",
        description = "PDF quiz and summary tools. Agent picks intent and source, orchestrator handles extraction.",
        toolNames = DOCUMENT_TOOLS + setOf("reply", "tasks"),
    ),

    Chat(
        id = "chat",
        displayName = "Chat",
        description = "Conversational assistant for answering general questions and remembering facts about the user.",
        toolNames = setOf(
            "reply",
            "save_fact",
            "health_search_topics",
            "health_get_topic",
        ),
    ),

    Vision(
        id = "vision",
        displayName = "Vision",
        description = "Visual assistant that can see and analyze what's on your screen. Answers questions about images, text, and UI elements.",
        toolNames = setOf(
            "reply",
            "take_screenshot",
            "save_fact",
        ),
    ),

    Scribe(
        id = "scribe",
        displayName = "Scribe",
        description = "Audio recording and transcription with intelligent summarization. Supports general notes and medical SOAP format.",
        toolNames = emptySet(),
    ),

    Health(
        id = "health",
        displayName = "Health",
        description = "Food calorie analyzer and health topics library. Analyze food nutrition or search WHO health information.",
        toolNames = emptySet(),
    ),

    AppControl(
        id = "app_control",
        displayName = "App Control",
        description = "Autonomous device control. Opens apps, navigates, types, and completes multi-step tasks like sending messages or searching content.",
        toolNames = setOf(
            "reply",
            "open_app",
            "click",
            "type_text",
            "scroll",
            "go_back",
            "go_home",
            "read_screen",
        ),
    );

    companion object {
        val DEFAULT = Chat

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
