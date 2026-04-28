package com.akimy.genie.tools.impl

import com.akimy.genie.agent.ToolOutcome
import com.akimy.genie.tools.GenieTool
import com.akimy.genie.tools.ToolServiceContext

// ============================================================================
// OS interaction tools
// ============================================================================

class ClickTool : GenieTool {
    override val name = "click"
    override val description = "Click on a UI element by its visible text or content description"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        val target = args["target"] ?: return ToolOutcome.LogicErr("Missing 'target' argument")
        return if (serviceContext.clickElement(target)) {
            ToolOutcome.Ok("Clicked on '$target'")
        } else {
            ToolOutcome.TransientErr("Could not find clickable element: '$target'")
        }
    }
}

class TapAtTool : GenieTool {
    override val name = "tap_at"
    override val description = "Tap a normalized screen coordinate where x and y are 0..1000"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        val normalizedX = args["x"]?.toFloatOrNull()
            ?: return ToolOutcome.LogicErr("Missing or invalid 'x' argument")
        val normalizedY = args["y"]?.toFloatOrNull()
            ?: return ToolOutcome.LogicErr("Missing or invalid 'y' argument")

        if (normalizedX !in 0f..1000f || normalizedY !in 0f..1000f) {
            return ToolOutcome.LogicErr("'x' and 'y' must be normalized coordinates from 0 to 1000")
        }

        val viewport = serviceContext.getViewportInfo()
        if (viewport.widthPx <= 0 || viewport.heightPx <= 0) {
            return ToolOutcome.TransientErr("Viewport dimensions are unavailable")
        }

        val realX = normalizedToPixel(normalizedX, viewport.widthPx)
        val realY = normalizedToPixel(normalizedY, viewport.heightPx)

        return if (serviceContext.tap(realX, realY)) {
            ToolOutcome.Ok(
                "Tapped normalized (${normalizedX.formatCoordinate()}, ${normalizedY.formatCoordinate()}) " +
                    "at pixel (${realX.toInt()}, ${realY.toInt()})"
            )
        } else {
            ToolOutcome.TransientErr("Tap gesture failed at normalized (${normalizedX.formatCoordinate()}, ${normalizedY.formatCoordinate()})")
        }
    }

    private fun normalizedToPixel(value: Float, sizePx: Int): Float {
        val maxPixel = (sizePx - 1).coerceAtLeast(0).toFloat()
        return ((value / 1000f) * maxPixel).coerceIn(0f, maxPixel)
    }

    private fun Float.formatCoordinate(): String {
        return if (this % 1f == 0f) this.toInt().toString() else "%.1f".format(this)
    }
}

class TypeTextTool : GenieTool {
    override val name = "type_text"
    override val description = "Type text into the currently focused input field"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        val text = args["text"] ?: return ToolOutcome.LogicErr("Missing 'text' argument")
        return if (serviceContext.typeText(text)) {
            ToolOutcome.Ok("Typed: '$text'")
        } else {
            ToolOutcome.TransientErr("No focused text field found")
        }
    }
}

class SwipeTool : GenieTool {
    override val name = "swipe"
    override val description = "Swipe in a direction: up, down, left, right"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        val direction = args["direction"] ?: return ToolOutcome.LogicErr("Missing 'direction' argument")
        val validDirections = setOf("up", "down", "left", "right")
        if (direction.lowercase() !in validDirections) {
            return ToolOutcome.LogicErr("Invalid direction '$direction'. Must be one of: $validDirections")
        }
        return if (serviceContext.swipe(direction.lowercase())) {
            ToolOutcome.Ok("Swiped $direction")
        } else {
            ToolOutcome.TransientErr("Swipe gesture failed")
        }
    }
}

class ScrollTool : GenieTool {
    override val name = "scroll"
    override val description = "Scroll the screen up or down"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        val direction = args["direction"] ?: return ToolOutcome.LogicErr("Missing 'direction' argument")
        if (direction.lowercase() !in setOf("up", "down")) {
            return ToolOutcome.LogicErr("Invalid scroll direction '$direction'. Must be 'up' or 'down'")
        }
        return if (serviceContext.scroll(direction.lowercase())) {
            ToolOutcome.Ok("Scrolled $direction")
        } else {
            ToolOutcome.TransientErr("Scroll failed")
        }
    }
}

