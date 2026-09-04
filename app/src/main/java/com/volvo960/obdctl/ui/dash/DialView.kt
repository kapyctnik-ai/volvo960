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
import kotlin.math.sin

/**
 * One dial from the real cluster photograph with a live needle on top.
 *
 * The needle travels across the measured gap between readings, so it is still
 * moving when the next one arrives and never stalls half way. An exponential
 * approach — the obvious way to smooth this — starts with a jerk and then
 * crawls, and at two or three readings a second the crawl is what you see.
 */
class DialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    enum class Mode { SPEEDO, TACHO, CLOCK }

    private companion object {
        /**
         * How long the needle is given to travel between two readings when the
         * sample rate is not yet known, and the bounds it is clamped to. The
         * needle is driven across the measured gap between samples, so it is
         * always moving and never stalls waiting for the next one.
         */
        const val DEFAULT_SAMPLE_MS = 400f
        const val MIN_SAMPLE_MS = 120f
        const val MAX_SAMPLE_MS = 1_500f
        /** How much of the measured gap a new measurement is worth. */
        const val SAMPLE_SMOOTHING = 0.3f
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

    /** Caption height as a fraction of the dial; the gear number wants to be big. */
    var captionScale: Float = 0.11f
        set(value) {
            field = value
            invalidate()
        }

    private var shown = 0f
    private var haveValue = false

    /** The travel currently in progress: from [animFrom] to [animTo]. */
    private var animFrom = 0f
    private var animTo = 0f
    private var animStartMs = 0L
    private var lastSampleAtMs = 0L
    private var sampleIntervalMs = DEFAULT_SAMPLE_MS

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
        // The poller publishes its state several times per pass, so the same
        // reading arrives here several times over. Restarting the travel on
        // each one re-eased the needle from wherever it was — a visible
        // stutter — and polluted the sample-interval measurement.
        if (haveValue == (value != null) && animTo == (value ?: 0f)) return
        val now = SystemClock.elapsedRealtime()
        // Readings arrive as fast as the K-line allows, which varies with what
        // else is being asked for. Measuring the gap rather than assuming it
        // keeps the travel matched to the data: the needle arrives just as the
        // next reading does.
        if (lastSampleAtMs != 0L) {
            val gap = (now - lastSampleAtMs).toFloat()
            if (gap in MIN_SAMPLE_MS..MAX_SAMPLE_MS) {
                sampleIntervalMs += (gap - sampleIntervalMs) * SAMPLE_SMOOTHING
            }
        }
        lastSampleAtMs = now
        haveValue = value != null
        animFrom = shown
        animTo = value ?: 0f
        animStartMs = now
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
            captionPaint.textSize = side * captionScale
            // Baseline is fixed: a bigger caption grows upward into the space
            // between the hub and the dial's printing, not down over it.
            canvas.drawText(it, cx, cy + side * 0.30f, captionPaint)
        }
    }

    private fun drawGauge(canvas: Canvas, piece: DialArt.Piece, cx: Float, cy: Float, scale: Float) {
        val now = SystemClock.elapsedRealtime()
        val elapsed = (now - animStartMs).toFloat()
        val progress = (elapsed / sampleIntervalMs).coerceIn(0f, 1f)
        // Eased at both ends: a needle has mass, so it neither starts nor stops
        // instantly. Linear travel between samples looks mechanical in the wrong
        // way — like a plotter.
        val eased = progress * progress * (3f - 2f * progress)
        shown = animFrom + (animTo - animFrom) * eased
        if (progress < 1f) postInvalidateOnAnimation()

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
