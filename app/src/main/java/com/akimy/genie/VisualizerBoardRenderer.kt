package com.akimy.genie

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import com.akimy.genie.tools.BoardObject
import com.akimy.genie.tools.BoardStyle
import com.akimy.genie.tools.VisualizerScene
import com.akimy.genie.tools.boardObjectEndX
import com.akimy.genie.tools.boardObjectEndY
import com.akimy.genie.tools.currentStep
import com.akimy.genie.tools.visibleObjects
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class BoardViewport(
    val widthPx: Float,
    val heightPx: Float,
)

fun boardViewport(scene: VisualizerScene): BoardViewport {
    val board = scene.board ?: return BoardViewport(960f, 720f)
    val visibleObjects = if (board.objects.isEmpty()) emptyList() else board.objects
    val maxX = visibleObjects.maxOfOrNull { max(it.position.x, boardObjectEndX(it)) } ?: 840f
    val maxY = visibleObjects.maxOfOrNull { max(it.position.y, boardObjectEndY(it)) } ?: 540f
    return BoardViewport(
        widthPx = (maxX + 160f).coerceAtLeast(960f),
        heightPx = (maxY + 160f).coerceAtLeast(720f),
    )
}

fun boardCanvasBackground(theme: String?): Color {
    return when (theme?.lowercase()) {
        "blueprint" -> Color(0xFF23364A)
        "light" -> Color(0xFFF8FAFC)
        "neon_lab" -> Color(0xFF0F172A)
        else -> Color(0xFF16181D)
    }
}

fun boardSurfaceBackground(theme: String?): Color {
    return when (theme?.lowercase()) {
        "blueprint" -> Color(0xFF31475D)
        "light" -> Color(0xFFEFF3F8)
        "neon_lab" -> Color(0xFF111827)
        else -> Color(0xFF1D2128)
    }
}

fun boardCurrentStepLabel(scene: VisualizerScene): String? {
    val step = scene.board?.currentStep() ?: return null
    return if (step.title.isBlank()) step.stepId else "${step.stepId}: ${step.title}"
}

fun boardNarration(scene: VisualizerScene): String? {
    return scene.board?.narrationText?.takeIf { it.isNotBlank() }
}

fun DrawScope.drawTeachingBoard(scene: VisualizerScene) {
    val board = scene.board ?: return
    drawRect(color = boardCanvasBackground(board.theme))

    val focusedIds = board.focusObjectIds.toSet()
    val visibleObjects = board.visibleObjects().sortedBy { drawRank(it.objectType) }
    visibleObjects.forEach { obj ->
        drawBoardObject(
            obj = obj,
            isFocused = focusedIds.contains(obj.objectId),
            defaultTheme = board.theme,
        )
    }
}

private fun DrawScope.drawBoardObject(
    obj: BoardObject,
    isFocused: Boolean,
    defaultTheme: String,
) {
    val style = obj.style
    when (obj.objectType.lowercase()) {
        "title" -> drawTextBlock(obj, style, isFocused, center = false, monospace = false, bold = true, titleScale = 1.2f)
        "text" -> drawTextBlock(obj, style, isFocused, center = false, monospace = false)
        "code" -> drawCodeBlock(obj, style, isFocused)
        "box", "card" -> drawRoundedCard(obj, style, isFocused, defaultTheme)
        "circle" -> drawCircleObject(obj, style, isFocused, defaultTheme)
        "line" -> drawLineObject(obj, style, isFocused, arrow = false)
        "arrow" -> drawLineObject(obj, style, isFocused, arrow = true)
    }
}

private fun DrawScope.drawRoundedCard(
    obj: BoardObject,
    style: BoardStyle,
    isFocused: Boolean,
    defaultTheme: String,
) {
    val fill = style.fillColor.toBoardColor(
        when (defaultTheme.lowercase()) {
            "light" -> Color(0xFFFFFFFF)
            else -> Color(0xFF171A21)
        }
    ).copy(alpha = style.alpha.coerceIn(0.15f, 1f))
    val stroke = style.strokeColor.toBoardColor(
        if (isFocused) Color(0xFF31E7B6) else Color(0xFFE5E7EB)
    )
    val topLeft = Offset(obj.position.x, obj.position.y)
    val size = Size(obj.size.width.coerceAtLeast(8f), obj.size.height.coerceAtLeast(8f))
    val corner = style.cornerRadius.coerceAtLeast(8f)

    drawRoundRect(
        color = fill,
        topLeft = topLeft,
        size = size,
        cornerRadius = CornerRadius(corner, corner),
    )
    drawRoundRect(
        color = stroke,
        topLeft = topLeft,
        size = size,
        cornerRadius = CornerRadius(corner, corner),
        style = Stroke(width = if (isFocused) style.strokeWidth + 1.8f else style.strokeWidth),
    )
    if (isFocused) {
        drawRoundRect(
            color = Color(0x6631E7B6),
            topLeft = Offset(topLeft.x - 6f, topLeft.y - 6f),
            size = Size(size.width + 12f, size.height + 12f),
            cornerRadius = CornerRadius(corner + 6f, corner + 6f),
            style = Stroke(width = 2.2f),
        )
    }
    drawCenteredText(
        text = obj.text,
        left = obj.position.x + 16f,
        top = obj.position.y + 18f,
        width = obj.size.width - 32f,
        height = obj.size.height - 24f,
        style = style,
        textColor = style.textColor.toBoardColor(Color(0xFFF8FAFC)),
        bold = obj.objectType.equals("card", ignoreCase = true),
    )
}

