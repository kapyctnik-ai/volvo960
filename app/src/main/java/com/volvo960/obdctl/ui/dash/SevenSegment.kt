package com.volvo960.obdctl.ui.dash

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Draws digits as seven-segment shapes rather than as text.
 *
 * A digital-clock typeface would do the same job, but drawing the segments
 * costs nothing to ship, scales exactly, and lets the unlit segments show
 * faintly behind the lit ones — which is what makes a real segment display
 * look like one rather than like glowing text.
 */
object SevenSegment {

    /** Which segments (a b c d e f g) light up for each supported character. */
    private val GLYPHS: Map<Char, BooleanArray> = mapOf(
        '0' to booleanArrayOf(true, true, true, true, true, true, false),
        '1' to booleanArrayOf(false, true, true, false, false, false, false),
        '2' to booleanArrayOf(true, true, false, true, true, false, true),
        '3' to booleanArrayOf(true, true, true, true, false, false, true),
        '4' to booleanArrayOf(false, true, true, false, false, true, true),
        '5' to booleanArrayOf(true, false, true, true, false, true, true),
        '6' to booleanArrayOf(true, false, true, true, true, true, true),
        '7' to booleanArrayOf(true, true, true, false, false, false, false),
        '8' to booleanArrayOf(true, true, true, true, true, true, true),
        '9' to booleanArrayOf(true, true, true, true, false, true, true),
        '-' to booleanArrayOf(false, false, false, false, false, false, true),
        'C' to booleanArrayOf(true, false, false, true, true, true, false),
        'V' to booleanArrayOf(false, true, true, true, true, true, false),
        ' ' to booleanArrayOf(false, false, false, false, false, false, false),
    )

    private val path = Path()

    /**
     * Renders [textToDraw] centred in [bounds]. A `.` attaches to the digit
     * before it instead of taking a cell of its own, and `°` becomes a small
     * ring, so "12.6V" and "87°C" lay out the way they read.
     */
    fun draw(
        canvas: Canvas,
        bounds: RectF,
        textToDraw: String,
        onColor: Int,
        offColor: Int,
        paint: Paint,
    ) {
        val cells = textToDraw.count { it != '.' && it != '°' }
        if (cells == 0) return

        val gap = bounds.width() / (cells * 9f)
        val cellWidth = (bounds.width() - gap * (cells - 1)) / cells
        val digitHeight = bounds.height()
        val thickness = minOf(cellWidth, digitHeight) * 0.17f

        var x = bounds.left
        var index = 0
        for ((position, ch) in textToDraw.withIndex()) {
            when (ch) {
                '.' -> {
                    // Rides in the gap after the digit already drawn.
                    paint.color = onColor
                    canvas.drawCircle(x - gap / 2f, bounds.bottom - thickness / 2f, thickness * 0.55f, paint)
                }
                '°' -> {
                    paint.color = onColor
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = thickness * 0.6f
                    canvas.drawCircle(x + cellWidth * 0.22f, bounds.top + thickness * 1.4f, thickness * 0.9f, paint)
                    paint.style = Paint.Style.FILL
                }
                else -> {
                    val glyph = GLYPHS[ch] ?: GLYPHS[' ']!!
                    drawCell(canvas, x, bounds.top, cellWidth, digitHeight, thickness, glyph, onColor, offColor, paint)
                    x += cellWidth
                    index++
                    if (position != textToDraw.lastIndex) x += gap
                }
            }
        }
    }

    private fun drawCell(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        thickness: Float,
        glyph: BooleanArray,
        onColor: Int,
        offColor: Int,
        paint: Paint,
    ) {
        val right = left + width
        val bottom = top + height
        val middle = top + height / 2f
        val inset = thickness * 0.75f

        // a b c d e f g, clockwise from the top bar then the middle one.
        segment(canvas, glyph[0], onColor, offColor, paint, horizontal = true, x1 = left + inset, x2 = right - inset, y = top + thickness / 2f, thickness = thickness)
        segment(canvas, glyph[1], onColor, offColor, paint, horizontal = false, x1 = right - thickness / 2f, x2 = 0f, y = 0f, thickness = thickness, yStart = top + inset, yEnd = middle - inset)
        segment(canvas, glyph[2], onColor, offColor, paint, horizontal = false, x1 = right - thickness / 2f, x2 = 0f, y = 0f, thickness = thickness, yStart = middle + inset, yEnd = bottom - inset)
        segment(canvas, glyph[3], onColor, offColor, paint, horizontal = true, x1 = left + inset, x2 = right - inset, y = bottom - thickness / 2f, thickness = thickness)
        segment(canvas, glyph[4], onColor, offColor, paint, horizontal = false, x1 = left + thickness / 2f, x2 = 0f, y = 0f, thickness = thickness, yStart = middle + inset, yEnd = bottom - inset)
        segment(canvas, glyph[5], onColor, offColor, paint, horizontal = false, x1 = left + thickness / 2f, x2 = 0f, y = 0f, thickness = thickness, yStart = top + inset, yEnd = middle - inset)
        segment(canvas, glyph[6], onColor, offColor, paint, horizontal = true, x1 = left + inset, x2 = right - inset, y = middle, thickness = thickness)
    }

    private fun segment(
        canvas: Canvas,
        lit: Boolean,
        onColor: Int,
        offColor: Int,
        paint: Paint,
        horizontal: Boolean,
        x1: Float,
        x2: Float,
        y: Float,
        thickness: Float,
        yStart: Float = 0f,
        yEnd: Float = 0f,
    ) {
        paint.color = if (lit) onColor else offColor
        paint.style = Paint.Style.FILL
        val half = thickness / 2f
        path.reset()
        if (horizontal) {
            // Pointed ends, so neighbouring segments meet the way real ones do.
            path.moveTo(x1 - half, y)
            path.lineTo(x1, y - half)
            path.lineTo(x2, y - half)
            path.lineTo(x2 + half, y)
            path.lineTo(x2, y + half)
            path.lineTo(x1, y + half)
        } else {
            path.moveTo(x1, yStart - half)
            path.lineTo(x1 + half, yStart)
            path.lineTo(x1 + half, yEnd)
            path.lineTo(x1, yEnd + half)
            path.lineTo(x1 - half, yEnd)
            path.lineTo(x1 - half, yStart)
        }
        path.close()
        canvas.drawPath(path, paint)
    }
}
