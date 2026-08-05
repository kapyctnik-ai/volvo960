package com.volvo960.obdctl.ui.dash

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * The instrument cluster.
 *
 * Two rendering paths. When photographic artwork is installed in
 * `assets/cluster/` the dial face is drawn as an image and only the needles,
 * the odometer digits and the tell-tale are composited on top — that is the
 * only way this reads as the real panel rather than a redrawing of it. With
 * no artwork present it falls back to drawing the gauges itself, which keeps
 * the app usable but is an approximation and looks like one.
 *
 * Needles are smoothed towards their targets every frame. The car answers
 * roughly once a second; without smoothing that shows as visible stepping.
 */
class DashboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private companion object {
        const val HOUSING = 0xFF0B0D0E.toInt()
        const val FACE = 0xFF060809.toInt()
        const val BEZEL = 0xFF23292B.toInt()
        const val TICK_MINOR = 0xFF2F7F84.toInt()
        const val TICK_MAJOR = 0xFF7FF2F4.toInt()
        const val LABEL = 0xFF7FF2F4.toInt()
        const val NEEDLE = 0xFFFF5A1A.toInt()
        const val NEEDLE_HALO = 0x66FF6928
        const val NEEDLE_EDGE = 0xFFE04A12.toInt()
        const val NEEDLE_CREST = 0xFFFF7A3C.toInt()
        const val HUB = 0xFF191E20.toInt()
        const val REDLINE = 0xFFD41F1F.toInt()
        const val WINDOW_BG = 0xFF08090A.toInt()
        const val WINDOW_DIGIT = 0xFFE8F6F6.toInt()
        const val WINDOW_TRIP_DIGIT = 0xFFFFD9A0.toInt()
        const val LAMP_OFF = 0xFF161C1E.toInt()
        const val LAMP_ON = 0xFFFFA000.toInt()
        const val INACTIVE = 0xFF2C3D40.toInt()
        // Segment display: lit, lit-but-stale, and the faint unlit ghosts.
        const val SEGMENT_ON = 0xFF3FE9F0.toInt()
        const val SEGMENT_IDLE = 0xFF2A6E73.toInt()
        const val SEGMENT_OFF = 0x223FE9F0

        const val SPEED_MAX = 240f
        const val RPM_MAX = 7000f
        const val RPM_REDLINE = 6000f
        const val TEMP_MIN = 50f
        const val TEMP_MAX = 130f