class ReadScreenTool : GenieTool {
    override val name = "read_screen"
    override val description = "Read all visible text on the current screen"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        val text = serviceContext.readScreenText()
        return if (text.isNotEmpty()) {
            ToolOutcome.Ok(text)
        } else {
            ToolOutcome.Ok("Screen is empty or no text elements found")
        }
    }
}

class TakeScreenshotTool : GenieTool {
    override val name = "take_screenshot"
    override val description = "Capture a screenshot of the current screen"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        val result = serviceContext.takeScreenshot()
        return ToolOutcome.Ok(result)
    }
}

class OpenAppTool : GenieTool {
    override val name = "open_app"
    override val description = "Open an installed app by its name"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        val appName = args["name"] ?: return ToolOutcome.LogicErr("Missing 'name' argument")
        return if (serviceContext.openApp(appName)) {
            ToolOutcome.Ok("Opened '$appName'")
        } else {
            ToolOutcome.LogicErr("App not found: '$appName'")
        }
    }
}

// ============================================================================
// Navigation Tools
// ============================================================================

class GoBackTool : GenieTool {
    override val name = "go_back"
    override val description = "Press the system back button"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        return if (serviceContext.goBack()) {
            ToolOutcome.Ok("Pressed back")
        } else {
            ToolOutcome.TransientErr("Back navigation failed")
        }
    }
}

class GoHomeTool : GenieTool {
    override val name = "go_home"
    override val description = "Press the system home button"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        return if (serviceContext.goHome()) {
            ToolOutcome.Ok("Pressed home")
        } else {
            ToolOutcome.TransientErr("Home navigation failed")
        }
    }
}

class ReadFocusedTool : GenieTool {
    override val name = "read_focused"
    override val description = "Read the currently accessibility-focused element with role and state"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        return ToolOutcome.Ok(serviceContext.readFocusedNode())
    }
}

class FocusNextTool : GenieTool {
    override val name = "focus_next"
    override val description = "Move accessibility focus to the next navigable element"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        return if (serviceContext.focusNext()) {
            ToolOutcome.Ok(serviceContext.readFocusedNode())
        } else {
            ToolOutcome.TransientErr("Could not move focus to the next element")
        }
    }
}

class FocusPreviousTool : GenieTool {
    override val name = "focus_previous"
    override val description = "Move accessibility focus to the previous navigable element"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        return if (serviceContext.focusPrevious()) {
            ToolOutcome.Ok(serviceContext.readFocusedNode())
        } else {
            ToolOutcome.TransientErr("Could not move focus to the previous element")
        }
    }
}

class FocusFirstTool : GenieTool {
    override val name = "focus_first"
    override val description = "Move accessibility focus to the first navigable element on screen"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        return if (serviceContext.focusFirst()) {
            ToolOutcome.Ok(serviceContext.readFocusedNode())
        } else {
            ToolOutcome.TransientErr("Could not move focus to the first element")
        }
    }
}

class FocusByTextTool : GenieTool {
    override val name = "focus_by_text"
    override val description = "Move accessibility focus to the first element matching visible text"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        val target = args["target"] ?: return ToolOutcome.LogicErr("Missing 'target' argument")
        return if (serviceContext.focusElementByText(target)) {
            ToolOutcome.Ok(serviceContext.readFocusedNode())
        } else {
            ToolOutcome.TransientErr("Could not focus an element matching '$target'")
        }
    }
}

class FocusByRoleTool : GenieTool {
    override val name = "focus_by_role"
    override val description = "Move accessibility focus to the first element matching a semantic role"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        val role = args["role"] ?: return ToolOutcome.LogicErr("Missing 'role' argument")
        return if (serviceContext.focusElementByRole(role)) {
            ToolOutcome.Ok(serviceContext.readFocusedNode())
        } else {
            ToolOutcome.TransientErr("Could not focus a '$role' element")
        }
    }
}

class ActivateFocusedTool : GenieTool {
    override val name = "activate_focused"
    override val description = "Activate the currently accessibility-focused element"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        return if (serviceContext.activateFocused()) {
            ToolOutcome.Ok("Activated focused element")
        } else {
            ToolOutcome.TransientErr("Could not activate the focused element")
        }
    }
}

