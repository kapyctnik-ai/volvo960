package com.volvo960.obdctl.ui.dash

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/**
 * One reading, as a card: small label at the top, the number filling the middle,
 * its unit beside it. Drawn rather than assembled out of TextViews — a grid of
 * these updates several times a second, and one view per tile is cheaper to
 * measure and lay out than four.
 *
 * A missing reading shows an em dash. The car declining to answer is
 * information; a zero pretending to be an answer is not.
 */
class TileView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private companion object {
        val BACKGROUND = Color.parseColor("#15181B")
        val BORDER = Color.parseColor("#23292E")
        val LABEL = Color.parseColor("#7E888F")
        val VALUE = Color.parseColor("#EEF4F7")
        val UNIT = Color.parseColor("#9AA4AB")
        const val EMPTY = "—"
    }

    var label: String = ""
        set(value) { field = value; invalidate() }

    var value: String? = null
        set(v) { field = v; invalidate() }

    var unit: String = ""
        set(value) { field = value; invalidate() }

    /** Overrides the value colour — used to flag a hot engine. */
    var valueColor: Int? = null
        set(v) { field = v; invalidate() }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BACKGROUND }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BORDER
        style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LABEL
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = VALUE
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UNIT
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    override fun onDraw(canvas: Canvas) {
        val inset = dp(0.5f)
        rect.set(inset, inset, width - inset, height - inset)
        val radius = dp(10f)
        canvas.drawRoundRect(rect, radius, radius, bgPaint)
        borderPaint.strokeWidth = dp(1f)
        canvas.drawRoundRect(rect, radius, radius, borderPaint)

        labelPaint.textSize = dp(11f)
        canvas.drawText(label.uppercase(), dp(10f), dp(10f) + labelPaint.textSize, labelPaint)

        val text = value ?: EMPTY
        // Shrinks to fit rather than clipping: "1234.5" has to sit in the same
        // box as "0".
        var size = height * 0.42f
        valuePaint.textSize = size
        val maxWidth = width - dp(20f)
        while (valuePaint.measureText(text) > maxWidth && size > dp(12f)) {
            size -= dp(1f)
            valuePaint.textSize = size
        }
        valuePaint.color = valueColor ?: VALUE
        val baseline = height * 0.72f
        canvas.drawText(text, width / 2f, baseline, valuePaint)

        if (unit.isNotEmpty()) {
            // The unit carries the method the number came from, so it can get
            // long; shrink rather than clip.
            var unitSize = dp(11f)
            unitPaint.textSize = unitSize
            while (unitPaint.measureText(unit) > maxWidth && unitSize > dp(7f)) {
                unitSize -= dp(0.5f)
                unitPaint.textSize = unitSize
            }
            canvas.drawText(unit, width / 2f, height - dp(8f), unitPaint)
        }
    }
}
