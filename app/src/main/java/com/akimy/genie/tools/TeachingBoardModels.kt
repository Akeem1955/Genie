package com.akimy.genie.tools

import kotlinx.serialization.Serializable

private val LINE_OBJECT_TYPES = setOf("line", "arrow")

@Serializable
data class BoardPoint(
    val x: Float = 0f,
    val y: Float = 0f,
)

@Serializable
data class BoardSize(
    val width: Float = 180f,
    val height: Float = 96f,
)

@Serializable
data class BoardStyle(
    val fillColor: String? = null,
    val strokeColor: String? = null,
    val textColor: String? = null,
    val accentColor: String? = null,
    val strokeWidth: Float = 2f,
    val cornerRadius: Float = 18f,
    val textSize: Float = 24f,
    val textAlign: String = "center",
    val dashed: Boolean = false,
    val alpha: Float = 1f,
)

@Serializable
data class BoardObject(
    val objectId: String,
    val objectType: String,
    val text: String = "",
    val position: BoardPoint = BoardPoint(),
    val size: BoardSize = BoardSize(),
    val style: BoardStyle = BoardStyle(),
    val stepId: String? = null,
    val animation: String? = null,
    val visible: Boolean = true,
)

@Serializable
data class BoardStep(
    val stepId: String,
    val title: String = "",
    val narration: String = "",
)

@Serializable
data class TeachingBoard(
    val theme: String = "dark_classroom",
    val objects: List<BoardObject> = emptyList(),
    val steps: List<BoardStep> = emptyList(),
    val currentStepId: String? = null,
    val narrationText: String = "",
    val focusObjectIds: List<String> = emptyList(),
)

fun TeachingBoard.stepIndex(stepId: String?): Int {
    if (stepId.isNullOrBlank()) return -1
    return steps.indexOfFirst { it.stepId == stepId }
}

fun TeachingBoard.currentStepIndex(): Int {
    if (steps.isEmpty()) return Int.MAX_VALUE
    val index = stepIndex(currentStepId)
    return if (index >= 0) index else -1
}

fun TeachingBoard.currentStep(): BoardStep? = steps.firstOrNull { it.stepId == currentStepId }

fun TeachingBoard.visibleObjects(): List<BoardObject> {
    if (steps.isEmpty()) {
        return objects.filter { it.visible }
    }

    val currentIndex = currentStepIndex()
    return objects.filter { obj ->
        if (!obj.visible) return@filter false
        val revealIndex = stepIndex(obj.stepId)
        revealIndex == -1 || revealIndex <= currentIndex
    }
}

fun TeachingBoard.withCurrentStep(stepId: String?): TeachingBoard {
    if (stepId.isNullOrBlank()) return copy(currentStepId = null)
    val step = steps.firstOrNull { it.stepId == stepId } ?: return this
    val focusIds = objects.filter { it.stepId == step.stepId }.map { it.objectId }
    val narration = step.narration.ifBlank { narrationText }
    return copy(
        currentStepId = step.stepId,
        narrationText = narration,
        focusObjectIds = focusIds,
    )
}

fun TeachingBoard.withNextStep(): TeachingBoard {
    if (steps.isEmpty()) return this
    val currentIndex = currentStepIndex()
    val nextIndex = if (currentIndex < 0) 0 else (currentIndex + 1).coerceAtMost(steps.lastIndex)
    return withCurrentStep(steps[nextIndex].stepId)
}

fun TeachingBoard.withPreviousStep(): TeachingBoard {
    if (steps.isEmpty()) return this
    val currentIndex = currentStepIndex()
    val previousIndex = if (currentIndex < 0) 0 else (currentIndex - 1).coerceAtLeast(0)
    return withCurrentStep(steps[previousIndex].stepId)
}

fun TeachingBoard.withReplayStep(): TeachingBoard {
    val activeStepId = currentStepId ?: steps.firstOrNull()?.stepId
    return withCurrentStep(activeStepId)
}

fun defaultBoardSizeFor(type: String): BoardSize {
    return when (type.lowercase()) {
        "title" -> BoardSize(width = 520f, height = 56f)
        "text" -> BoardSize(width = 320f, height = 60f)
        "code" -> BoardSize(width = 420f, height = 72f)
        "circle" -> BoardSize(width = 140f, height = 140f)
        "line", "arrow" -> BoardSize(width = 220f, height = 0f)
        "box" -> BoardSize(width = 220f, height = 140f)
        "card" -> BoardSize(width = 260f, height = 160f)
        else -> BoardSize()
    }
}

fun boardObjectEndX(obj: BoardObject): Float {
    return if (obj.objectType.lowercase() in LINE_OBJECT_TYPES) {
        obj.position.x + obj.size.width
    } else {
        obj.position.x + obj.size.width.coerceAtLeast(1f)
    }
}

fun boardObjectEndY(obj: BoardObject): Float {
    return if (obj.objectType.lowercase() in LINE_OBJECT_TYPES) {
        obj.position.y + obj.size.height
    } else {
        obj.position.y + obj.size.height.coerceAtLeast(1f)
    }
}