class ScrollForwardTool : GenieTool {
    override val name = "scroll_forward"
    override val description = "Scroll the current list or container forward using native accessibility actions"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        return if (serviceContext.scrollForward()) {
            ToolOutcome.Ok("Scrolled forward")
        } else {
            ToolOutcome.TransientErr("Could not scroll forward")
        }
    }
}

class ScrollBackwardTool : GenieTool {
    override val name = "scroll_backward"
    override val description = "Scroll the current list or container backward using native accessibility actions"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        return if (serviceContext.scrollBackward()) {
            ToolOutcome.Ok("Scrolled backward")
        } else {
            ToolOutcome.TransientErr("Could not scroll backward")
        }
    }
}

class ReadScreenSummaryTool : GenieTool {
    override val name = "read_screen_summary"
    override val description = "Read a compact semantic summary of the current screen"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        return ToolOutcome.Ok(serviceContext.readScreenSummary())
    }
}

class ReadRecentEventsTool : GenieTool {
    override val name = "read_recent_events"
    override val description = "Read recent meaningful accessibility events such as focus, dialogs, and notifications"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        val limit = args["limit"]?.toIntOrNull() ?: 5
        return ToolOutcome.Ok(serviceContext.readRecentEvents(limit.coerceIn(1, 10)))
    }
}

class WhereAmITool : GenieTool {
    override val name = "where_am_i"
    override val description = "Read a full orientation summary of the current screen, focus, and landmarks"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        return ToolOutcome.Ok(serviceContext.whereAmI())
    }
}

class ReadNearbyContextTool : GenieTool {
    override val name = "read_nearby_context"
    override val description = "Read semantic context around the currently focused element"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        return ToolOutcome.Ok(serviceContext.readNearbyContext())
    }
}

class WhatCanIDoHereTool : GenieTool {
    override val name = "what_can_i_do_here"
    override val description = "Read the native accessibility actions available on the current item"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        return ToolOutcome.Ok(serviceContext.whatCanIDoHere())
    }
}

class ReadScreenChangesTool : GenieTool {
    override val name = "read_screen_changes"
    override val description = "Read meaningful screen changes detected since the last awareness update"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        return ToolOutcome.Ok(serviceContext.readScreenChanges())
    }
}

class EnableContinuousReaderTool : GenieTool {
    override val name = "enable_continuous_reader"
    override val description = "Turn continuous reader narration on so Genie speaks important UI changes automatically"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        return ToolOutcome.Ok(serviceContext.enableContinuousReader())
    }
}

class DisableContinuousReaderTool : GenieTool {
    override val name = "disable_continuous_reader"
    override val description = "Turn continuous reader narration off"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        return ToolOutcome.Ok(serviceContext.disableContinuousReader())
    }
}

class ReadContinuousReaderStatusTool : GenieTool {
    override val name = "read_continuous_reader_status"
    override val description = "Read whether continuous reader narration is currently on or off"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        return ToolOutcome.Ok(serviceContext.readContinuousReaderStatus())
    }
}

class RepeatLastNarrationTool : GenieTool {
    override val name = "repeat_last_narration"
    override val description = "Repeat the last spoken continuous-reader hint"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        return ToolOutcome.Ok(serviceContext.repeatLastNarration())
    }
}

class ReadScreenMapTool : GenieTool {
    override val name = "read_screen_map"
    override val description = "Read Genie's learned shortcuts, landmarks, and remembered hints for the current screen"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        return ToolOutcome.Ok(serviceContext.readScreenMap())
    }
}

class SaveScreenHintTool : GenieTool {
    override val name = "save_screen_hint"
    override val description = "Save a user-provided note for the current screen so Genie remembers it later"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        val note = args["note"] ?: return ToolOutcome.LogicErr("Missing 'note' argument")
        return ToolOutcome.Ok(serviceContext.saveScreenHint(note))
    }
}

class ReadDialogTool : GenieTool {
    override val name = "read_dialog"
    override val description = "Read the currently visible dialog and its contents"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        return ToolOutcome.Ok(serviceContext.readDialog())
    }
}

class ReadNotificationsTool : GenieTool {
    override val name = "read_notifications"
    override val description = "Read recent notification summaries"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        val limit = args["limit"]?.toIntOrNull() ?: 5
        return ToolOutcome.Ok(serviceContext.readNotifications(limit.coerceIn(1, 10)))
    }
}

