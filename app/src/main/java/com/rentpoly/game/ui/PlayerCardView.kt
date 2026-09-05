package com.rentpoly.game.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.sin

/**
 * One player's card: token, name, money. Money counts up or down rather than
 * jumping, and the player whose turn it is gets a breathing white rim.
 */
class PlayerCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    var color: Int = Color.GRAY
    var token: String = "●"
    var name: String = ""
    var properties: Int = 0
    var inJail: Boolean = false
    var bankrupt: Boolean = false
    var current: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }

    private var shownMoney = 0f
    private var targetMoney = 0
    private var animator: ValueAnimator? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6FFFFFF")
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    }
    private val emoji = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    fun setMoney(value: Int, animate: Boolean) {
        if (value == targetMoney && animate) return
        targetMoney = value
        animator?.cancel()
        if (!animate) {
            shownMoney = value.toFloat()
            invalidate()
            return
        }
        val from = shownMoney
        animator = ValueAnimator.ofFloat(from, value.toFloat()).apply {
            duration = 550
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                shownMoney = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = dp(12f)
        rect.set(dp(2f), dp(2f), w - dp(2f), h - dp(2f))

        val base = if (bankrupt) Color.parseColor("#555555") else color
        fill.shader = LinearGradient(0f, 0f, 0f, h, lighten(base, 0.18f), darken(base, 0.22f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, r, r, fill)
        fill.shader = null

        // Gloss across the top third.
        fill.color = Color.parseColor("#2AFFFFFF")
        val gloss = RectF(rect.left, rect.top, rect.right, rect.top + h * 0.38f)
        canvas.drawRoundRect(gloss, r, r, fill)

        if (current && !bankrupt) {
            val pulse = 0.55f + 0.45f * sin(SystemClock.elapsedRealtime() / 260.0).toFloat()
            rim.color = Color.argb((120 + 135 * pulse).toInt(), 255, 255, 255)
            rim.strokeWidth = dp(2.5f)
            canvas.drawRoundRect(rect, r, r, rim)
            postInvalidateOnAnimation()
        } else {
            rim.color = Color.parseColor("#55000000")
            rim.strokeWidth = dp(1f)
            canvas.drawRoundRect(rect, r, r, rim)
        }

        // Token disc.
        val tr = h * 0.26f
        val tx = dp(10f) + tr
        val ty = h / 2f
        fill.shader = RadialGradient(tx - tr * 0.35f, ty - tr * 0.35f, tr * 1.5f, Color.WHITE, Color.parseColor("#D0D0D0"), Shader.TileMode.CLAMP)
        canvas.drawCircle(tx, ty, tr, fill)
        fill.shader = null
        emoji.textSize = tr * 1.25f
        canvas.drawText(token, tx, ty + tr * 0.45f, emoji)

        val left = tx + tr + dp(8f)
        text.textSize = h * 0.24f
        canvas.drawText(name, left, h * 0.38f, text)
        if (bankrupt) {
            small.textSize = h * 0.2f
            canvas.drawText("банкрот", left, h * 0.72f, small)
        } else {
            text.textSize = h * 0.3f
            canvas.drawText("${shownMoney.toInt()}", left, h * 0.74f, text)
            small.textSize = h * 0.18f
            val extras = buildString {
                append("🏠 $properties")
                if (inJail) append("  🔒")
            }
            canvas.drawText(extras, left, h * 0.94f, small)
        }
        alpha = if (bankrupt) 0.55f else 1f
    }

    private fun lighten(c: Int, f: Float): Int = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * f).toInt(),
        (Color.green(c) + (255 - Color.green(c)) * f).toInt(),
        (Color.blue(c) + (255 - Color.blue(c)) * f).toInt(),
    )

    private fun darken(c: Int, f: Float): Int = Color.rgb(
        (Color.red(c) * (1 - f)).toInt(),
        (Color.green(c) * (1 - f)).toInt(),
        (Color.blue(c) * (1 - f)).toInt(),
    )
}
