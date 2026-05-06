package com.akimy.genie.tools

import android.graphics.Path
import android.graphics.RectF
import kotlin.math.*

/**
 * Parses simplified SVG path data strings into Android [Path] objects.
 *
 * Supported commands:
 *   M x y       — moveTo
 *   L x y       — lineTo
 *   Q cx cy x y — quadratic bezier
 *   C c1x c1y c2x c2y x y — cubic bezier
 *   A rx ry rot large sweep x y — arc
 *   Z           — close path
 *
 * All coordinates are multiplied by [scale] and offset by [offsetX]/[offsetY].
 */
object PathParser {

    fun parse(
        data: String?,
        scale: Float = 1f,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    ): Path? {
        if (data.isNullOrBlank()) return null
        val path = Path()
        val tokens = tokenize(data)
        var i = 0
        var cx = 0f
        var cy = 0f

        fun s(v: Float) = v * scale  // scale helper
        fun sx(v: Float) = v * scale + offsetX
        fun sy(v: Float) = v * scale + offsetY
        fun nextF(): Float = tokens.getOrNull(i++)?.toFloatOrNull() ?: 0f

        while (i < tokens.size) {
            when (tokens[i++].uppercase()) {
                "M" -> {
                    cx = nextF(); cy = nextF()
                    path.moveTo(sx(cx), sy(cy))
                }
                "L" -> {
                    cx = nextF(); cy = nextF()
                    path.lineTo(sx(cx), sy(cy))
                }
                "Q" -> {
                    val qcx = nextF(); val qcy = nextF()
                    cx = nextF(); cy = nextF()
                    path.quadTo(sx(qcx), sy(qcy), sx(cx), sy(cy))
                }
                "C" -> {
                    val c1x = nextF(); val c1y = nextF()
                    val c2x = nextF(); val c2y = nextF()
                    cx = nextF(); cy = nextF()
                    path.cubicTo(sx(c1x), sy(c1y), sx(c2x), sy(c2y), sx(cx), sy(cy))
                }
                "A" -> {
                    val rx = nextF(); val ry = nextF()
                    @Suppress("UNUSED_VARIABLE") val rot = nextF()
                    val large = nextF().toInt()
                    val sweep = nextF().toInt()
                    val ex = nextF(); val ey = nextF()
                    addArcToPath(path, sx(cx), sy(cy), sx(ex), sy(ey), s(rx), s(ry), large, sweep)
                    cx = ex; cy = ey
                }
                "Z" -> path.close()
            }
        }
        return path
    }

    /**
     * Tokenize a path data string — splits on whitespace and commas,
     * but also handles command letters touching numbers (e.g. "M30 40L100 200").
     */
    private fun tokenize(data: String): List<String> {
        val result = mutableListOf<String>()
        val buf = StringBuilder()

        for (ch in data) {
            when {
                ch in "MmLlQqCcAaZz" -> {
                    if (buf.isNotEmpty()) { result.add(buf.toString()); buf.clear() }
                    result.add(ch.toString())
                }
                ch == ',' || ch == ' ' || ch == '\t' || ch == '\n' -> {
                    if (buf.isNotEmpty()) { result.add(buf.toString()); buf.clear() }
                }
                ch == '-' && buf.isNotEmpty() && buf.last() != 'e' && buf.last() != 'E' -> {
                    // Negative sign starts a new number (unless after exponent)
                    result.add(buf.toString()); buf.clear()
                    buf.append(ch)
                }
                else -> buf.append(ch)
            }
        }
        if (buf.isNotEmpty()) result.add(buf.toString())
        return result
    }

    /**
     * Convert SVG arc parameters to Android arcTo call.
     * Simplified — assumes no rotation, converts endpoint arc to center-point arc.
     */
    private fun addArcToPath(
        path: Path,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        rx: Float, ry: Float,
        largeArc: Int, sweepDir: Int,
    ) {
        // Fallback for degenerate arcs
        if (rx == 0f || ry == 0f) {
            path.lineTo(x2, y2)
            return
        }
        // Midpoint
        val dx = (x1 - x2) / 2f
        val dy = (y1 - y2) / 2f

        val d = (dx * dx) / (rx * rx) + (dy * dy) / (ry * ry)
        val aRx = if (d > 1f) rx * sqrt(d) else rx
        val aRy = if (d > 1f) ry * sqrt(d) else ry

        val sq = ((aRx * aRx * aRy * aRy - aRx * aRx * dy * dy - aRy * aRy * dx * dx) /
                (aRx * aRx * dy * dy + aRy * aRy * dx * dx)).coerceAtLeast(0f)
        val sign = if (largeArc == sweepDir) -1f else 1f
        val root = sign * sqrt(sq)

        val cxp = root * aRx * dy / aRy
        val cyp = -root * aRy * dx / aRx

        val ccx = cxp + (x1 + x2) / 2f
        val ccy = cyp + (y1 + y2) / 2f

        val startAngle = atan2((y1 - ccy) / aRy, (x1 - ccx) / aRx)
        val endAngle = atan2((y2 - ccy) / aRy, (x2 - ccx) / aRx)
        var sweepAngle = endAngle - startAngle

        if (sweepDir == 1 && sweepAngle < 0) sweepAngle += 2f * PI.toFloat()
        if (sweepDir == 0 && sweepAngle > 0) sweepAngle -= 2f * PI.toFloat()

        val rect = RectF(ccx - aRx, ccy - aRy, ccx + aRx, ccy + aRy)
        path.arcTo(rect, Math.toDegrees(startAngle.toDouble()).toFloat(), Math.toDegrees(sweepAngle.toDouble()).toFloat())
    }
}