class ReadFormStateTool : GenieTool {
    override val name = "read_form_state"
    override val description = "Read visible form fields with hints, errors, and focused state"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        return ToolOutcome.Ok(serviceContext.readFormState())
    }
}

// ============================================================================
// Persistence Tools - interface to Room FactStore
// ============================================================================

class SaveFactTool : GenieTool {
    override val name = "save_fact"
    override val description = "Save a user preference or fact to long-term memory"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        val key = args["key"] ?: return ToolOutcome.LogicErr("Missing 'key' argument")
        val value = args["value"] ?: return ToolOutcome.LogicErr("Missing 'value' argument")
        // Actual persistence is handled by the orchestrator via the database
        return ToolOutcome.Ok("SAVE_FACT:$key=$value")
    }
}

class RetrieveFactTool : GenieTool {
    override val name = "retrieve_fact"
    override val description = "Retrieve a previously saved user preference or fact"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        val key = args["key"] ?: return ToolOutcome.LogicErr("Missing 'key' argument")
        // Actual retrieval is handled by the orchestrator via the database
        return ToolOutcome.Ok("RETRIEVE_FACT:$key")
    }
}

class ReadPdfPageRangeTool : GenieTool {
    override val name = "read_pdf_page_range"
    override val description = "Read text from a local PDF page range"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        val filePath = args["file_path"] ?: args["path"]
            ?: return ToolOutcome.LogicErr("Missing 'file_path' argument")

        val startPage = args["start_page"]?.toIntOrNull()
            ?: return ToolOutcome.LogicErr("Missing or invalid 'start_page' argument")
        val endPage = args["end_page"]?.toIntOrNull()
            ?: return ToolOutcome.LogicErr("Missing or invalid 'end_page' argument")

        if (startPage <= 0 || endPage <= 0) {
            return ToolOutcome.LogicErr("'start_page' and 'end_page' must be >= 1")
        }
        if (startPage > endPage) {
            return ToolOutcome.LogicErr("'start_page' cannot be greater than 'end_page'")
        }

        val maxChars = args["max_chars"]?.toIntOrNull() ?: 8_000
        if (maxChars <= 0) {
            return ToolOutcome.LogicErr("'max_chars' must be > 0")
        }

        // Actual PDF extraction is handled by the orchestrator where appContext is available.
        return ToolOutcome.Ok("READ_PDF_PAGE_RANGE")
    }
}

class VisualizeConceptTool : GenieTool {
    override val name = "visualize_concept"
    override val description = "Create or update a conceptual canvas scene using a structured diagram payload"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        val rawOperation = args["operation"]?.trim()?.lowercase()
            ?: return ToolOutcome.LogicErr("Missing 'operation' argument")
        val operation = when (rawOperation) {
            "create", "create_scene" -> "create_scene"
            "update", "update_scene" -> "update_scene"
            "highlight", "focus" -> "highlight"
            "clear", "clear_scene", "delete", "delete_scene" -> "clear_scene"
            "export", "export_scene" -> "export_scene"
            else -> null
        } ?: return ToolOutcome.LogicErr(
            "Invalid 'operation' '$rawOperation'. Must map to create_scene, update_scene, highlight, clear_scene, export_scene"
        )

        val validOps = setOf("create_scene", "update_scene", "highlight", "clear_scene", "export_scene")
        if (operation !in validOps) {
            return ToolOutcome.LogicErr(
                "Invalid 'operation' '$operation'. Must be one of: ${validOps.joinToString()}"
            )
        }

        val sceneId = args["scene_id"]?.trim()
            ?: return ToolOutcome.LogicErr("Missing 'scene_id' argument")
        if (!Regex("^[A-Za-z0-9_-]{1,64}$").matches(sceneId)) {
            return ToolOutcome.LogicErr("Invalid 'scene_id'. Use 1-64 chars: letters, numbers, underscore, hyphen")
        }

        if ((args["nodes"]?.length ?: 0) > 24_000 || (args["edges"]?.length ?: 0) > 24_000) {
            return ToolOutcome.LogicErr("'nodes'/'edges' payload too large. Keep each JSON string under 24,000 chars")
        }

        if (operation == "create_scene") {
            val diagramType = args["diagram_type"]?.trim()?.lowercase()
                ?: return ToolOutcome.LogicErr("Missing 'diagram_type' argument for create_scene")
            if (diagramType !in setOf("flowchart", "cycle", "timeline", "mindmap", "table")) {
                return ToolOutcome.LogicErr(
                    "Invalid 'diagram_type' '$diagramType'. Must be one of: flowchart, cycle, timeline, mindmap, table"
                )
            }
        }