private fun DrawScope.drawCircleObject(
    obj: BoardObject,
    style: BoardStyle,
    isFocused: Boolean,
    defaultTheme: String,
) {
    val diameter = min(obj.size.width, obj.size.height).coerceAtLeast(48f)
    val center = Offset(obj.position.x + (diameter / 2f), obj.position.y + (diameter / 2f))
    val fill = style.fillColor.toBoardColor(
        when (defaultTheme.lowercase()) {
            "light" -> Color(0xFFFFFFFF)
            else -> Color(0x191EE7B6)
        }
    ).copy(alpha = style.alpha.coerceIn(0.15f, 1f))
    val stroke = style.strokeColor.toBoardColor(if (isFocused) Color(0xFF31E7B6) else Color(0xFF31E7B6))

    drawCircle(
        color = fill,
        radius = diameter / 2f,
        center = center,
    )
    drawCircle(
        color = stroke,
        radius = diameter / 2f,
        center = center,
        style = Stroke(width = if (isFocused) style.strokeWidth + 1.4f else style.strokeWidth),
    )
    if (isFocused) {
        drawCircle(
            color = Color(0x6631E7B6),
            radius = (diameter / 2f) + 8f,
            center = center,
            style = Stroke(width = 2f),
        )
    }
    drawCenteredText(
        text = obj.text,
        left = obj.position.x + 12f,
        top = obj.position.y + 12f,
        width = diameter - 24f,
        height = diameter - 24f,
        style = style,
        textColor = style.textColor.toBoardColor(Color(0xFFFDE047)),
        bold = true,
    )
}

private fun DrawScope.drawLineObject(
    obj: BoardObject,
    style: BoardStyle,
    isFocused: Boolean,
    arrow: Boolean,
) {
    val stroke = style.strokeColor.toBoardColor(if (isFocused) Color(0xFF31E7B6) else Color(0xFFE5E7EB))
    val pathEffect = if (style.dashed) PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f) else null
    val start = Offset(obj.position.x, obj.position.y)
    val end = Offset(obj.position.x + obj.size.width, obj.position.y + obj.size.height)
    drawLine(
        color = stroke,
        start = start,
        end = end,
        strokeWidth = if (isFocused) style.strokeWidth + 1.4f else style.strokeWidth,
        pathEffect = pathEffect,
    )
    if (arrow) {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val len = sqrt((dx * dx) + (dy * dy)).takeIf { it > 0f } ?: 1f
        val ux = dx / len
        val uy = dy / len
        val arrowSize = 14f
        val left = Offset(
            end.x - (ux * arrowSize) - (uy * arrowSize * 0.6f),
            end.y - (uy * arrowSize) + (ux * arrowSize * 0.6f),
        )
        val right = Offset(
            end.x - (ux * arrowSize) + (uy * arrowSize * 0.6f),
            end.y - (uy * arrowSize) - (ux * arrowSize * 0.6f),
        )
        drawLine(stroke, end, left, if (isFocused) style.strokeWidth + 1.4f else style.strokeWidth)
        drawLine(stroke, end, right, if (isFocused) style.strokeWidth + 1.4f else style.strokeWidth)
    }
    if (obj.text.isNotBlank()) {
        val midX = (start.x + end.x) / 2f
        val midY = (start.y + end.y) / 2f
        drawContext.canvas.nativeCanvas.drawText(
            obj.text,
            midX,
            midY - 8f,
            textPaint(
                style = style,
                color = style.textColor.toBoardColor(Color(0xFFF8FAFC)),
                bold = false,
                monospace = false,
                align = Paint.Align.CENTER,
            )
        )
    }
}

