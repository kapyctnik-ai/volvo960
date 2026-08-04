package com.volvo960.obdctl.ui.dash

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Digital stand-in for the car's own instrument cluster: fuel, clock,
 * speedometer, tachometer and coolant temperature, left to right, in the
 * cluster's night colours — cyan markings, amber needles, near-black face.
 *
 * Needles are smoothed towards their targets on every frame rather than
 * jumping to each new reading. The car's diagnostic bus is slow and answers
 * roughly once a second, which would otherwise show as visible stepping.
 */
class DashboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private companion object {
        const val BACKGROUND = 0xFF06090A.toInt()
        const val FACE = 0xFF0D1416.toInt()
        const val RIM = 0xFF1B2A2D.toInt()
        const val TICK_MINOR = 0xFF2A7B80.toInt()
        const val TICK_MAJOR = 0xFF48E4E8.toInt()
        const val LABEL = 0xFF7FF2F4.toInt()
        const val NEEDLE = 0xFFFF6A1A.toInt()
        const val NEEDLE_GLOW = 0x55FF6A1A
        const val HUB = 0xFF202A2C.toInt()
        const val REDLINE = 0xFFE02323.toInt()
        const val DIGITS = 0xFF9BF7F9.toInt()
        const val INACTIVE = 0xFF33484B.toInt()

        /** Sweep of the round gauges, in screen degrees (0 = 3 o'clock). */
        const val GAUGE_START_ANGLE = 150f
        const val GAUGE_SWEEP = 240f