        // Canvas rendering/storage is handled by orchestrator-side visualizer module.
        return ToolOutcome.Ok("VISUALIZE_CONCEPT:$operation")
    }
}

class TeachWithBoardTool : GenieTool {
    override val name = "teach_with_board"
    override val description = "Create or replace an interactive teaching board scene with staged lesson steps and narration"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        val sceneId = args["scene_id"]?.trim()
            ?: return ToolOutcome.LogicErr("Missing 'scene_id' argument")
        if (!Regex("^[A-Za-z0-9_-]{1,64}$").matches(sceneId)) {
            return ToolOutcome.LogicErr("Invalid 'scene_id'. Use 1-64 chars: letters, numbers, underscore, hyphen")
        }
        if ((args["objects"]?.length ?: 0) > 32_000 || (args["steps"]?.length ?: 0) > 16_000) {
            return ToolOutcome.LogicErr("'objects' or 'steps' payload too large for teach_with_board")
        }
        return ToolOutcome.Ok("TEACH_WITH_BOARD")
    }
}

class BoardAddObjectTool : GenieTool {
    override val name = "board_add_object"
    override val description = "Add a drawable object to an existing teaching board scene"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        requireBoardSceneAndObject(args)?.let { return it }
        val objectType = args["object_type"]?.trim()?.lowercase()
            ?: return ToolOutcome.LogicErr("Missing 'object_type' argument")
        if (objectType !in setOf("title", "text", "box", "card", "circle", "line", "arrow", "code")) {
            return ToolOutcome.LogicErr("Invalid 'object_type' '$objectType'")
        }
        if (args["x"]?.toFloatOrNull() == null || args["y"]?.toFloatOrNull() == null) {
            return ToolOutcome.LogicErr("'x' and 'y' must be valid numbers")
        }
        return ToolOutcome.Ok("BOARD_ADD_OBJECT")
    }
}

class BoardUpdateObjectTool : GenieTool {
    override val name = "board_update_object"
    override val description = "Update position, size, style, text, or step of an existing teaching board object"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        requireBoardSceneAndObject(args)?.let { return it }
        return ToolOutcome.Ok("BOARD_UPDATE_OBJECT")
    }
}

class BoardRemoveObjectTool : GenieTool {
    override val name = "board_remove_object"
    override val description = "Remove an object from the teaching board scene"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        requireBoardSceneAndObject(args)?.let { return it }
        return ToolOutcome.Ok("BOARD_REMOVE_OBJECT")
    }
}

class BoardFocusObjectTool : GenieTool {
    override val name = "board_focus_object"
    override val description = "Visually focus a board object so the lesson can emphasize it"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        requireBoardSceneAndObject(args)?.let { return it }
        return ToolOutcome.Ok("BOARD_FOCUS_OBJECT")
    }
}

class BoardRevealStepTool : GenieTool {
    override val name = "board_reveal_step"
    override val description = "Jump the teaching board to a named lesson step and reveal its objects"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        val sceneId = args["scene_id"]?.trim()
            ?: return ToolOutcome.LogicErr("Missing 'scene_id' argument")
        val stepId = args["step_id"]?.trim()
            ?: return ToolOutcome.LogicErr("Missing 'step_id' argument")
        if (!Regex("^[A-Za-z0-9_-]{1,64}$").matches(sceneId) || !Regex("^[A-Za-z0-9_-]{1,64}$").matches(stepId)) {
            return ToolOutcome.LogicErr("Invalid 'scene_id' or 'step_id'")
        }
        return ToolOutcome.Ok("BOARD_REVEAL_STEP")
    }
}

class BoardNextStepTool : GenieTool {
    override val name = "board_next_step"
    override val description = "Advance the teaching board to the next lesson step"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        val sceneId = args["scene_id"]?.trim()
            ?: return ToolOutcome.LogicErr("Missing 'scene_id' argument")
        if (!Regex("^[A-Za-z0-9_-]{1,64}$").matches(sceneId)) {
            return ToolOutcome.LogicErr("Invalid 'scene_id'")
        }
        return ToolOutcome.Ok("BOARD_NEXT_STEP")
    }
}

