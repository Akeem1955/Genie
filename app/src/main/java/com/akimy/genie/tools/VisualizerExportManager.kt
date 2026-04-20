package com.akimy.genie.tools

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object VisualizerExportManager {
    fun exportSceneAsPng(context: Context, snapshot: SceneSnapshot): Uri? {
        return runCatching {
            val bitmap = renderSceneBitmap(snapshot)
            val folder = File(context.cacheDir, "visualizer_exports").apply { mkdirs() }
            val safeId = snapshot.scene.sceneId.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val filename = "scene_${safeId}_${System.currentTimeMillis()}.png"
            val file = File(folder, filename)

            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    }

    fun buildSharePngIntent(uri: Uri): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun renderSceneBitmap(snapshot: SceneSnapshot): Bitmap {
        if (snapshot.scene.board != null || snapshot.scene.diagramType.equals("board", ignoreCase = true)) {
            return renderBoardBitmap(snapshot.scene)
        }

        val layout = snapshot.layout
        val scene = snapshot.scene
        val focusSet = scene.focusNodeIds.toSet()
        val maxX = (layout.nodeLayouts.maxOfOrNull { it.x + it.width } ?: 800f) + 120f
        val maxY = (layout.nodeLayouts.maxOfOrNull { it.y + it.height } ?: 520f) + 120f

        val width = maxX.toInt().coerceAtLeast(960)
        val height = maxY.toInt().coerceAtLeast(720)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.parseColor("#FCFDFE"))

        val edgePaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val nodeFill = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
        }
        val nodeBorder = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
        }
        val textPaint = Paint().apply {
            isAntiAlias = true
            color = Color.parseColor("#0F172A")
            textSize = 24f
        }

        layout.edgeLayouts.forEach { edge ->
            val focused = edge.from in focusSet || edge.to in focusSet
            edgePaint.color = Color.parseColor(if (focused) "#0B8A5A" else "#334155")
            edgePaint.strokeWidth = if (focused) 3.2f else 2.1f
            val points = edge.points
            for (i in 0 until points.lastIndex) {
                val from = points[i]
                val to = points[i + 1]
                canvas.drawLine(from.x, from.y, to.x, to.y, edgePaint)
            }
            if (points.size >= 2) {
                val tip = points.last()
                val arrow = 8f
                canvas.drawLine(tip.x, tip.y, tip.x - arrow, tip.y - arrow, edgePaint)
                canvas.drawLine(tip.x, tip.y, tip.x - arrow, tip.y + arrow, edgePaint)
            }
        }

        layout.nodeLayouts.forEach { node ->
            val focused = node.id in focusSet
            nodeFill.color = Color.parseColor(if (focused) "#DCFCE7" else "#FFFFFF")
            nodeBorder.color = Color.parseColor(if (focused) "#0B8A5A" else "#1E293B")
            nodeBorder.strokeWidth = if (focused) 2.7f else 1.6f

            val left = node.x
            val top = node.y
            val right = node.x + node.width
            val bottom = node.y + node.height
            canvas.drawRoundRect(left, top, right, bottom, 14f, 14f, nodeFill)
            canvas.drawRoundRect(left, top, right, bottom, 14f, 14f, nodeBorder)

            val label = scene.nodes.firstOrNull { it.id == node.id }?.label ?: node.id
            val lines = wrapLabelForNode(label)
            val lineHeight = 21f
            val startY = node.y + (node.height / 2f) - ((lines.size - 1) * lineHeight / 2f) + 7f
            lines.forEachIndexed { idx, line ->
                canvas.drawText(line, node.x + 12f, startY + (idx * lineHeight), textPaint)
            }
        }

        return bitmap
    }

    private fun renderBoardBitmap(scene: VisualizerScene): Bitmap {
        val board = scene.board ?: TeachingBoard()
        val visibleObjects = if (board.objects.isEmpty()) emptyList() else board.objects
        val maxX = visibleObjects.maxOfOrNull { max(it.position.x, boardObjectEndX(it)) } ?: 840f
        val maxY = visibleObjects.maxOfOrNull { max(it.position.y, boardObjectEndY(it)) } ?: 540f
        val width = (maxX + 160f).toInt().coerceAtLeast(960)
        val height = (maxY + 160f).toInt().coerceAtLeast(720)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(themeBackground(board.theme))

        val focusedIds = board.focusObjectIds.toSet()
        board.visibleObjects()
            .sortedBy { drawRank(it.objectType) }
            .forEach { obj ->
                when (obj.objectType.lowercase()) {
                    "title" -> drawTextBlock(canvas, obj, focusedIds.contains(obj.objectId), bold = true, monospace = false, titleScale = 1.2f)
                    "text" -> drawTextBlock(canvas, obj, focusedIds.contains(obj.objectId), bold = false, monospace = false)
                    "code" -> drawCodeBlock(canvas, obj, focusedIds.contains(obj.objectId))
                    "box", "card" -> drawBox(canvas, obj, focusedIds.contains(obj.objectId), board.theme)
                    "circle" -> drawCircle(canvas, obj, focusedIds.contains(obj.objectId))
                    "line" -> drawLine(canvas, obj, focusedIds.contains(obj.objectId), arrow = false)
                    "arrow" -> drawLine(canvas, obj, focusedIds.contains(obj.objectId), arrow = true)
                }
            }

        return bitmap
    }

    fun wrapLabelForNode(text: String, maxCharsPerLine: Int = 16, maxLines: Int = 3): List<String> {
        if (text.length <= maxCharsPerLine) return listOf(text)
        val words = text.split(Regex("\\s+"))
        if (words.size == 1) {
            return text.chunked(maxCharsPerLine).take(maxLines)
        }

        val lines = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isBlank()) word else "$current $word"
            if (candidate.length <= maxCharsPerLine) {
                current = candidate
            } else {
                if (current.isNotBlank()) lines += current
                current = word
                if (lines.size == maxLines - 1) break
            }
        }
        if (lines.size < maxLines && current.isNotBlank()) lines += current
        return lines.take(maxLines)
    }

    private fun drawBox(canvas: Canvas, obj: BoardObject, focused: Boolean, theme: String) {
        val fillPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = parseColor(
                obj.style.fillColor,
                if (theme.equals("light", ignoreCase = true)) "#FFFFFF" else "#171A21"
            )
            alpha = (obj.style.alpha.coerceIn(0.15f, 1f) * 255).toInt()
        }
        val borderPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            color = parseColor(obj.style.strokeColor, if (focused) "#31E7B6" else "#E5E7EB")
            strokeWidth = if (focused) obj.style.strokeWidth + 1.8f else obj.style.strokeWidth
        }
        val left = obj.position.x
        val top = obj.position.y
        val right = left + obj.size.width
        val bottom = top + obj.size.height
        val radius = obj.style.cornerRadius.coerceAtLeast(8f)
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, fillPaint)
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, borderPaint)
        if (focused) {
            val glow = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.STROKE
                color = Color.parseColor("#6631E7B6")
                strokeWidth = 2.2f
            }
            canvas.drawRoundRect(left - 6f, top - 6f, right + 6f, bottom + 6f, radius + 6f, radius + 6f, glow)
        }
        drawCenteredText(
            canvas = canvas,
            text = obj.text,
            centerX = left + (obj.size.width / 2f),
            centerY = top + (obj.size.height / 2f),
            maxCharsPerLine = 18,
            paint = textPaint(obj, parseColor(obj.style.textColor, "#F8FAFC"), align = Paint.Align.CENTER, bold = obj.objectType.equals("card", ignoreCase = true)),
        )
    }

    private fun drawCircle(canvas: Canvas, obj: BoardObject, focused: Boolean) {
        val diameter = min(obj.size.width, obj.size.height).coerceAtLeast(48f)
        val centerX = obj.position.x + (diameter / 2f)
        val centerY = obj.position.y + (diameter / 2f)
        val fillPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = parseColor(obj.style.fillColor, "#191EE7B6")
            alpha = (obj.style.alpha.coerceIn(0.15f, 1f) * 255).toInt()
        }
        val borderPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            color = parseColor(obj.style.strokeColor, "#31E7B6")
            strokeWidth = if (focused) obj.style.strokeWidth + 1.4f else obj.style.strokeWidth
        }
        canvas.drawCircle(centerX, centerY, diameter / 2f, fillPaint)
        canvas.drawCircle(centerX, centerY, diameter / 2f, borderPaint)
        drawCenteredText(
            canvas = canvas,
            text = obj.text,
            centerX = centerX,
            centerY = centerY,
            maxCharsPerLine = 16,
            paint = textPaint(obj, parseColor(obj.style.textColor, "#FDE047"), Paint.Align.CENTER, bold = true),
        )
    }

    private fun drawLine(canvas: Canvas, obj: BoardObject, focused: Boolean, arrow: Boolean) {
        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            color = parseColor(obj.style.strokeColor, if (focused) "#31E7B6" else "#E5E7EB")
            strokeWidth = if (focused) obj.style.strokeWidth + 1.4f else obj.style.strokeWidth
        }
        val startX = obj.position.x
        val startY = obj.position.y
        val endX = startX + obj.size.width
        val endY = startY + obj.size.height
        canvas.drawLine(startX, startY, endX, endY, paint)
        if (arrow) {
            val dx = endX - startX
            val dy = endY - startY
            val len = sqrt((dx * dx) + (dy * dy)).takeIf { it > 0f } ?: 1f
            val ux = dx / len
            val uy = dy / len
            val arrowSize = 14f
            canvas.drawLine(endX, endY, endX - (ux * arrowSize) - (uy * arrowSize * 0.6f), endY - (uy * arrowSize) + (ux * arrowSize * 0.6f), paint)
            canvas.drawLine(endX, endY, endX - (ux * arrowSize) + (uy * arrowSize * 0.6f), endY - (uy * arrowSize) - (ux * arrowSize * 0.6f), paint)
        }
        if (obj.text.isNotBlank()) {
            canvas.drawText(
                obj.text,
                (startX + endX) / 2f,
                ((startY + endY) / 2f) - 8f,
                textPaint(obj, parseColor(obj.style.textColor, "#F8FAFC"), Paint.Align.CENTER, bold = false),
            )
        }
    }

    private fun drawCodeBlock(canvas: Canvas, obj: BoardObject, focused: Boolean) {
        val fillPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = parseColor(obj.style.fillColor, "#0B1220")
            alpha = (obj.style.alpha.coerceIn(0.2f, 1f) * 255).toInt()
        }
        val borderPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            color = parseColor(obj.style.strokeColor, if (focused) "#4ADE80" else "#1F2937")
            strokeWidth = if (focused) obj.style.strokeWidth + 1.2f else obj.style.strokeWidth
        }
        val left = obj.position.x
        val top = obj.position.y
        val right = left + obj.size.width
        val bottom = top + obj.size.height
        canvas.drawRoundRect(left, top, right, bottom, 12f, 12f, fillPaint)
        canvas.drawRoundRect(left, top, right, bottom, 12f, 12f, borderPaint)
        val paint = textPaint(obj, parseColor(obj.style.textColor, "#4ADE80"), Paint.Align.LEFT, bold = false, monospace = true)
        val lines = wrapLabelForNode(obj.text, maxCharsPerLine = 26, maxLines = 6)
        val lineHeight = paint.textSize * 1.18f
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, left + 16f, top + 24f + ((index + 1) * lineHeight), paint)
        }
    }

    private fun drawTextBlock(canvas: Canvas, obj: BoardObject, focused: Boolean, bold: Boolean, monospace: Boolean, titleScale: Float = 1f) {
        if (obj.text.isBlank()) return
        val align = if (obj.style.textAlign.equals("center", ignoreCase = true)) Paint.Align.CENTER else Paint.Align.LEFT
        val paint = textPaint(
            obj = obj.copy(style = obj.style.copy(textSize = obj.style.textSize * titleScale)),
            color = parseColor(obj.style.textColor, if (obj.objectType.equals("title", ignoreCase = true)) "#F8FAFC" else "#E5E7EB"),
            align = align,
            bold = bold,
            monospace = monospace,
        )
        val x = if (align == Paint.Align.CENTER) obj.position.x + (obj.size.width / 2f) else obj.position.x
        val lines = wrapLabelForNode(obj.text, maxCharsPerLine = if (obj.objectType.equals("title", ignoreCase = true)) 40 else 28, maxLines = 8)
        val lineHeight = paint.textSize * 1.18f
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, x, obj.position.y + ((index + 1) * lineHeight), paint)
        }
        if (focused) {
            val underline = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.STROKE
                color = Color.parseColor("#6631E7B6")
                strokeWidth = 2.2f
            }
            val underlineY = obj.position.y + ((lines.size + 1) * lineHeight)
            canvas.drawLine(obj.position.x, underlineY, obj.position.x + obj.size.width.coerceAtLeast(180f), underlineY, underline)
        }
    }

    private fun drawCenteredText(canvas: Canvas, text: String, centerX: Float, centerY: Float, maxCharsPerLine: Int, paint: Paint) {
        val lines = wrapLabelForNode(text, maxCharsPerLine = maxCharsPerLine, maxLines = 6)
        val lineHeight = paint.textSize * 1.12f
        val totalHeight = lines.size * lineHeight
        val startY = centerY - (totalHeight / 2f) + paint.textSize
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, centerX, startY + (index * lineHeight), paint)
        }
    }

    private fun textPaint(
        obj: BoardObject,
        color: Int,
        align: Paint.Align,
        bold: Boolean,
        monospace: Boolean = false,
    ): Paint {
        return Paint().apply {
            isAntiAlias = true
            this.color = color
            textAlign = align
            textSize = obj.style.textSize.coerceIn(14f, 44f)
            typeface = when {
                monospace -> Typeface.MONOSPACE
                bold -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                else -> Typeface.DEFAULT
            }
        }
    }

    private fun parseColor(raw: String?, fallback: String): Int {
        return runCatching {
            Color.parseColor(raw?.takeIf { it.isNotBlank() } ?: fallback)
        }.getOrElse { Color.parseColor(fallback) }
    }

    private fun drawRank(type: String): Int {
        return when (type.lowercase()) {
            "line", "arrow" -> 0
            "box", "card", "circle" -> 1
            "title", "text", "code" -> 2
            else -> 3
        }
    }

    private fun themeBackground(theme: String): Int {
        return when (theme.lowercase()) {
            "blueprint" -> Color.parseColor("#23364A")
            "light" -> Color.parseColor("#F8FAFC")
            "neon_lab" -> Color.parseColor("#0F172A")
            else -> Color.parseColor("#16181D")
        }
    }
}