        /** Time constant of the needle smoothing, seconds. */
        const val SMOOTHING_TAU = 0.16f
    }

    // Targets fed from outside; nulls mean "no source for this yet".
    private var targetSpeed: Float? = null
    private var targetRpm: Float? = null
    private var targetCoolant: Float? = null
    private var targetFuel: Float? = null

    // Smoothed values actually drawn.
    private var shownSpeed = 0f
    private var shownRpm = 0f
    private var shownCoolant = 50f
    private var shownFuel = 0f

    private var tripKm = 0.0
    private var atfTempC: Int? = null
    private var lastFrameNs = 0L

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    private val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
        color = DIGITS
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = NEEDLE_GLOW
    }
    private val needlePath = Path()

    fun submit(
        speedKmh: Int?,
        rpm: Int?,
        coolantTempC: Int?,
        atfTempC: Int?,
        fuelPercent: Int?,
        tripKm: Double,
    ) {
        targetSpeed = speedKmh?.toFloat()
        targetRpm = rpm?.toFloat()
        targetCoolant = coolantTempC?.toFloat()
        targetFuel = fuelPercent?.toFloat()
        this.atfTempC = atfTempC
        this.tripKm = tripKm
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(BACKGROUND)
        advanceSmoothing()

        val landscape = width > height * 1.25f
        if (landscape) drawRow(canvas) else drawStacked(canvas)

        // Keep animating until every needle has settled on its target.
        if (needsAnotherFrame()) postInvalidateOnAnimation() else lastFrameNs = 0L
    }

    /** Five gauges side by side, in the cluster's own order. */
    private fun drawRow(canvas: Canvas) {
        val pad = width * 0.015f
        val usable = width - pad * 2
        // Small, big, big, big, small — weights chosen to echo the real cluster.
        val weights = floatArrayOf(0.55f, 0.95f, 1.25f, 0.95f, 0.55f)
        val total = weights.sum()
        val unit = usable / total
        var x = pad
        val cy = height / 2f
        val maxRadius = min(height * 0.44f, unit * 1.25f / 2f)

        for ((index, weight) in weights.withIndex()) {
            val slot = unit * weight
            val cx = x + slot / 2f
            val r = min(slot / 2f * 0.94f, maxRadius)
            drawGaugeAt(canvas, index, cx, cy, r)
            x += slot
        }
    }

    /** Portrait fallback: the two big dials on top, the rest underneath. */
    private fun drawStacked(canvas: Canvas) {
        val topR = min(width * 0.24f, height * 0.19f)
        val topY = height * 0.27f
        drawGaugeAt(canvas, 2, width * 0.29f, topY, topR)
        drawGaugeAt(canvas, 3, width * 0.71f, topY, topR)

        val bottomR = min(width * 0.15f, height * 0.12f)
        val bottomY = height * 0.68f
        drawGaugeAt(canvas, 0, width * 0.19f, bottomY, bottomR)
        drawGaugeAt(canvas, 1, width * 0.5f, bottomY, bottomR)
        drawGaugeAt(canvas, 4, width * 0.81f, bottomY, bottomR)
    }

    private fun drawGaugeAt(canvas: Canvas, index: Int, cx: Float, cy: Float, r: Float) {
        when (index) {
            0 -> drawFuel(canvas, cx, cy, r)
            1 -> drawClock(canvas, cx, cy, r)
            2 -> drawSpeedometer(canvas, cx, cy, r)
            3 -> drawTachometer(canvas, cx, cy, r)
            4 -> drawCoolant(canvas, cx, cy, r)
        }
    }

    private fun drawFace(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        facePaint.color = FACE
        canvas.drawCircle(cx, cy, r, facePaint)
        rimPaint.color = RIM
        rimPaint.strokeWidth = r * 0.035f
        canvas.drawCircle(cx, cy, r * 0.98f, rimPaint)
    }

    private fun drawSpeedometer(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        drawFace(canvas, cx, cy, r)
        drawScale(canvas, cx, cy, r, max = 240f, majorStep = 20f, minorStep = 10f, labelStep = 20f)

        digitPaint.textSize = r * 0.17f
        canvas.drawText("TRIP  %.1f".format(tripKm), cx, cy + r * 0.42f, digitPaint)

        textPaint.color = LABEL
        textPaint.textSize = r * 0.13f
        canvas.drawText("km/h", cx, cy + r * 0.66f, textPaint)

        drawNeedle(canvas, cx, cy, r, fraction = shownSpeed / 240f, active = targetSpeed != null)
    }

    private fun drawTachometer(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        drawFace(canvas, cx, cy, r)
        drawScale(canvas, cx, cy, r, max = 7f, majorStep = 1f, minorStep = 0.5f, labelStep = 1f)

        // Red zone from 6000 rpm, drawn along the scale like the real dial.
        arcPaint.color = REDLINE
        arcPaint.strokeWidth = r * 0.055f
        val redStart = GAUGE_START_ANGLE + GAUGE_SWEEP * (6f / 7f)
        val box = RectF(cx - r * 0.83f, cy - r * 0.83f, cx + r * 0.83f, cy + r * 0.83f)
        canvas.drawArc(box, redStart, GAUGE_SWEEP * (1f / 7f), false, arcPaint)

        textPaint.color = LABEL
        textPaint.textSize = r * 0.13f
        canvas.drawText("×1000 r/min", cx, cy + r * 0.62f, textPaint)

        drawNeedle(canvas, cx, cy, r, fraction = shownRpm / 7000f, active = targetRpm != null)
    }

    private fun drawClock(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        drawFace(canvas, cx, cy, r)

        textPaint.color = LABEL
        textPaint.textSize = r * 0.26f
        for (hour in listOf(12, 3, 6, 9)) {
            val angle = Math.toRadians((hour * 30 - 90).toDouble())
            val tx = cx + cos(angle).toFloat() * r * 0.72f
            val ty = cy + sin(angle).toFloat() * r * 0.72f + textPaint.textSize * 0.35f
            canvas.drawText(hour.toString(), tx, ty, textPaint)
        }
        tickPaint.color = TICK_MINOR
        tickPaint.strokeWidth = r * 0.03f
        for (step in 0 until 12) {
            if (step % 3 == 0) continue
            val angle = Math.toRadians((step * 30 - 90).toDouble())
            canvas.drawLine(
                cx + cos(angle).toFloat() * r * 0.82f,
                cy + sin(angle).toFloat() * r * 0.82f,
                cx + cos(angle).toFloat() * r * 0.9f,
                cy + sin(angle).toFloat() * r * 0.9f,
                tickPaint,
            )
        }

        val now = Calendar.getInstance()
        val minute = now.get(Calendar.MINUTE) + now.get(Calendar.SECOND) / 60f
        val hour = now.get(Calendar.HOUR) + minute / 60f
        drawHand(canvas, cx, cy, r * 0.5f, (hour * 30f) - 90f, r * 0.05f)
        drawHand(canvas, cx, cy, r * 0.74f, (minute * 6f) - 90f, r * 0.035f)

        facePaint.color = HUB
        canvas.drawCircle(cx, cy, r * 0.07f, facePaint)
    }

    private fun drawHand(canvas: Canvas, cx: Float, cy: Float, length: Float, degrees: Float, thickness: Float) {
        val angle = Math.toRadians(degrees.toDouble())
        needlePaint.color = NEEDLE
        needlePaint.strokeWidth = thickness
        needlePaint.style = Paint.Style.STROKE
        needlePaint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(
            cx - cos(angle).toFloat() * length * 0.12f,
            cy - sin(angle).toFloat() * length * 0.12f,
            cx + cos(angle).toFloat() * length,
            cy + sin(angle).toFloat() * length,
            needlePaint,
        )
        needlePaint.style = Paint.Style.FILL
    }

    private fun drawFuel(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        drawSmallArcGauge(
            canvas, cx, cy, r,
            fraction = shownFuel / 100f,
            active = targetFuel != null,
            leftLabel = "E",
            rightLabel = "F",
            caption = "fuel",
        )
    }

    private fun drawCoolant(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // 50..130 °C across the arc, matching the cluster's temperature band.
        val fraction = ((shownCoolant - 50f) / 80f).coerceIn(0f, 1f)
        drawSmallArcGauge(
            canvas, cx, cy, r,
            fraction = fraction,
            active = targetCoolant != null,
            leftLabel = "C",
            rightLabel = "H",
            caption = targetCoolant?.let { "${shownCoolant.toInt()}°" } ?: "temp",
            hotFrom = 0.5f,
        )
        atfTempC?.let {
            textPaint.color = LABEL
            textPaint.textSize = r * 0.2f
            canvas.drawText("ATF $it°", cx, cy + r * 0.95f, textPaint)
        }
    }

    private fun drawSmallArcGauge(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        fraction: Float,
        active: Boolean,
        leftLabel: String,
        rightLabel: String,
        caption: String,
        hotFrom: Float? = null,
    ) {
        drawFace(canvas, cx, cy, r)
        val box = RectF(cx - r * 0.7f, cy - r * 0.7f, cx + r * 0.7f, cy + r * 0.7f)

        arcPaint.color = TICK_MINOR
        arcPaint.strokeWidth = r * 0.07f
        canvas.drawArc(box, GAUGE_START_ANGLE, GAUGE_SWEEP, false, arcPaint)

        if (hotFrom != null) {
            arcPaint.color = REDLINE
            canvas.drawArc(
                box,
                GAUGE_START_ANGLE + GAUGE_SWEEP * hotFrom,
                GAUGE_SWEEP * (1f - hotFrom),
                false,
                arcPaint,
            )
        }

        textPaint.color = if (active) LABEL else INACTIVE
        textPaint.textSize = r * 0.28f
        val startAngle = Math.toRadians(GAUGE_START_ANGLE.toDouble())
        val endAngle = Math.toRadians((GAUGE_START_ANGLE + GAUGE_SWEEP).toDouble())
        canvas.drawText(
            leftLabel,
            cx + cos(startAngle).toFloat() * r * 0.44f,
            cy + sin(startAngle).toFloat() * r * 0.44f,
            textPaint,
        )
        canvas.drawText(
            rightLabel,
            cx + cos(endAngle).toFloat() * r * 0.44f,
            cy + sin(endAngle).toFloat() * r * 0.44f,
            textPaint,
        )

        textPaint.textSize = r * 0.24f
        canvas.drawText(caption, cx, cy + r * 0.5f, textPaint)

        drawNeedle(canvas, cx, cy, r * 0.8f, fraction, active)
    }

    private fun drawScale(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        max: Float,
        majorStep: Float,
        minorStep: Float,
        labelStep: Float,
    ) {
        var value = 0f
        while (value <= max + 0.0001f) {
            val fraction = value / max
            val angle = Math.toRadians((GAUGE_START_ANGLE + GAUGE_SWEEP * fraction).toDouble())
            val isMajor = (value / majorStep) % 1f < 0.0001f
            tickPaint.color = if (isMajor) TICK_MAJOR else TICK_MINOR
            tickPaint.strokeWidth = if (isMajor) r * 0.035f else r * 0.018f
            val inner = if (isMajor) r * 0.74f else r * 0.79f
            canvas.drawLine(
                cx + cos(angle).toFloat() * inner,
                cy + sin(angle).toFloat() * inner,
                cx + cos(angle).toFloat() * r * 0.88f,
                cy + sin(angle).toFloat() * r * 0.88f,
                tickPaint,
            )
            if (isMajor && (value / labelStep) % 1f < 0.0001f) {
                textPaint.color = LABEL
                textPaint.textSize = r * 0.15f
                val label = if (max <= 10f) value.toInt().toString() else value.toInt().toString()
                canvas.drawText(
                    label,
                    cx + cos(angle).toFloat() * r * 0.6f,
                    cy + sin(angle).toFloat() * r * 0.6f + textPaint.textSize * 0.35f,
                    textPaint,
                )
            }
            value += minorStep
        }
    }

    private fun drawNeedle(canvas: Canvas, cx: Float, cy: Float, r: Float, fraction: Float, active: Boolean) {
        val clamped = fraction.coerceIn(0f, 1f)
        val angle = Math.toRadians((GAUGE_START_ANGLE + GAUGE_SWEEP * clamped).toDouble())
        val tipX = cx + cos(angle).toFloat() * r * 0.8f
        val tipY = cy + sin(angle).toFloat() * r * 0.8f
        val tailX = cx - cos(angle).toFloat() * r * 0.16f
        val tailY = cy - sin(angle).toFloat() * r * 0.16f

        if (active) {
            glowPaint.strokeWidth = r * 0.09f
            canvas.drawLine(tailX, tailY, tipX, tipY, glowPaint)
        }

        val half = Math.toRadians((GAUGE_START_ANGLE + GAUGE_SWEEP * clamped + 90f).toDouble())
        val w = r * 0.035f
        needlePath.reset()
        needlePath.moveTo(tipX, tipY)
        needlePath.lineTo(tailX + cos(half).toFloat() * w, tailY + sin(half).toFloat() * w)
        needlePath.lineTo(tailX - cos(half).toFloat() * w, tailY - sin(half).toFloat() * w)
        needlePath.close()
        needlePaint.color = if (active) NEEDLE else INACTIVE
        canvas.drawPath(needlePath, needlePaint)

        facePaint.color = HUB
        canvas.drawCircle(cx, cy, r * 0.09f, facePaint)
    }

    /**
     * Moves each needle a frame-rate independent fraction of the way to its
     * target, so the same easing looks identical at 60 and 120 Hz.
     */
    private fun advanceSmoothing() {
        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 1f / 60f else ((now - lastFrameNs) / 1_000_000_000f).coerceIn(0f, 0.25f)
        lastFrameNs = now
        val alpha = 1f - exp(-dt / SMOOTHING_TAU)

        shownSpeed += ((targetSpeed ?: 0f) - shownSpeed) * alpha
        shownRpm += ((targetRpm ?: 0f) - shownRpm) * alpha
        shownCoolant += ((targetCoolant ?: 50f) - shownCoolant) * alpha
        shownFuel += ((targetFuel ?: 0f) - shownFuel) * alpha
    }

    private fun needsAnotherFrame(): Boolean {
        // The clock's second-driven minute hand means this view is never truly
        // idle, but redrawing at display rate only matters while values move.
        val settled = kotlin.math.abs(shownSpeed - (targetSpeed ?: 0f)) < 0.05f &&
            kotlin.math.abs(shownRpm - (targetRpm ?: 0f)) < 0.5f &&
            kotlin.math.abs(shownCoolant - (targetCoolant ?: 50f)) < 0.05f &&
            kotlin.math.abs(shownFuel - (targetFuel ?: 0f)) < 0.05f
        return !settled
    }
}
