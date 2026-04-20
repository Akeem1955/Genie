package com.akimy.genie.tools

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

private const val MAX_ANNOTATION_OPS = 48
private const val MAX_ANNOTATION_TEXT = 180
private const val MAX_ANNOTATION_REPLAY_DELAY_MS = 20_000L

private data class AnnotationSession(
    val sessionId: String,
    val title: String,
    val viewport: ViewportInfo,
    val operations: MutableList<AnnotationOp> = mutableListOf(),
)

private sealed class AnnotationOp {
    abstract val opId: String
    abstract val delayMs: Long

    data class Box(
        override val opId: String,
        override val delayMs: Long,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val label: String,
        val style: BoardStyle,
    ) : AnnotationOp()

    data class Label(
        override val opId: String,
        override val delayMs: Long,
        val x: Float,
        val y: Float,
        val text: String,
        val style: BoardStyle,
    ) : AnnotationOp()

    data class Pointer(
        override val opId: String,
        override val delayMs: Long,
        val x: Float,
        val y: Float,
        val targetX: Float,
        val targetY: Float,
        val text: String,
        val style: BoardStyle,
    ) : AnnotationOp()
}

object AnnotationSessionStore {
    private val sessions = ConcurrentHashMap<String, AnnotationSession>()
    private val json = Json { encodeDefaults = true }

    fun startSession(
        sessionId: String,
        title: String,
        viewport: ViewportInfo,
    ): SceneStoreResult {
        val trimmedTitle = title.trim().ifBlank { "Screen Annotation" }.take(120)
        val session = AnnotationSession(
            sessionId = sessionId,
            title = trimmedTitle,
            viewport = viewport,
        )
        sessions[sessionId] = session
        return publish(session)
    }

    fun addBox(
        sessionId: String,
        opId: String,
        delayMs: Long,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        label: String,
        style: BoardStyle,
    ): SceneStoreResult {
        val session = sessions[sessionId] ?: return SceneStoreResult(false, "Annotation session '$sessionId' not found")
        if (session.operations.size >= MAX_ANNOTATION_OPS) {
            return SceneStoreResult(false, "Too many annotation operations. Max is $MAX_ANNOTATION_OPS")
        }
        session.operations += AnnotationOp.Box(
            opId = opId,
            delayMs = delayMs,
            x = clampX(x, session.viewport),
            y = clampY(y, session.viewport),
            width = width.coerceAtLeast(8f).coerceAtMost(session.viewport.widthPx.toFloat()),
            height = height.coerceAtLeast(8f).coerceAtMost(session.viewport.heightPx.toFloat()),
            label = label.take(MAX_ANNOTATION_TEXT),
            style = style,
        )
        return publish(session)
    }

    fun addLabel(
        sessionId: String,
        opId: String,
        delayMs: Long,
        x: Float,
        y: Float,
        text: String,
        style: BoardStyle,
    ): SceneStoreResult {
        val session = sessions[sessionId] ?: return SceneStoreResult(false, "Annotation session '$sessionId' not found")
        if (session.operations.size >= MAX_ANNOTATION_OPS) {
            return SceneStoreResult(false, "Too many annotation operations. Max is $MAX_ANNOTATION_OPS")
        }
        session.operations += AnnotationOp.Label(
            opId = opId,
            delayMs = delayMs,
            x = clampX(x, session.viewport),
            y = clampY(y, session.viewport),
            text = text.take(MAX_ANNOTATION_TEXT),
            style = style,
        )
        return publish(session)
    }

    fun addPointer(
        sessionId: String,
        opId: String,
        delayMs: Long,
        x: Float,
        y: Float,
        targetX: Float,
        targetY: Float,
        text: String,
        style: BoardStyle,
    ): SceneStoreResult {
        val session = sessions[sessionId] ?: return SceneStoreResult(false, "Annotation session '$sessionId' not found")
        if (session.operations.size >= MAX_ANNOTATION_OPS) {
            return SceneStoreResult(false, "Too many annotation operations. Max is $MAX_ANNOTATION_OPS")
        }
        session.operations += AnnotationOp.Pointer(
            opId = opId,
            delayMs = delayMs,
            x = clampX(x, session.viewport),
            y = clampY(y, session.viewport),
            targetX = clampX(targetX, session.viewport),
            targetY = clampY(targetY, session.viewport),
            text = text.take(MAX_ANNOTATION_TEXT),
            style = style,
        )
        return publish(session)
    }

    suspend fun replaySession(sessionId: String): SceneStoreResult {
        val session = sessions[sessionId] ?: return SceneStoreResult(false, "Annotation session '$sessionId' not found")
        val sceneId = sceneIdFor(sessionId)

        val introResult = VisualizerSceneStore.boardRevealStep(sceneId, "step_intro")
        if (!introResult.ok) return introResult
        if (session.operations.isEmpty()) return introResult

        for ((index, op) in session.operations.withIndex()) {
            val waitMs = op.delayMs.coerceIn(0L, MAX_ANNOTATION_REPLAY_DELAY_MS)
            if (waitMs > 0L) {
                delay(waitMs)
            }
            val stepResult = VisualizerSceneStore.boardRevealStep(sceneId, "step_${index + 1}")
            if (!stepResult.ok) return stepResult
        }

        return SceneStoreResult(
            ok = true,
            message = "Replayed annotation session '$sessionId' (${session.operations.size} steps)",
        )
    }