class BoardPrevStepTool : GenieTool {
    override val name = "board_prev_step"
    override val description = "Move the teaching board back to the previous lesson step"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        val sceneId = args["scene_id"]?.trim()
            ?: return ToolOutcome.LogicErr("Missing 'scene_id' argument")
        if (!Regex("^[A-Za-z0-9_-]{1,64}$").matches(sceneId)) {
            return ToolOutcome.LogicErr("Invalid 'scene_id'")
        }
        return ToolOutcome.Ok("BOARD_PREV_STEP")
    }
}

class BoardReplayStepTool : GenieTool {
    override val name = "board_replay_step"
    override val description = "Replay the current lesson step so its emphasis and narration can be repeated"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        val sceneId = args["scene_id"]?.trim()
            ?: return ToolOutcome.LogicErr("Missing 'scene_id' argument")
        if (!Regex("^[A-Za-z0-9_-]{1,64}$").matches(sceneId)) {
            return ToolOutcome.LogicErr("Invalid 'scene_id'")
        }
        return ToolOutcome.Ok("BOARD_REPLAY_STEP")
    }
}

class BoardSetNarrationTool : GenieTool {
    override val name = "board_set_narration"
    override val description = "Set or update the narration text associated with the current teaching board state"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        val sceneId = args["scene_id"]?.trim()
            ?: return ToolOutcome.LogicErr("Missing 'scene_id' argument")
        val narrationText = args["narration_text"]?.trim()
            ?: return ToolOutcome.LogicErr("Missing 'narration_text' argument")
        if (!Regex("^[A-Za-z0-9_-]{1,64}$").matches(sceneId)) {
            return ToolOutcome.LogicErr("Invalid 'scene_id'")
        }
        if (narrationText.length > 600) {
            return ToolOutcome.LogicErr("'narration_text' is too long. Keep it under 600 characters")
        }
        return ToolOutcome.Ok("BOARD_SET_NARRATION")
    }
}

private fun requireBoardSceneAndObject(args: Map<String, String>): ToolOutcome? {
    val sceneId = args["scene_id"]?.trim()
        ?: return ToolOutcome.LogicErr("Missing 'scene_id' argument")
    val objectId = args["object_id"]?.trim()
        ?: return ToolOutcome.LogicErr("Missing 'object_id' argument")
    val idPattern = Regex("^[A-Za-z0-9_-]{1,64}$")
    if (!idPattern.matches(sceneId) || !idPattern.matches(objectId)) {
        return ToolOutcome.LogicErr("Invalid 'scene_id' or 'object_id'. Use letters, numbers, underscore, hyphen")
    }
    return null
}

class AnnotateSceneTool : GenieTool {
    override val name = "annotate_scene"
    override val description = "Start a standalone screen annotation session"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        val sessionId = args["session_id"]?.trim()
            ?: return ToolOutcome.LogicErr("Missing 'session_id' argument")
        if (!Regex("^[A-Za-z0-9_-]{1,64}$").matches(sessionId)) {
            return ToolOutcome.LogicErr("Invalid 'session_id'. Use letters, numbers, underscore, hyphen")
        }
        return ToolOutcome.Ok("ANNOTATE_SCENE")
    }
}

class AnnotationAddBoxTool : GenieTool {
    override val name = "annotation_add_box"
    override val description = "Add a highlighted box annotation to the current annotation session"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        requireAnnotationSessionAndOp(args)?.let { return it }
        if (args["x"]?.toFloatOrNull() == null || args["y"]?.toFloatOrNull() == null) {
            return ToolOutcome.LogicErr("'x' and 'y' must be valid numbers")
        }
        val hasWidthHeight = args["width"]?.toFloatOrNull() != null && args["height"]?.toFloatOrNull() != null
        val hasBounds = args["x2"]?.toFloatOrNull() != null && args["y2"]?.toFloatOrNull() != null
        if (!hasWidthHeight && !hasBounds) {
            return ToolOutcome.LogicErr("Provide either width/height or x2/y2")
        }
        if ((args["style"]?.length ?: 0) > 4_000) {
            return ToolOutcome.LogicErr("'style' payload is too large")
        }
        return ToolOutcome.Ok("ANNOTATION_ADD_BOX")
    }
}

