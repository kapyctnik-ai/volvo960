package com.volvo960.obdctl.ui.dash

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * One dial from the real cluster photograph with a live needle on top.
 *
 * The needle is smoothed in real time rather than animated to a target: an
 * `alpha = 1 - exp(-dt/tau)` step is frame-rate independent, so it settles at
 * the same speed whether the view is drawing at 60 or 90 Hz, and a late poll
 * doesn't make it jump.
 */
class DialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    enum class Mode { SPEEDO, TACHO, CLOCK }

    private companion object {
        /** Needle time constant, seconds. Slow enough to look mechanical. */
        const val TAU = 0.16f
        const val SPEEDO_MAX = 240f
        const val TACHO_MAX = 7000f
        val NEEDLE_COLOR = Color.parseColor("#E5482F")
        val NEEDLE_EDGE = Color.parseColor("#7A1B10")
        val HAND_COLOR = Color.parseColor("#D8DEE3")
    }

    var mode: Mode = Mode.SPEEDO
        set(value) {
            field = value
            invalidate()
        }

    /** Text drawn under the hub — the numeric reading, or null for none. */
    var caption: String? = null
        set(value) {
            field = value
            invalidate()
        }

    private var target = 0f
    private var shown = 0f
    private var lastFrameMs = 0L
    private var haveValue = false

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8C949B") }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EAF2F6")
        textAlign = Paint.Align.CENTER
    }
    private val src = Rect()
    private val dst = RectF()
    private val path = Path()

    /** null blanks the needle: the car isn't reporting this reading. */
    fun setValue(value: Float?) {
        if (value == null) {
            haveValue = false
            target = 0f
        } else {
            haveValue = true
            target = value
        }
        invalidate()
    }

    private fun piece(): DialArt.Piece = when (mode) {
        Mode.SPEEDO -> DialArt.SPEEDO
        Mode.TACHO -> DialArt.TACHO
        Mode.CLOCK -> DialArt.CLOCK
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Always square: the artwork is a circle and letterboxing it wastes
        // the little width a phone has in portrait.
        val size = minOf(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec).takeIf { MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED }
                ?: MeasureSpec.getSize(widthMeasureSpec),
        )
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val piece = piece()
        val bitmap = DialArt.bitmap(context, piece) ?: return
        val side = minOf(width, height).toFloat()
        val scale = side / piece.size
        val left = (width - side) / 2f
        val top = (height - side) / 2f

        src.set(0, 0, bitmap.width, bitmap.height)
        dst.set(left, top, left + side, top + side)
        canvas.drawBitmap(bitmap, src, dst, bitmapPaint)

        val cx = left + piece.pivotX * scale
        val cy = top + piece.pivotY * scale

        if (mode == Mode.CLOCK) {
            drawClock(canvas, cx, cy, scale)
            // A clock that doesn't tick is a picture of a clock.
            postInvalidateDelayed(1_000L)
        } else {
            drawGauge(canvas, piece, cx, cy, scale)
        }

        caption?.let {
            captionPaint.textSize = side * 0.11f
            canvas.drawText(it, cx, cy + side * 0.30f, captionPaint)
        }
    }

    private fun drawGauge(canvas: Canvas, piece: DialArt.Piece, cx: Float, cy: Float, scale: Float) {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameMs == 0L) 0f else (now - lastFrameMs) / 1000f
        lastFrameMs = now
        if (dt > 0f) {
            val alpha = 1f - exp(-dt / TAU)
            shown += (target - shown) * alpha
        }
        if (kotlin.math.abs(target - shown) > 0.5f) postInvalidateOnAnimation()

        if (!haveValue && shown < 1f) return

        val max = if (mode == Mode.TACHO) TACHO_MAX else SPEEDO_MAX
        val fraction = (shown / max).coerceIn(0f, 1f)
        val angle = piece.startAngle + piece.sweepAngle * fraction
        val needle = if (mode == Mode.TACHO) DialArt.TACHO_NEEDLE else DialArt.SPEEDO_NEEDLE
        drawNeedle(canvas, cx, cy, scale, angle, needle, NEEDLE_COLOR, NEEDLE_EDGE)
        hubPaint.color = Color.parseColor("#5E666D")
        canvas.drawCircle(cx, cy, needle.baseRadius * scale * 0.45f, hubPaint)
    }

    private fun drawClock(canvas: Canvas, cx: Float, cy: Float, scale: Float) {
        val cal = Calendar.getInstance()
        val seconds = cal.get(Calendar.SECOND).toFloat()
        val minutes = cal.get(Calendar.MINUTE) + seconds / 60f
        val hours = (cal.get(Calendar.HOUR) % 12) + minutes / 60f
        // Twelve o'clock is straight up, which is -90° from the three o'clock
        // direction the needle helper measures from.
        drawNeedle(canvas, cx, cy, scale, hours / 12f * 360f - 90f, DialArt.HOUR_NEEDLE, HAND_COLOR, Color.parseColor("#41484D"))
        drawNeedle(canvas, cx, cy, scale, minutes / 60f * 360f - 90f, DialArt.MINUTE_NEEDLE, HAND_COLOR, Color.parseColor("#41484D"))
        drawNeedle(canvas, cx, cy, scale, seconds / 60f * 360f - 90f, DialArt.SECOND_NEEDLE, NEEDLE_COLOR, NEEDLE_EDGE)
        hubPaint.color = Color.parseColor("#5E666D")
        canvas.drawCircle(cx, cy, 10f * scale, hubPaint)
    }

    private fun drawNeedle(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        scale: Float,
        angleDeg: Float,
        needle: DialArt.Needle,
        fill: Int,
        edge: Int,
    ) {
        val rad = Math.toRadians(angleDeg.toDouble())
        val dirX = cos(rad).toFloat()
        val dirY = sin(rad).toFloat()
        // Perpendicular, for the taper's half-widths.
        val perpX = -dirY
        val perpY = dirX

        val baseR = needle.baseRadius * scale
        val tipR = needle.tipRadius * scale
        val baseW = needle.baseHalfWidth * scale
        val tipW = needle.tipHalfWidth * scale

        path.reset()
        path.moveTo(cx + dirX * baseR + perpX * baseW, cy + dirY * baseR + perpY * baseW)
        path.lineTo(cx + dirX * tipR + perpX * tipW, cy + dirY * tipR + perpY * tipW)
        path.lineTo(cx + dirX * tipR - perpX * tipW, cy + dirY * tipR - perpY * tipW)
        path.lineTo(cx + dirX * baseR - perpX * baseW, cy + dirY * baseR - perpY * baseW)
        path.close()

        needlePaint.style = Paint.Style.FILL
        needlePaint.color = fill
        canvas.drawPath(path, needlePaint)
        needlePaint.style = Paint.Style.STROKE
        needlePaint.strokeWidth = maxOf(1f, scale)
        needlePaint.color = edge
        canvas.drawPath(path, needlePaint)
    }
}