    fun clearSession(sessionId: String): SceneStoreResult {
        sessions.remove(sessionId)
        return VisualizerSceneStore.clearScene(sceneIdFor(sessionId))
    }

    private fun publish(session: AnnotationSession): SceneStoreResult {
        val steps = mutableListOf(
            BoardStep(
                stepId = "step_intro",
                title = "Start",
                narration = "Preparing annotation overlays",
            )
        )
        val objects = mutableListOf<BoardObject>()

        session.operations.forEachIndexed { index, op ->
            val stepId = "step_${index + 1}"
            val narration = when (op) {
                is AnnotationOp.Box -> if (op.label.isBlank()) "Highlight region" else op.label
                is AnnotationOp.Label -> op.text.ifBlank { "Label" }
                is AnnotationOp.Pointer -> op.text.ifBlank { "Pointer" }
            }
            val delayNarration = if (op.delayMs > 0) " (delay ${op.delayMs}ms)" else ""
            steps += BoardStep(
                stepId = stepId,
                title = "Annotation ${index + 1}",
                narration = narration + delayNarration,
            )

            when (op) {
                is AnnotationOp.Box -> {
                    objects += BoardObject(
                        objectId = "${op.opId}_box",
                        objectType = "box",
                        text = "",
                        position = BoardPoint(op.x, op.y),
                        size = BoardSize(op.width, op.height),
                        style = op.style,
                        stepId = stepId,
                        animation = "reveal",
                    )
                    if (op.label.isNotBlank()) {
                        objects += BoardObject(
                            objectId = "${op.opId}_label",
                            objectType = "text",
                            text = op.label,
                            position = BoardPoint(op.x, (op.y - 42f).coerceAtLeast(8f)),
                            size = BoardSize(width = (op.width * 0.8f).coerceAtLeast(140f), height = 38f),
                            style = op.style.copy(textAlign = "start"),
                            stepId = stepId,
                            animation = "reveal",
                        )
                    }
                }

                is AnnotationOp.Label -> {
                    objects += BoardObject(
                        objectId = "${op.opId}_label",
                        objectType = "text",
                        text = op.text,
                        position = BoardPoint(op.x, op.y),
                        size = BoardSize(width = 280f, height = 40f),
                        style = op.style.copy(textAlign = "start"),
                        stepId = stepId,
                        animation = "reveal",
                    )
                }

                is AnnotationOp.Pointer -> {
                    objects += BoardObject(
                        objectId = "${op.opId}_arrow",
                        objectType = "arrow",
                        text = "",
                        position = BoardPoint(op.x, op.y),
                        size = BoardSize(op.targetX - op.x, op.targetY - op.y),
                        style = op.style,
                        stepId = stepId,
                        animation = "trace",
                    )
                    objects += BoardObject(
                        objectId = "${op.opId}_dot",
                        objectType = "circle",
                        text = "",
                        position = BoardPoint(op.x - 10f, op.y - 10f),
                        size = BoardSize(20f, 20f),
                        style = op.style.copy(fillColor = op.style.strokeColor ?: "#FACC15"),
                        stepId = stepId,
                        animation = "pulse",
                    )
                    if (op.text.isNotBlank()) {
                        val labelX = (op.targetX + 12f).coerceAtMost(session.viewport.widthPx - 220f)
                        val labelY = (op.targetY + 12f).coerceAtMost(session.viewport.heightPx - 48f)
                        objects += BoardObject(
                            objectId = "${op.opId}_label",
                            objectType = "text",
                            text = op.text,
                            position = BoardPoint(labelX, labelY),
                            size = BoardSize(width = 220f, height = 36f),
                            style = op.style.copy(textAlign = "start"),
                            stepId = stepId,
                            animation = "reveal",
                        )
                    }
                }
            }
        }

        val teachResult = VisualizerSceneStore.teachWithBoard(
            sceneId = sceneIdFor(session.sessionId),
            title = session.title,
            theme = "dark_classroom",
            objectsJson = json.encodeToString(objects),
            stepsJson = json.encodeToString(steps),
            narrationText = "Annotation session ready",
        )
        if (!teachResult.ok) return teachResult

        val revealStepId = if (session.operations.isEmpty()) "step_intro" else "step_${session.operations.size}"
        return VisualizerSceneStore.boardRevealStep(sceneIdFor(session.sessionId), revealStepId)
    }

    private fun sceneIdFor(sessionId: String): String = "annot_$sessionId"

    private fun clampX(x: Float, viewport: ViewportInfo): Float {
        return x.coerceIn(0f, viewport.widthPx.toFloat())
    }

    private fun clampY(y: Float, viewport: ViewportInfo): Float {
        return y.coerceIn(0f, viewport.heightPx.toFloat())
    }

    fun normalizedToPx(raw: Float, maxPx: Int): Float {
        val bounded = when {
            raw in 0f..1f -> raw * maxPx
            raw in 0f..1000f -> (raw / 1000f) * maxPx
            else -> raw
        }
        return bounded.coerceIn(0f, maxPx.toFloat())
    }

    fun spanToPx(startRaw: Float, endRaw: Float, maxPx: Int): Float {
        val start = normalizedToPx(startRaw, maxPx)
        val end = normalizedToPx(endRaw, maxPx)
        return abs(end - start).coerceAtLeast(8f)
    }
}