class AnnotationAddLabelTool : GenieTool {
    override val name = "annotation_add_label"
    override val description = "Add a text label annotation to the current annotation session"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        requireAnnotationSessionAndOp(args)?.let { return it }
        val text = args["text"]?.trim()
            ?: return ToolOutcome.LogicErr("Missing 'text' argument")
        if (text.isBlank()) return ToolOutcome.LogicErr("'text' cannot be blank")
        if (args["x"]?.toFloatOrNull() == null || args["y"]?.toFloatOrNull() == null) {
            return ToolOutcome.LogicErr("'x' and 'y' must be valid numbers")
        }
        if ((args["style"]?.length ?: 0) > 4_000) {
            return ToolOutcome.LogicErr("'style' payload is too large")
        }
        return ToolOutcome.Ok("ANNOTATION_ADD_LABEL")
    }
}

class AnnotationAddPointerTool : GenieTool {
    override val name = "annotation_add_pointer"
    override val description = "Add a pointer annotation with optional label to the current annotation session"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        requireAnnotationSessionAndOp(args)?.let { return it }
        if (args["x"]?.toFloatOrNull() == null || args["y"]?.toFloatOrNull() == null) {
            return ToolOutcome.LogicErr("'x' and 'y' must be valid numbers")
        }
        val targetX = args["target_x"]?.trim()
        val targetY = args["target_y"]?.trim()
        if ((targetX.isNullOrBlank() xor targetY.isNullOrBlank())) {
            return ToolOutcome.LogicErr("'target_x' and 'target_y' must both be provided when setting pointer target")
        }
        if (!targetX.isNullOrBlank() && targetX.toFloatOrNull() == null) {
            return ToolOutcome.LogicErr("'target_x' must be a valid number")
        }
        if (!targetY.isNullOrBlank() && targetY.toFloatOrNull() == null) {
            return ToolOutcome.LogicErr("'target_y' must be a valid number")
        }
        if ((args["style"]?.length ?: 0) > 4_000) {
            return ToolOutcome.LogicErr("'style' payload is too large")
        }
        return ToolOutcome.Ok("ANNOTATION_ADD_POINTER")
    }
}

class AnnotationClearTool : GenieTool {
    override val name = "annotation_clear"
    override val description = "Clear all overlays for the given annotation session"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        val sessionId = args["session_id"]?.trim()
            ?: return ToolOutcome.LogicErr("Missing 'session_id' argument")
        if (!Regex("^[A-Za-z0-9_-]{1,64}$").matches(sessionId)) {
            return ToolOutcome.LogicErr("Invalid 'session_id'. Use letters, numbers, underscore, hyphen")
        }
        return ToolOutcome.Ok("ANNOTATION_CLEAR")
    }
}

class AnnotationReplayTool : GenieTool {
    override val name = "annotation_replay"
    override val description = "Replay the current annotation session"

    override suspend fun execute(args: Map<String, String>, serviceContext: ToolServiceContext): ToolOutcome {
        val sessionId = args["session_id"]?.trim()
            ?: return ToolOutcome.LogicErr("Missing 'session_id' argument")
        if (!Regex("^[A-Za-z0-9_-]{1,64}$").matches(sessionId)) {
            return ToolOutcome.LogicErr("Invalid 'session_id'. Use letters, numbers, underscore, hyphen")
        }
        return ToolOutcome.Ok("ANNOTATION_REPLAY")
    }
}

private fun requireAnnotationSessionAndOp(args: Map<String, String>): ToolOutcome? {
    val sessionId = args["session_id"]?.trim()
        ?: return ToolOutcome.LogicErr("Missing 'session_id' argument")
    val opId = args["op_id"]?.trim()
        ?: return ToolOutcome.LogicErr("Missing 'op_id' argument")
    val idPattern = Regex("^[A-Za-z0-9_-]{1,64}$")
    if (!idPattern.matches(sessionId) || !idPattern.matches(opId)) {
        return ToolOutcome.LogicErr("Invalid 'session_id' or 'op_id'. Use letters, numbers, underscore, hyphen")
    }
    val delayMs = args["delay_ms"]?.trim()?.takeIf { it.isNotEmpty() }?.toLongOrNull() ?: 0L
    if (delayMs < 0L || delayMs > 20_000L) {
        return ToolOutcome.LogicErr("'delay_ms' must be between 0 and 20000")
    }
    return null
}