private fun DrawScope.drawCodeBlock(
    obj: BoardObject,
    style: BoardStyle,
    isFocused: Boolean,
) {
    val fill = style.fillColor.toBoardColor(Color(0xFF0B1220)).copy(alpha = style.alpha.coerceIn(0.2f, 1f))
    val stroke = style.strokeColor.toBoardColor(if (isFocused) Color(0xFF4ADE80) else Color(0xFF1F2937))
    val topLeft = Offset(obj.position.x, obj.position.y)
    val size = Size(obj.size.width.coerceAtLeast(8f), obj.size.height.coerceAtLeast(8f))

    drawRoundRect(fill, topLeft, size, cornerRadius = CornerRadius(12f, 12f))
    drawRoundRect(
        stroke,
        topLeft,
        size,
        cornerRadius = CornerRadius(12f, 12f),
        style = Stroke(width = if (isFocused) style.strokeWidth + 1.2f else style.strokeWidth),
    )
    drawTextBlock(
        obj = obj,
        style = style.copy(textAlign = "start", textSize = style.textSize.coerceAtLeast(22f)),
        isFocused = isFocused,
        center = false,
        monospace = true,
    )
}

private fun DrawScope.drawTextBlock(
    obj: BoardObject,
    style: BoardStyle,
    isFocused: Boolean,
    center: Boolean,
    monospace: Boolean,
    bold: Boolean = false,
    titleScale: Float = 1f,
) {
    if (obj.text.isBlank()) return
    val align = if (center || style.textAlign.equals("center", ignoreCase = true)) {
        Paint.Align.CENTER
    } else {
        Paint.Align.LEFT
    }
    val paint = textPaint(
        style = style.copy(textSize = style.textSize * titleScale),
        color = style.textColor.toBoardColor(
            if (obj.objectType.equals("title", ignoreCase = true)) Color(0xFFF8FAFC) else Color(0xFFE5E7EB)
        ),
        bold = bold,
        monospace = monospace,
        align = align,
    )
    val x = if (align == Paint.Align.CENTER) obj.position.x + (obj.size.width / 2f) else obj.position.x
    val lines = wrapBoardText(obj.text, maxCharsPerLine = if (obj.objectType == "title") 40 else 28)
    val lineHeight = (paint.textSize * 1.18f)
    lines.forEachIndexed { index, line ->
        drawContext.canvas.nativeCanvas.drawText(
            line,
            x,
            obj.position.y + ((index + 1) * lineHeight),
            paint,
        )
    }
    if (isFocused && obj.objectType in setOf("title", "text", "code")) {
        val underlineY = obj.position.y + ((lines.size + 1) * lineHeight)
        drawLine(
            color = Color(0x6631E7B6),
            start = Offset(obj.position.x, underlineY),
            end = Offset(obj.position.x + obj.size.width.coerceAtLeast(180f), underlineY),
            strokeWidth = 2.2f,
        )
    }
}

private fun DrawScope.drawCenteredText(
    text: String,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    style: BoardStyle,
    textColor: Color,
    bold: Boolean,
) {
    val lines = wrapBoardText(text, maxCharsPerLine = 18)
    val paint = textPaint(
        style = style,
        color = textColor,
        bold = bold,
        monospace = false,
        align = Paint.Align.CENTER,
    )
    val lineHeight = paint.textSize * 1.12f
    val totalHeight = lines.size * lineHeight
    val startY = top + (height / 2f) - (totalHeight / 2f) + paint.textSize
    val centerX = left + (width / 2f)
    lines.forEachIndexed { index, line ->
        drawContext.canvas.nativeCanvas.drawText(
            line,
            centerX,
            startY + (index * lineHeight),
            paint,
        )
    }
}

private fun textPaint(
    style: BoardStyle,
    color: Color,
    bold: Boolean,
    monospace: Boolean,
    align: Paint.Align,
): Paint {
    return Paint().apply {
        isAntiAlias = true
        this.color = color.toArgbCompat()
        textSize = style.textSize.coerceIn(14f, 44f)
        textAlign = align
        typeface = when {
            monospace -> Typeface.MONOSPACE
            bold -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            else -> Typeface.DEFAULT
        }
    }
}

private fun String?.toBoardColor(fallback: Color): Color {
    if (this.isNullOrBlank()) return fallback
    return runCatching { Color(android.graphics.Color.parseColor(this.trim())) }.getOrElse { fallback }
}

private fun Color.toArgbCompat(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt(),
)

private fun drawRank(type: String): Int {
    return when (type.lowercase()) {
        "line", "arrow" -> 0
        "box", "card", "circle" -> 1
        "title", "text", "code" -> 2
        else -> 3
    }
}

private fun wrapBoardText(text: String, maxCharsPerLine: Int): List<String> {
    if (text.length <= maxCharsPerLine) return listOf(text)
    val words = text.split(Regex("\\s+"))
    if (words.size == 1) return text.chunked(maxCharsPerLine)

    val lines = mutableListOf<String>()
    var current = ""
    for (word in words) {
        val candidate = if (current.isBlank()) word else "$current $word"
        if (candidate.length <= maxCharsPerLine) {
            current = candidate
        } else {
            if (current.isNotBlank()) lines += current
            current = word
        }
    }
    if (current.isNotBlank()) lines += current
    return lines
}