        const val START_ANGLE = 150f
        const val SWEEP = 240f
        const val SMOOTHING_TAU = 0.16f
    }

    var onTripReset: (() -> Unit)? = null
    var onFanTap: (() -> Unit)? = null
    var onFanLongPress: (() -> Unit)? = null
    var onOpenControls: (() -> Unit)? = null

    private val assets: ClusterAssets? = ClusterAssets.load(context)

    private var targetSpeed: Float? = null
    private var targetRpm: Float? = null
    private var targetCoolant: Float? = null
    private var targetFuel: Float? = null

    private var shownSpeed = 0f
    private var shownRpm = 0f
    private var shownCoolant = TEMP_MIN
    private var shownFuel = 0f

    private var tripKm = 0.0
    private var totalKm = 0.0
    private var batteryVolts: Double? = null
    private var fanOn = false
    private var lastFrameNs = 0L

    // Hit areas, recomputed on every draw so they follow the layout.
    private val knobHit = RectF()
    private val lampHit = RectF()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    private val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val needlePath = Path()

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (knobHit.contains(e.x, e.y)) {
                onTripReset?.invoke()
                return true
            }
            if (lampHit.contains(e.x, e.y)) {
                onFanTap?.invoke()
                return true
            }
            return false
        }

        override fun onLongPress(e: MotionEvent) {
            if (lampHit.contains(e.x, e.y)) onFanLongPress?.invoke() else onOpenControls?.invoke()
        }
    })

    init {
        isClickable = true
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event) || super.onTouchEvent(event)

    fun submit(
        speedKmh: Int?,
        rpm: Int?,
        coolantTempC: Int?,
        atfTempC: Int?,
        fuelPercent: Int?,
        batteryVolts: Double?,
        tripKm: Double,
        totalKm: Double,
        fanOn: Boolean,
    ) {
        targetSpeed = speedKmh?.toFloat()
        targetRpm = rpm?.toFloat()
        targetCoolant = coolantTempC?.toFloat() ?: atfTempC?.toFloat()
        targetFuel = fuelPercent?.toFloat()
        this.batteryVolts = batteryVolts
        this.tripKm = tripKm
        this.totalKm = totalKm
        this.fanOn = fanOn
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(HOUSING)
        advanceSmoothing()

        val art = assets
        if (art != null) drawFromArtwork(canvas, art) else drawVector(canvas)

        // The sweeping second hand means there is always a next frame.
        postInvalidateOnAnimation()
    }

    // ------------------------------------------------------------------
    // Photographic path
    // ------------------------------------------------------------------

    private fun drawFromArtwork(canvas: Canvas, art: ClusterAssets) {
        val scale = min(width.toFloat() / art.face.width, height.toFloat() / art.face.height)
        val dx = (width - art.face.width * scale) / 2f
        val dy = (height - art.face.height * scale) / 2f

        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(scale, scale)
        canvas.drawBitmap(art.face, 0f, 0f, null)

        val g = art.geometry
        g.odometerRect?.let { drawCounterWindow(canvas, it, "%06d".format(totalKm.toInt()), WINDOW_DIGIT) }
        g.tripRect?.let { drawCounterWindow(canvas, it, "%05.1f".format(tripKm), WINDOW_TRIP_DIGIT) }

        // Temperature and voltage are figures, not dials — their arcs are
        // outside the crop — and they go where the turn-signal arrows are.
        g.coolantRect?.let {
            drawReadout(canvas, it, targetCoolant?.let { c -> "${c.toInt()}°C" } ?: "--°C", targetCoolant != null)
        }
        g.voltageRect?.let {
            drawReadout(canvas, it, batteryVolts?.let { v -> "%.1fV".format(v) } ?: "--.-V", batteryVolts != null)
        }

        g.speedo?.let { drawDialNeedle(canvas, it, fraction(shownSpeed, 0f, SPEED_MAX), targetSpeed != null) }
        g.tacho?.let { drawDialNeedle(canvas, it, fraction(shownRpm, 0f, RPM_MAX), targetRpm != null) }
        g.fuel?.let { drawDialNeedle(canvas, it, fraction(shownFuel, 0f, 100f), targetFuel != null) }
        g.temp?.let { drawDialNeedle(canvas, it, fraction(shownCoolant, TEMP_MIN, TEMP_MAX), targetCoolant != null) }
        g.clock?.let { drawArtClock(canvas, g, it) }

        canvas.restore()

        g.resetKnob?.let {
            knobHit.set(
                dx + it.left * scale,
                dy + it.top * scale,
                dx + it.right * scale,
                dy + it.bottom * scale,
            )
            // The photographed knob is tiny; give the touch target room.
            val padX = ((48f - knobHit.width()) / 2f).coerceAtLeast(0f)
            val padY = ((48f - knobHit.height()) / 2f).coerceAtLeast(0f)
            knobHit.inset(-padX, -padY)
        }
        // The tell-tale isn't in the photograph, so it goes in the housing band
        // below the dials, where the real warning-light row sits.
        val faceBottom = dy + art.face.height * scale
        val lampY = if (height - faceBottom > height * 0.08f) (faceBottom + height) / 2f else height * 0.94f
        drawFanLamp(canvas, width / 2f, lampY, height * 0.028f)
    }

    private fun drawDialNeedle(canvas: Canvas, dial: ClusterAssets.Dial, fraction: Float, active: Boolean) {
        val needle = dial.needle ?: return
        val degrees = dial.startAngle + dial.sweepAngle * fraction.coerceIn(0f, 1f)
        drawTaperedNeedle(canvas, dial.cx, dial.cy, degrees, needle, active)
    }

    private fun drawArtClock(canvas: Canvas, g: ClusterAssets.Geometry, dial: ClusterAssets.Dial) {
        val now = Calendar.getInstance()
        val seconds = now.get(Calendar.SECOND) + now.get(Calendar.MILLISECOND) / 1000f
        val minutes = now.get(Calendar.MINUTE) + seconds / 60f
        val hours = now.get(Calendar.HOUR) + minutes / 60f
        g.hourNeedle?.let { drawTaperedNeedle(canvas, dial.cx, dial.cy, hours * 30f - 90f, it, true) }
        g.minuteNeedle?.let { drawTaperedNeedle(canvas, dial.cx, dial.cy, minutes * 6f - 90f, it, true) }
        // Sweeps continuously rather than ticking.
        g.secondNeedle?.let { drawTaperedNeedle(canvas, dial.cx, dial.cy, seconds * 6f - 90f, it, true) }
    }

    /**
     * Draws a needle as the flat tapered shape it actually is: a quad running
     * outwards from [ClusterAssets.Needle.baseRadius] to the tip, shaded across
     * its width and haloed the way the lit original is.
     */
    private fun drawTaperedNeedle(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        degrees: Float,
        needle: ClusterAssets.Needle,
        active: Boolean,
    ) {
        val a = Math.toRadians(degrees.toDouble())
        val ux = cos(a).toFloat()
        val uy = sin(a).toFloat()
        // Unit vector across the needle, for the taper and the shading.
        val px = -uy
        val py = ux

        val baseX = cx + ux * needle.baseRadius
        val baseY = cy + uy * needle.baseRadius
        val tipX = cx + ux * needle.tipRadius
        val tipY = cy + uy * needle.tipRadius

        needlePath.reset()
        needlePath.moveTo(baseX + px * needle.baseHalfWidth, baseY + py * needle.baseHalfWidth)
        needlePath.lineTo(tipX + px * needle.tipHalfWidth, tipY + py * needle.tipHalfWidth)
        needlePath.lineTo(tipX - px * needle.tipHalfWidth, tipY - py * needle.tipHalfWidth)
        needlePath.lineTo(baseX - px * needle.baseHalfWidth, baseY - py * needle.baseHalfWidth)
        needlePath.close()

        if (active) {
            glow.color = NEEDLE_HALO
            glow.style = Paint.Style.STROKE
            glow.strokeWidth = needle.baseHalfWidth * 1.6f
            canvas.drawPath(needlePath, glow)
        }

        fill.shader = android.graphics.LinearGradient(
            baseX - px * needle.baseHalfWidth, baseY - py * needle.baseHalfWidth,
            baseX + px * needle.baseHalfWidth, baseY + py * needle.baseHalfWidth,
            intArrayOf(NEEDLE_EDGE, NEEDLE_CREST, NEEDLE_EDGE),
            floatArrayOf(0f, 0.45f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        fill.color = if (active) NEEDLE else INACTIVE
        canvas.drawPath(needlePath, fill)
        fill.shader = null
    }

    /**
     * A seven-segment readout, drawn segment by segment rather than as text so
     * the unlit segments can show faintly behind the lit ones — that ghosting
     * is what makes a segment display read as one.
     */
    private fun drawReadout(canvas: Canvas, rect: RectF, value: String, active: Boolean) {
        SevenSegment.draw(
            canvas = canvas,
            bounds = rect,
            textToDraw = value,
            onColor = if (active) SEGMENT_ON else SEGMENT_IDLE,
            offColor = SEGMENT_OFF,
            paint = fill,
        )
        fill.style = Paint.Style.FILL
    }

    /**
     * Redraws a counter window over the one printed in the photograph, so the
     * live figure replaces the frozen one without disturbing its surround.
     */
    private fun drawCounterWindow(canvas: Canvas, rect: RectF, value: String, digitColor: Int) {
        fill.color = WINDOW_BG
        canvas.drawRect(rect, fill)
        drawOdometerDigits(canvas, rect, value, digitColor, drawWindow = false)
    }

    // ------------------------------------------------------------------
    // Fallback path
    // ------------------------------------------------------------------

    private fun drawVector(canvas: Canvas) {
        val landscape = width > height * 1.2f
        if (landscape) {
            // Radius that keeps the speedometer clear of its neighbours.
            val r = min(width * 0.125f, height * 0.46f)
            val cy = height * 0.47f
            drawCutOffGauge(canvas, -r * 0.62f, cy + r * 0.30f, r * 0.62f, fraction(shownFuel, 0f, 100f), targetFuel != null, "E", "F", false)
            drawClock(canvas, width * 0.27f, cy, r * 0.80f)
            drawSpeedometer(canvas, width * 0.50f, cy, r)
            drawTachometer(canvas, width * 0.73f, cy, r * 0.80f)
            drawCutOffGauge(canvas, width + r * 0.62f, cy + r * 0.30f, r * 0.62f, fraction(shownCoolant, TEMP_MIN, TEMP_MAX), targetCoolant != null, "C", "H", true)
            drawFanLamp(canvas, width * 0.50f, height * 0.955f, height * 0.028f)
        } else {
            val r = min(width * 0.26f, height * 0.20f)
            drawSpeedometer(canvas, width * 0.5f, height * 0.28f, r)
            drawTachometer(canvas, width * 0.72f, height * 0.62f, r * 0.72f)
            drawClock(canvas, width * 0.28f, height * 0.62f, r * 0.72f)
            drawFanLamp(canvas, width * 0.5f, height * 0.88f, height * 0.018f)
        }
    }

    private fun drawFace(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        fill.color = FACE
        canvas.drawCircle(cx, cy, r, fill)
        stroke.color = BEZEL
        stroke.strokeWidth = r * 0.05f
        canvas.drawCircle(cx, cy, r * 0.975f, stroke)
    }

    private fun drawSpeedometer(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        drawFace(canvas, cx, cy, r)
        drawScale(canvas, cx, cy, r, max = SPEED_MAX, labelEvery = 20f, minorEvery = 10f, labelRadius = 0.68f)

        drawWindow(canvas, cx, cy - r * 0.42f, r * 0.62f, r * 0.15f, "%06d".format(totalKm.toInt()), WINDOW_DIGIT)
        drawWindow(canvas, cx, cy - r * 0.20f, r * 0.42f, r * 0.12f, "%05.1f".format(tripKm), WINDOW_TRIP_DIGIT)

        text.color = LABEL
        text.textSize = r * 0.11f
        canvas.drawText("km/h", cx, cy + r * 0.60f, text)

        // Trip reset knob, where the real cluster has its own.
        val knobR = r * 0.075f
        val knobX = cx - r * 0.52f
        val knobY = cy + r * 0.60f
        fill.color = BEZEL
        canvas.drawCircle(knobX, knobY, knobR, fill)
        fill.color = HUB
        canvas.drawCircle(knobX, knobY, knobR * 0.62f, fill)
        val hit = knobR.coerceAtLeast(28f)
        knobHit.set(knobX - hit, knobY - hit, knobX + hit, knobY + hit)

        drawNeedle(canvas, cx, cy, r, fraction(shownSpeed, 0f, SPEED_MAX), targetSpeed != null)
    }

    private fun drawTachometer(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        drawFace(canvas, cx, cy, r)
        drawScale(canvas, cx, cy, r, max = 7f, labelEvery = 1f, minorEvery = 0.5f, labelRadius = 0.66f)

        stroke.color = REDLINE
        stroke.strokeWidth = r * 0.05f
        val box = RectF(cx - r * 0.93f, cy - r * 0.93f, cx + r * 0.93f, cy + r * 0.93f)
        canvas.drawArc(box, START_ANGLE + SWEEP * (RPM_REDLINE / RPM_MAX), SWEEP * (1f - RPM_REDLINE / RPM_MAX), false, stroke)

        text.color = LABEL
        text.textSize = r * 0.11f
        canvas.drawText("×1000 r/min", cx, cy + r * 0.55f, text)

        drawNeedle(canvas, cx, cy, r, fraction(shownRpm, 0f, RPM_MAX), targetRpm != null)
    }

    private fun drawClock(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        drawFace(canvas, cx, cy, r)

        text.color = LABEL
        text.textSize = r * 0.24f
        for (hour in listOf(12, 3, 6, 9)) {
            val a = Math.toRadians((hour * 30 - 90).toDouble())
            canvas.drawText(
                hour.toString(),
                cx + cos(a).toFloat() * r * 0.72f,
                cy + sin(a).toFloat() * r * 0.72f + text.textSize * 0.35f,
                text,
            )
        }
        stroke.color = TICK_MINOR
        stroke.strokeWidth = r * 0.028f
        for (step in 0 until 12) {
            if (step % 3 == 0) continue
            val a = Math.toRadians((step * 30 - 90).toDouble())
            canvas.drawLine(
                cx + cos(a).toFloat() * r * 0.82f,
                cy + sin(a).toFloat() * r * 0.82f,
                cx + cos(a).toFloat() * r * 0.91f,
                cy + sin(a).toFloat() * r * 0.91f,
                stroke,
            )
        }

        val now = Calendar.getInstance()
        val seconds = now.get(Calendar.SECOND) + now.get(Calendar.MILLISECOND) / 1000f
        val minutes = now.get(Calendar.MINUTE) + seconds / 60f
        val hours = now.get(Calendar.HOUR) + minutes / 60f
        drawHand(canvas, cx, cy, r * 0.50f, hours * 30f - 90f, r * 0.055f, NEEDLE)
        drawHand(canvas, cx, cy, r * 0.74f, minutes * 6f - 90f, r * 0.038f, NEEDLE)
        // Sweeps continuously rather than ticking, matching a quartz sweep hand.
        drawHand(canvas, cx, cy, r * 0.82f, seconds * 6f - 90f, r * 0.015f, NEEDLE)

        fill.color = HUB
        canvas.drawCircle(cx, cy, r * 0.07f, fill)
    }

    /**
     * The fuel and temperature gauges sit at the extreme edges of the real
     * cluster with their centres beyond the housing, so only part of each arc
     * is ever visible. Passing a centre outside the view reproduces that.
     */
    private fun drawCutOffGauge(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        fraction: Float,
        active: Boolean,
        lowLabel: String,
        highLabel: String,
        hotEnd: Boolean,
    ) {
        drawFace(canvas, cx, cy, r)
        val box = RectF(cx - r * 0.72f, cy - r * 0.72f, cx + r * 0.72f, cy + r * 0.72f)

        stroke.color = TICK_MINOR
        stroke.strokeWidth = r * 0.08f
        canvas.drawArc(box, START_ANGLE, SWEEP, false, stroke)
        if (hotEnd) {
            stroke.color = REDLINE
            canvas.drawArc(box, START_ANGLE + SWEEP * 0.72f, SWEEP * 0.28f, false, stroke)
        }

        text.color = if (active) LABEL else INACTIVE
        text.textSize = r * 0.26f
        val a0 = Math.toRadians(START_ANGLE.toDouble())
        val a1 = Math.toRadians((START_ANGLE + SWEEP).toDouble())
        canvas.drawText(lowLabel, cx + cos(a0).toFloat() * r * 0.46f, cy + sin(a0).toFloat() * r * 0.46f, text)
        canvas.drawText(highLabel, cx + cos(a1).toFloat() * r * 0.46f, cy + sin(a1).toFloat() * r * 0.46f, text)

        drawNeedle(canvas, cx, cy, r * 0.86f, fraction, active)
    }

    /** Mechanical-drum style window, as used for the odometer and trip counter. */
    private fun drawWindow(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        halfWidth: Float,
        halfHeight: Float,
        value: String,
        digitColor: Int,
    ) {
        val rect = RectF(cx - halfWidth, cy - halfHeight, cx + halfWidth, cy + halfHeight)
        drawOdometerDigits(canvas, rect, value, digitColor, drawWindow = true)
    }

    private fun drawOdometerDigits(canvas: Canvas, rect: RectF, value: String, digitColor: Int, drawWindow: Boolean) {
        if (drawWindow) {
            fill.color = WINDOW_BG
            canvas.drawRoundRect(rect, rect.height() * 0.18f, rect.height() * 0.18f, fill)
            stroke.color = BEZEL
            stroke.strokeWidth = rect.height() * 0.09f
            canvas.drawRoundRect(rect, rect.height() * 0.18f, rect.height() * 0.18f, stroke)
        }
        val cells = value.length
        if (cells == 0) return
        val cellWidth = rect.width() / cells
        digits.color = digitColor
        digits.textSize = rect.height() * 1.15f
        for ((index, ch) in value.withIndex()) {
            val x = rect.left + cellWidth * (index + 0.5f)
            canvas.drawText(ch.toString(), x, rect.centerY() + digits.textSize * 0.36f, digits)
            if (drawWindow && index < cells - 1) {
                stroke.color = 0xFF14191B.toInt()
                stroke.strokeWidth = rect.height() * 0.05f
                val gx = rect.left + cellWidth * (index + 1)
                canvas.drawLine(gx, rect.top, gx, rect.bottom, stroke)
            }
        }
    }

    /** The single tell-tale kept from the cluster's warning-light row. */
    private fun drawFanLamp(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val on = fanOn
        fill.color = if (on) LAMP_ON else LAMP_OFF
        if (on) {
            fill.color = 0x33FFA000
            canvas.drawCircle(cx, cy, r * 1.7f, fill)
            fill.color = LAMP_ON
        }
        // Three blades around a hub read as a fan at this size.
        for (blade in 0 until 3) {
            val a = Math.toRadians((blade * 120).toDouble())
            val bx = cx + cos(a).toFloat() * r * 0.5f
            val by = cy + sin(a).toFloat() * r * 0.5f
            canvas.save()
            canvas.rotate((blade * 120).toFloat(), bx, by)
            canvas.drawOval(RectF(bx - r * 0.5f, by - r * 0.26f, bx + r * 0.5f, by + r * 0.26f), fill)
            canvas.restore()
        }
        fill.color = if (on) 0xFF2A1A00.toInt() else HOUSING
        canvas.drawCircle(cx, cy, r * 0.28f, fill)

        val hit = (r * 2.2f).coerceAtLeast(40f)
        lampHit.set(cx - hit, cy - hit, cx + hit, cy + hit)
    }

    private fun drawScale(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        max: Float,
        labelEvery: Float,
        minorEvery: Float,
        labelRadius: Float,
    ) {
        var value = 0f
        while (value <= max + 0.0001f) {
            val f = value / max
            val a = Math.toRadians((START_ANGLE + SWEEP * f).toDouble())
            val major = (value / labelEvery) % 1f < 0.0001f
            stroke.color = if (major) TICK_MAJOR else TICK_MINOR
            stroke.strokeWidth = if (major) r * 0.030f else r * 0.015f
            val inner = if (major) r * 0.82f else r * 0.86f
            canvas.drawLine(
                cx + cos(a).toFloat() * inner,
                cy + sin(a).toFloat() * inner,
                cx + cos(a).toFloat() * r * 0.94f,
                cy + sin(a).toFloat() * r * 0.94f,
                stroke,
            )
            if (major) {
                text.color = LABEL
                text.textSize = r * 0.115f
                canvas.drawText(
                    value.toInt().toString(),
                    cx + cos(a).toFloat() * r * labelRadius,
                    cy + sin(a).toFloat() * r * labelRadius + text.textSize * 0.35f,
                    text,
                )
            }
            value += minorEvery
        }
    }

    private fun drawHand(canvas: Canvas, cx: Float, cy: Float, length: Float, degrees: Float, thickness: Float, color: Int) {
        val a = Math.toRadians(degrees.toDouble())
        stroke.color = color
        stroke.strokeWidth = thickness
        canvas.drawLine(
            cx - cos(a).toFloat() * length * 0.12f,
            cy - sin(a).toFloat() * length * 0.12f,
            cx + cos(a).toFloat() * length,
            cy + sin(a).toFloat() * length,
            stroke,
        )
    }

    private fun drawNeedle(canvas: Canvas, cx: Float, cy: Float, r: Float, fraction: Float, active: Boolean) {
        val f = fraction.coerceIn(0f, 1f)
        val a = Math.toRadians((START_ANGLE + SWEEP * f).toDouble())
        val tipX = cx + cos(a).toFloat() * r * 0.84f
        val tipY = cy + sin(a).toFloat() * r * 0.84f
        val tailX = cx - cos(a).toFloat() * r * 0.15f
        val tailY = cy - sin(a).toFloat() * r * 0.15f

        if (active) {
            stroke.color = NEEDLE_HALO
            stroke.strokeWidth = r * 0.075f
            canvas.drawLine(tailX, tailY, tipX, tipY, stroke)
        }

        val perp = Math.toRadians((START_ANGLE + SWEEP * f + 90f).toDouble())
        val w = r * 0.028f
        needlePath.reset()
        needlePath.moveTo(tipX, tipY)
        needlePath.lineTo(tailX + cos(perp).toFloat() * w, tailY + sin(perp).toFloat() * w)
        needlePath.lineTo(tailX - cos(perp).toFloat() * w, tailY - sin(perp).toFloat() * w)
        needlePath.close()
        fill.color = if (active) NEEDLE else INACTIVE
        canvas.drawPath(needlePath, fill)

        fill.color = HUB
        canvas.drawCircle(cx, cy, r * 0.085f, fill)
    }

    private fun fraction(value: Float, min: Float, max: Float) = ((value - min) / (max - min)).coerceIn(0f, 1f)

    /** Frame-rate independent easing, so the motion looks the same at 60 and 120 Hz. */
    private fun advanceSmoothing() {
        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 1f / 60f else ((now - lastFrameNs) / 1_000_000_000f).coerceIn(0f, 0.25f)
        lastFrameNs = now
        val alpha = 1f - exp(-dt / SMOOTHING_TAU)

        shownSpeed += ((targetSpeed ?: 0f) - shownSpeed) * alpha
        shownRpm += ((targetRpm ?: 0f) - shownRpm) * alpha
        shownCoolant += ((targetCoolant ?: TEMP_MIN) - shownCoolant) * alpha
        shownFuel += ((targetFuel ?: 0f) - shownFuel) * alpha
        if (abs(shownSpeed) < 0.01f) shownSpeed = 0f
    }
}
