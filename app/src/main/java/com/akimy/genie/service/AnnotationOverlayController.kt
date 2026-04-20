package com.akimy.genie.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.akimy.genie.tools.BoardStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.sqrt

private const val MAX_OVERLAY_REPLAY_DELAY_MS = 20_000L

private sealed class OverlayItem {
    abstract val sessionId: String
    abstract val opId: String
    abstract val delayMs: Long

    data class Box(
        override val sessionId: String,
        override val opId: String,
        override val delayMs: Long,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val label: String,
        val style: BoardStyle,
    ) : OverlayItem()

    data class Label(
        override val sessionId: String,
        override val opId: String,
        override val delayMs: Long,
        val x: Float,
        val y: Float,
        val text: String,
        val style: BoardStyle,
    ) : OverlayItem()

    data class Pointer(
        override val sessionId: String,
        override val opId: String,
        override val delayMs: Long,
        val x: Float,
        val y: Float,
        val targetX: Float,
        val targetY: Float,
        val text: String,
        val style: BoardStyle,
    ) : OverlayItem()
}

internal class AnnotationOverlayController(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: AnnotationOverlayView? = null
    private val items = mutableListOf<OverlayItem>()
    private var replayJob: Job? = null

    private fun ensureOverlay() {
        if (overlayView != null) return
        val view = AnnotationOverlayView(context)
        val lp = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = (
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                )
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.TOP or Gravity.START
        }
        windowManager.addView(view, lp)
        overlayView = view
    }

    fun startSession(sessionId: String) {
        replayJob?.cancel()
        ensureOverlay()
        items.removeAll { it.sessionId == sessionId }
        render()
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
    ) {
        ensureOverlay()
        items += OverlayItem.Box(sessionId, opId, delayMs, x, y, width, height, label, style)
        render()
    }

    fun addLabel(
        sessionId: String,
        opId: String,
        delayMs: Long,
        x: Float,
        y: Float,
        text: String,
        style: BoardStyle,
    ) {
        ensureOverlay()
        items += OverlayItem.Label(sessionId, opId, delayMs, x, y, text, style)
        render()
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
    ) {
        ensureOverlay()
        items += OverlayItem.Pointer(sessionId, opId, delayMs, x, y, targetX, targetY, text, style)
        render()
    }

    fun clearSession(sessionId: String) {
        replayJob?.cancel()
        items.removeAll { it.sessionId == sessionId }
        render()
        if (items.isEmpty()) {
            detach()
        }
    }

    fun replaySession(sessionId: String, scope: CoroutineScope) {
        val sessionItems = items.filter { it.sessionId == sessionId }
        if (sessionItems.isEmpty()) return
        ensureOverlay()
        replayJob?.cancel()

        val baseline = items.filterNot { it.sessionId == sessionId }
        replayJob = scope.launch(Dispatchers.Main) {
            overlayView?.setItems(baseline)
            val replayList = baseline.toMutableList()
            sessionItems.forEach { item ->
                val waitMs = item.delayMs.coerceIn(0L, MAX_OVERLAY_REPLAY_DELAY_MS)
                if (waitMs > 0L) {
                    delay(waitMs)
                }
                replayList += item
                overlayView?.setItems(replayList)
            }
        }
    }

    fun detach() {
        replayJob?.cancel()
        val view = overlayView ?: return
        runCatching {
            windowManager.removeView(view)
        }
        overlayView = null
        items.clear()
    }

    private fun render() {
        overlayView?.setItems(items.toList())
    }
}

private class AnnotationOverlayView(context: Context) : View(context) {
    private var items: List<OverlayItem> = emptyList()

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#38BDF8")
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#331E293B")
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = 34f
        color = Color.WHITE
    }

    fun setItems(newItems: List<OverlayItem>) {
        items = newItems
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        items.forEach { item ->
            when (item) {
                is OverlayItem.Box -> drawBox(canvas, item)
                is OverlayItem.Label -> drawLabel(canvas, item)
                is OverlayItem.Pointer -> drawPointer(canvas, item)
            }
        }
    }

    private fun drawBox(canvas: Canvas, item: OverlayItem.Box) {
        applyStyle(item.style)
        val rect = RectF(item.x, item.y, item.x + max(8f, item.width), item.y + max(8f, item.height))
        canvas.drawRoundRect(rect, 18f, 18f, fillPaint)
        canvas.drawRoundRect(rect, 18f, 18f, strokePaint)
        if (item.label.isNotBlank()) {
            canvas.drawText(item.label, item.x + 10f, (item.y - 14f).coerceAtLeast(36f), textPaint)
        }
    }

    private fun drawLabel(canvas: Canvas, item: OverlayItem.Label) {
        applyStyle(item.style)
        if (item.text.isNotBlank()) {
            canvas.drawText(item.text, item.x, item.y, textPaint)
        }
    }

    private fun drawPointer(canvas: Canvas, item: OverlayItem.Pointer) {
        applyStyle(item.style)
        canvas.drawLine(item.x, item.y, item.targetX, item.targetY, strokePaint)
        canvas.drawCircle(item.x, item.y, 10f, fillPaint)
        canvas.drawCircle(item.x, item.y, 10f, strokePaint)

        val dx = item.targetX - item.x
        val dy = item.targetY - item.y
        val len = sqrt((dx * dx) + (dy * dy)).takeIf { it > 0f } ?: 1f
        val ux = dx / len
        val uy = dy / len
        val head = 16f
        val leftX = item.targetX - (ux * head) - (uy * head * 0.6f)
        val leftY = item.targetY - (uy * head) + (ux * head * 0.6f)
        val rightX = item.targetX - (ux * head) + (uy * head * 0.6f)
        val rightY = item.targetY - (uy * head) - (ux * head * 0.6f)

        val arrowPath = Path().apply {
            moveTo(item.targetX, item.targetY)
            lineTo(leftX, leftY)
            moveTo(item.targetX, item.targetY)
            lineTo(rightX, rightY)
        }
        canvas.drawPath(arrowPath, strokePaint)

        if (item.text.isNotBlank()) {
            canvas.drawText(item.text, item.targetX + 12f, item.targetY - 10f, textPaint)
        }
    }

    private fun applyStyle(style: BoardStyle) {
        val strokeColor = parseColor(style.strokeColor, Color.parseColor("#38BDF8"))
        val fillColor = parseColor(style.fillColor, Color.parseColor("#331E293B"))
        val textColor = parseColor(style.textColor, Color.WHITE)
        strokePaint.color = strokeColor
        strokePaint.strokeWidth = style.strokeWidth.coerceIn(1.2f, 10f)
        fillPaint.color = withAlpha(fillColor, style.alpha.coerceIn(0.08f, 1f))
        textPaint.color = textColor
        textPaint.textSize = style.textSize.coerceIn(18f, 52f)
    }

    private fun parseColor(raw: String?, fallback: Int): Int {
        if (raw.isNullOrBlank()) return fallback
        return runCatching { Color.parseColor(raw) }.getOrElse { fallback }
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha * 255f).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }
}
