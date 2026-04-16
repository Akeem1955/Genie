package com.akimy.genie.tools.impl

import com.akimy.genie.agent.ToolOutcome
import com.akimy.genie.tools.GenieTool
import com.akimy.genie.tools.ToolServiceContext

// ============================================================================
// OS Interaction Tools — adapted from BetaAssist's action dispatch
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

// ============================================================================
// Persistence Tools — interface to Room FactStore
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
