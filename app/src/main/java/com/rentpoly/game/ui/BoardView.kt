package com.rentpoly.game.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import com.rentpoly.game.R
import com.rentpoly.game.model.Board
import com.rentpoly.game.model.CellType
import com.rentpoly.game.model.Game
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The board. Wood underneath, a paper playing surface with embossed squares,
 * felt in the middle, tokens that hop with a shadow that follows them, dice
 * that tumble and bounce, cards that flip up out of the board, money that
 * floats away from whoever paid it. All drawn; the only bitmaps are three
 * small tiled textures.
 */
class BoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    var game: Game? = null
        set(value) {
            field = value
            syncTokens()
            invalidate()
        }

    var onCellTapped: ((Int) -> Unit)? = null
    /** Fired once per square as a token walks. */
    var onHop: (() -> Unit)? = null

    var centreText: String = ""
        set(value) { if (field != value) { field = value; invalidate() } }

    private companion object {
        const val CORNER = 0.135f
        val CELL_BORDER = Color.parseColor("#3A2E22")
        val TEXT = Color.parseColor("#1F1A14")
        val HOUSE = Color.parseColor("#2E9E4A")
        val HOTEL = Color.parseColor("#C62828")
        const val STEP_MS = 210L
        const val FLOAT_MS = 1_500L
        const val GLOW_MS = 1_100L
    }

    private class Floater(val text: String, val x: Float, val y: Float, val color: Int, val start: Long)
    private class Glow(val cell: Int, val color: Int, val start: Long)
    private class CardShow(val title: String, val text: String, val color: Int, val start: Long, val onDone: () -> Unit) {
        var closing: Long = 0L
    }

    private val wood = BitmapFactory.decodeResource(resources, R.drawable.tex_wood)
    private val paper = BitmapFactory.decodeResource(resources, R.drawable.tex_paper)
    private val felt = BitmapFactory.decodeResource(resources, R.drawable.tex_felt)
    private val woodShader = BitmapShader(wood, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    private val paperShader = BitmapShader(paper, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    private val feltShader = BitmapShader(felt, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val emoji = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val path = Path()

    private val tokenPos = HashMap<Int, PointF>()
    private val tokenLift = HashMap<Int, Float>()
    private var walker: ValueAnimator? = null
    private var diceShown: Pair<Int, Int> = 0 to 0
    private var diceSpin = 0f
    private var dicePop = 0f
    private var diceAnimator: ValueAnimator? = null
    private val floaters = ArrayList<Floater>()
    private val glows = ArrayList<Glow>()
    private var card: CardShow? = null

    private fun side(): Float = min(width, height).toFloat()
    private fun originX(): Float = (width - side()) / 2f
    private fun originY(): Float = (height - side()) / 2f

    // ------------------------------------------------------------ geometry

    private fun cellRect(i: Int, out: RectF) {
        val c = CORNER
        val w = (1f - 2 * c) / 9f
        when {
            i == 0 -> out.set(1f - c, 1f - c, 1f, 1f)
            i in 1..9 -> out.set(1f - c - i * w, 1f - c, 1f - c - (i - 1) * w, 1f)
            i == 10 -> out.set(0f, 1f - c, c, 1f)
            i in 11..19 -> { val j = i - 10; out.set(0f, 1f - c - j * w, c, 1f - c - (j - 1) * w) }
            i == 20 -> out.set(0f, 0f, c, c)
            i in 21..29 -> { val j = i - 20; out.set(c + (j - 1) * w, 0f, c + j * w, c) }
            i == 30 -> out.set(1f - c, 0f, 1f, c)
            else -> { val j = i - 30; out.set(1f - c, c + (j - 1) * w, 1f, c + j * w) }
        }
    }

    private fun edgeOf(i: Int): Int = when {
        i in 0..10 -> 0
        i in 11..20 -> 1
        i in 21..30 -> 2
        else -> 3
    }

    private fun cellCentre(i: Int): PointF {
        val r = RectF()
        cellRect(i, r)
        return PointF(r.centerX(), r.centerY())
    }

    // ------------------------------------------------------------ public effects

    fun syncTokens() {
        val g = game ?: return
        for (p in g.players) if (!tokenPos.containsKey(p.id)) tokenPos[p.id] = cellCentre(p.position)
    }

    fun placeToken(player: Int, cell: Int) {
        tokenPos[player] = cellCentre(cell)
        tokenLift[player] = 0f
        invalidate()
    }

    fun animateWalk(player: Int, from: Int, steps: Int, onDone: () -> Unit) {
        walker?.cancel()
        if (steps == 0) {
            placeToken(player, from)
            onDone()
            return
        }
        val dir = if (steps > 0) 1 else -1
        val count = abs(steps)
        val points = ArrayList<PointF>(count + 1)
        var i = from
        points += cellCentre(i)
        repeat(count) {
            i = ((i + dir) % Board.SIZE + Board.SIZE) % Board.SIZE
            points += cellCentre(i)
        }
        var lastHop = -1
        val anim = ValueAnimator.ofFloat(0f, count.toFloat()).apply {
            duration = STEP_MS * count
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                val k = t.toInt().coerceIn(0, count - 1)
                val f = t - k
                val e = f * f * (3f - 2f * f)
                val a = points[k]
                val b = points[k + 1]
                val lift = sin(Math.PI * f).toFloat()
                tokenPos[player] = PointF(a.x + (b.x - a.x) * e, a.y + (b.y - a.y) * e)
                tokenLift[player] = lift
                if (k != lastHop && f > 0.5f) {
                    lastHop = k
                    onHop?.invoke()
                }
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    tokenPos[player] = points.last()
                    tokenLift[player] = 0f
                    invalidate()
                    onDone()
                }
            })
        }
        walker = anim
        anim.start()
    }

    fun animateDice(d1: Int, d2: Int, onDone: () -> Unit) {
        diceAnimator?.cancel()
        val rnd = java.util.Random()
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 800
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                diceShown = if (t < 0.8f) (rnd.nextInt(6) + 1) to (rnd.nextInt(6) + 1) else d1 to d2
                diceSpin = (1f - t) * 720f
                dicePop = sin(Math.PI * min(1f, t * 1.25f)).toFloat()
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    diceShown = d1 to d2
                    diceSpin = 0f
                    dicePop = 0f
                    invalidate()
                    onDone()
                }
            })
        }
        diceAnimator = anim
        anim.start()
    }

    /** Text that rises from a square and fades — "+200", "−50". */
    fun floatAt(cell: Int, message: String, color: Int) {
        val c = cellCentre(cell)
        floaters += Floater(message, c.x, c.y, color, SystemClock.elapsedRealtime())
        postInvalidateOnAnimation()
    }

    fun floatAtPlayer(player: Int, message: String, color: Int) {
        val p = tokenPos[player] ?: return
        floaters += Floater(message, p.x, p.y, color, SystemClock.elapsedRealtime())
        postInvalidateOnAnimation()
    }

    fun glow(cell: Int, color: Int) {
        glows += Glow(cell, color, SystemClock.elapsedRealtime())
        postInvalidateOnAnimation()
    }

    /** Flips a card up out of the board; it stays until tapped. */
    fun showCard(title: String, message: String, color: Int, onDone: () -> Unit) {
        card = CardShow(title, message, color, SystemClock.elapsedRealtime(), onDone)
        postInvalidateOnAnimation()
    }

    // ------------------------------------------------------------ measure/touch

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val size = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) w else min(w, h)
        setMeasuredDimension(size, size)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val shown = card
        if (shown != null) {
            if (shown.closing == 0L) shown.closing = SystemClock.elapsedRealtime()
            postInvalidateOnAnimation()
            return true
        }
        val s = side()
        val x = (event.x - originX()) / s
        val y = (event.y - originY()) / s
        val r = RectF()
        for (i in 0 until Board.SIZE) {
            cellRect(i, r)
            if (r.contains(x, y)) {
                onCellTapped?.invoke(i)
                return true
            }
        }
        return true
    }

    // ------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        val s = side()
        val ox = originX()
        val oy = originY()

        // Table.
        fill.shader = woodShader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        fill.shader = null

        canvas.save()
        canvas.translate(ox, oy)

        // Board shadow and frame.
        val inset = s * 0.012f
        for (k in 4 downTo 1) {
            fill.color = Color.argb(28, 0, 0, 0)
            rect.set(inset - k * s * 0.004f, inset + k * s * 0.006f, s - inset + k * s * 0.004f, s - inset + k * s * 0.008f)
            canvas.drawRoundRect(rect, s * 0.02f, s * 0.02f, fill)
        }
        rect.set(inset, inset, s - inset, s - inset)
        fill.shader = LinearGradient(0f, inset, 0f, s, Color.parseColor("#5A3B22"), Color.parseColor("#3A2415"), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, s * 0.02f, s * 0.02f, fill)
        fill.shader = null

        // Paper surface.
        val pad = s * 0.022f
        rect.set(pad, pad, s - pad, s - pad)
        fill.shader = paperShader
        canvas.drawRoundRect(rect, s * 0.01f, s * 0.01f, fill)
        fill.shader = null

        val g = game
        canvas.save()
        canvas.translate(pad, pad)
        val bs = s - 2 * pad
        for (i in 0 until Board.SIZE) drawCell(canvas, i, bs, g)
        drawCentre(canvas, bs, g)
        drawGlows(canvas, bs)
        if (g != null) drawTokens(canvas, bs, g)
        drawDice(canvas, bs)
        drawFloaters(canvas, bs)
        canvas.restore()

        drawCard(canvas, s)
        canvas.restore()

        if (floaters.isNotEmpty() || glows.isNotEmpty() || card != null) postInvalidateOnAnimation()
    }

    private fun cellScreenRect(i: Int, bs: Float, out: RectF) {
        cellRect(i, out)
        out.set(out.left * bs, out.top * bs, out.right * bs, out.bottom * bs)
    }

    private fun drawCell(canvas: Canvas, i: Int, bs: Float, g: Game?) {
        val cell = Board.cell(i)
        cellScreenRect(i, bs, rect)
        val edge = edgeOf(i)
        val corner = i % 10 == 0
        val band = bs * 0.03f
        val holding = g?.holdings?.get(i)

        // Emboss: dark line outside, light line inside.
        stroke.color = CELL_BORDER
        stroke.strokeWidth = bs * 0.0028f
        canvas.drawRect(rect, stroke)
        stroke.color = Color.parseColor("#66FFFFFF")
        stroke.strokeWidth = bs * 0.0018f
        val in1 = bs * 0.003f
        canvas.drawRect(rect.left + in1, rect.top + in1, rect.right - in1, rect.bottom - in1, stroke)

        if (corner) {
            fill.color = Color.parseColor("#14000000")
            canvas.drawRect(rect, fill)
        }

        if (cell.group != null && cell.type == CellType.PROPERTY) {
            val b = RectF(rect)
            when (edge) {
                0 -> b.bottom = b.top + band
                1 -> b.left = b.right - band
                2 -> b.top = b.bottom - band
                else -> b.right = b.left + band
            }
            val c = cell.group.color
            fill.shader = LinearGradient(b.left, b.top, b.left, b.bottom, lighten(c, 0.15f), darken(c, 0.25f), Shader.TileMode.CLAMP)
            canvas.drawRect(b, fill)
            fill.shader = null
            stroke.color = CELL_BORDER
            stroke.strokeWidth = bs * 0.0022f
            canvas.drawRect(b, stroke)
            if (holding != null && holding.houses > 0) drawHouses(canvas, b, edge, holding.houses, bs)
        }

        if (holding != null && holding.owner >= 0 && g != null) {
            val c = g.players[holding.owner].color
            val m = RectF(rect)
            val t = bs * 0.016f
            when (edge) {
                0 -> m.top = m.bottom - t
                1 -> m.right = m.left + t
                2 -> m.bottom = m.top + t
                else -> m.left = m.right - t
            }
            fill.color = c
            canvas.drawRect(m, fill)
        }

        canvas.save()
        canvas.rotate(when (edge) { 1 -> 90f; 3 -> -90f; else -> 0f }, rect.centerX(), rect.centerY())
        val w = if (edge == 1 || edge == 3) rect.height() else rect.width()
        val h = if (edge == 1 || edge == 3) rect.width() else rect.height()
        val cx = rect.centerX()
        val cy = rect.centerY()
        if (corner) {
            emoji.textSize = bs * 0.055f
            canvas.drawText(cell.icon, cx, cy - bs * 0.004f, emoji)
            text.textSize = bs * 0.021f
            canvas.drawText(cell.name, cx, cy + bs * 0.036f, text)
        } else {
            val bandInset = if (cell.type == CellType.PROPERTY) band else 0f
            val bandAtTop = edge != 2
            val localTop = cy - h / 2f + (if (bandAtTop) bandInset else 0f)
            val localBottom = cy + h / 2f - (if (bandAtTop) 0f else bandInset)
            text.textSize = bs * 0.0165f
            val lines = wrap(cell.name, w * 0.92f, text)
            var y = localTop + bs * 0.007f + text.textSize
            for (line in lines.take(2)) {
                canvas.drawText(line, cx, y, text)
                y += text.textSize * 1.05f
            }
            if (cell.icon.isNotEmpty()) {
                emoji.textSize = bs * 0.03f
                canvas.drawText(cell.icon, cx, (localTop + localBottom) / 2f + bs * 0.018f, emoji)
            }
            val figure = if (cell.price > 0) cell.price else cell.tax
            if (figure > 0) {
                text.textSize = bs * 0.016f
                canvas.drawText("$figure", cx, localBottom - bs * 0.007f, text)
            }
        }
        canvas.restore()

        if (holding != null && holding.mortgaged) {
            fill.color = Color.parseColor("#8C1B1B1B")
            canvas.drawRect(rect, fill)
            canvas.save()
            canvas.rotate(-20f, rect.centerX(), rect.centerY())
            text.textSize = bs * 0.02f
            val saved = text.color
            text.color = Color.parseColor("#FFE0B2")
            canvas.drawText("ЗАЛОГ", rect.centerX(), rect.centerY() + bs * 0.007f, text)
            text.color = saved
            canvas.restore()
        }
    }

    private fun drawHouses(canvas: Canvas, band: RectF, edge: Int, houses: Int, bs: Float) {
        val along = edge == 0 || edge == 2
        if (houses >= 5) {
            house(canvas, band.centerX(), band.centerY(), bs * 0.013f, HOTEL, wide = true)
            return
        }
        val len = if (along) band.width() else band.height()
        val gap = len / (houses + 1)
        for (k in 1..houses) {
            val cx = if (along) band.left + gap * k else band.centerX()
            val cy = if (along) band.centerY() else band.top + gap * k
            house(canvas, cx, cy, bs * 0.009f, HOUSE, wide = false)
        }
    }

    /** A little house: walls with a roof, and a shadow so it sits on the band. */
    private fun house(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int, wide: Boolean) {
        val w = if (wide) r * 1.7f else r
        fill.color = Color.parseColor("#66000000")
        canvas.drawRect(cx - w + r * 0.2f, cy - r * 0.2f + r * 0.2f, cx + w + r * 0.2f, cy + r + r * 0.2f, fill)
        fill.color = color
        canvas.drawRect(cx - w, cy - r * 0.2f, cx + w, cy + r, fill)
        path.reset()
        path.moveTo(cx - w - r * 0.2f, cy - r * 0.2f)
        path.lineTo(cx, cy - r * 1.1f)
        path.lineTo(cx + w + r * 0.2f, cy - r * 0.2f)
        path.close()
        fill.color = darken(color, 0.25f)
        canvas.drawPath(path, fill)
    }

    private fun wrap(name: String, maxWidth: Float, paint: Paint): List<String> {
        if (paint.measureText(name) <= maxWidth) return listOf(name)
        val words = name.split(' ')
        val lines = ArrayList<String>()
        var line = ""
        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) <= maxWidth || line.isEmpty()) line = candidate
            else { lines += line; line = word }
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }

    private fun drawCentre(canvas: Canvas, bs: Float, g: Game?) {
        val c = CORNER * bs
        val inner = RectF(c + bs * 0.008f, c + bs * 0.008f, bs - c - bs * 0.008f, bs - c - bs * 0.008f)
        fill.shader = feltShader
        canvas.drawRoundRect(inner, bs * 0.015f, bs * 0.015f, fill)
        // Vignette so the felt reads as a surface, not a flat fill.
        fill.shader = RadialGradient(inner.centerX(), inner.centerY(), inner.width() * 0.7f,
            Color.TRANSPARENT, Color.parseColor("#55000000"), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(inner, bs * 0.015f, bs * 0.015f, fill)
        fill.shader = null
        stroke.color = Color.parseColor("#5A3B22")
        stroke.strokeWidth = bs * 0.004f
        canvas.drawRoundRect(inner, bs * 0.015f, bs * 0.015f, stroke)

        canvas.save()
        canvas.rotate(-32f, inner.centerX(), inner.centerY())
        text.textSize = bs * 0.082f
        val saved = text.color
        text.color = Color.parseColor("#66000000")
        canvas.drawText("RENTPOLY", inner.centerX() + bs * 0.005f, inner.centerY() - bs * 0.05f + bs * 0.005f, text)
        text.color = Color.parseColor("#F2C14E")
        canvas.drawText("RENTPOLY", inner.centerX(), inner.centerY() - bs * 0.05f, text)
        text.color = saved
        canvas.restore()

        text.textSize = bs * 0.022f
        val savedColor = text.color
        text.color = Color.parseColor("#F1F5EC")
        val lines = wrap(centreText, inner.width() * 0.9f, text)
        var ty = inner.bottom - bs * 0.02f - (lines.size - 1) * text.textSize * 1.1f
        for (line in lines.take(3)) {
            canvas.drawText(line, inner.centerX(), ty, text)
            ty += text.textSize * 1.1f
        }
        if (g != null) {
            text.textSize = bs * 0.02f
            canvas.drawText("Ход ${g.turnNumber} · ${g.player.name}", inner.centerX(), inner.top + bs * 0.032f, text)
        }
        text.color = savedColor
    }

    private fun drawDice(canvas: Canvas, bs: Float) {
        val d = bs * 0.078f
        val gap = bs * 0.022f
        val cy = bs * 0.5f + bs * 0.075f
        val pop = 1f + 0.35f * dicePop
        drawDie(canvas, bs * 0.5f - gap / 2 - d / 2, cy, d, diceShown.first, diceSpin, pop, dicePop)
        drawDie(canvas, bs * 0.5f + gap / 2 + d / 2, cy, d, diceShown.second, -diceSpin * 0.8f, pop, dicePop)
    }

    private fun drawDie(canvas: Canvas, cx: Float, cy: Float, d: Float, value: Int, spin: Float, scale: Float, lift: Float) {
        // Shadow drops away as the die lifts.
        fill.color = Color.argb((90 * (1f - lift * 0.5f)).toInt(), 0, 0, 0)
        val sh = d * (1f + lift * 0.4f)
        rect.set(cx - sh / 2 + d * 0.06f, cy - sh / 2 + d * 0.1f + lift * d * 0.25f, cx + sh / 2 + d * 0.06f, cy + sh / 2 + d * 0.1f + lift * d * 0.25f)
        canvas.drawRoundRect(rect, d * 0.2f, d * 0.2f, fill)

        canvas.save()
        canvas.translate(cx, cy - lift * d * 0.35f)
        canvas.rotate(spin)
        canvas.scale(scale, scale)
        rect.set(-d / 2, -d / 2, d / 2, d / 2)
        fill.shader = LinearGradient(-d / 2, -d / 2, d / 2, d / 2, Color.WHITE, Color.parseColor("#D9D9D9"), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, d * 0.2f, d * 0.2f, fill)
        fill.shader = null
        stroke.color = Color.parseColor("#8A8A8A")
        stroke.strokeWidth = d * 0.025f
        canvas.drawRoundRect(rect, d * 0.2f, d * 0.2f, stroke)
        if (value > 0) {
            fill.color = Color.parseColor("#1A1A1A")
            val r = d * 0.085f
            val a = -d * 0.26f
            val b = 0f
            val cc = d * 0.26f
            fun pip(px: Float, py: Float) {
                canvas.drawCircle(px, py, r, fill)
            }
            when (value) {
                1 -> pip(b, b)
                2 -> { pip(a, a); pip(cc, cc) }
                3 -> { pip(a, a); pip(b, b); pip(cc, cc) }
                4 -> { pip(a, a); pip(cc, a); pip(a, cc); pip(cc, cc) }
                5 -> { pip(a, a); pip(cc, a); pip(b, b); pip(a, cc); pip(cc, cc) }
                else -> { pip(a, a); pip(cc, a); pip(a, b); pip(cc, b); pip(a, cc); pip(cc, cc) }
            }
        }
        canvas.restore()
    }

    private fun drawTokens(canvas: Canvas, bs: Float, g: Game) {
        val r = bs * 0.026f
        val alivePlayers = g.players.filter { !it.bankrupt }
        for ((n, p) in alivePlayers.withIndex()) {
            val pos = tokenPos[p.id] ?: continue
            val lift = tokenLift[p.id] ?: 0f
            val dx = ((n % 2) - 0.5f) * r * 1.5f
            val dy = ((n / 2) - 0.5f) * r * 1.5f
            val x = pos.x * bs + dx
            val y = pos.y * bs + dy
            // Shadow stays on the board; the token rises off it.
            fill.color = Color.argb((110 * (1f - lift * 0.6f)).toInt(), 0, 0, 0)
            val sr = r * (1f + lift * 0.5f)
            canvas.drawCircle(x + r * 0.15f, y + r * 0.25f + lift * r * 0.3f, sr, fill)
            val ty = y - lift * r * 1.6f
            if (p.id == g.current && !g.player.bankrupt) {
                val pulse = 0.5f + 0.5f * sin(SystemClock.elapsedRealtime() / 220.0).toFloat()
                stroke.color = Color.argb((140 + 100 * pulse).toInt(), 255, 255, 255)
                stroke.strokeWidth = r * 0.22f
                canvas.drawCircle(x, ty, r * (1.25f + 0.1f * pulse), stroke)
                postInvalidateOnAnimation()
            }
            fill.shader = RadialGradient(x - r * 0.35f, ty - r * 0.35f, r * 1.6f, lighten(p.color, 0.45f), darken(p.color, 0.35f), Shader.TileMode.CLAMP)
            canvas.drawCircle(x, ty, r, fill)
            fill.shader = null
            stroke.color = Color.parseColor("#CC222222")
            stroke.strokeWidth = r * 0.12f
            canvas.drawCircle(x, ty, r, stroke)
            emoji.textSize = r * 1.3f
            canvas.drawText(p.token, x, ty + r * 0.45f, emoji)
        }
    }

    private fun drawGlows(canvas: Canvas, bs: Float) {
        val now = SystemClock.elapsedRealtime()
        glows.removeAll { now - it.start > GLOW_MS }
        for (gl in glows) {
            val t = (now - gl.start).toFloat() / GLOW_MS
            cellScreenRect(gl.cell, bs, rect)
            val pulse = 0.5f + 0.5f * sin(t * Math.PI * 3).toFloat()
            stroke.color = Color.argb((220 * (1f - t)).toInt(), Color.red(gl.color), Color.green(gl.color), Color.blue(gl.color))
            stroke.strokeWidth = bs * (0.006f + 0.006f * pulse)
            canvas.drawRect(rect, stroke)
            fill.color = Color.argb((70 * (1f - t)).toInt(), Color.red(gl.color), Color.green(gl.color), Color.blue(gl.color))
            canvas.drawRect(rect, fill)
        }
    }

    private fun drawFloaters(canvas: Canvas, bs: Float) {
        val now = SystemClock.elapsedRealtime()
        floaters.removeAll { now - it.start > FLOAT_MS }
        for (f in floaters) {
            val t = (now - f.start).toFloat() / FLOAT_MS
            val y = f.y * bs - t * bs * 0.09f
            val alpha = (255 * (1f - t * t)).toInt()
            text.textSize = bs * (0.03f + 0.012f * min(1f, t * 4))
            val saved = text.color
            text.color = Color.argb(alpha, 0, 0, 0)
            canvas.drawText(f.text, f.x * bs + bs * 0.003f, y + bs * 0.003f, text)
            text.color = Color.argb(alpha, Color.red(f.color), Color.green(f.color), Color.blue(f.color))
            canvas.drawText(f.text, f.x * bs, y, text)
            text.color = saved
        }
    }

    private fun drawCard(canvas: Canvas, s: Float) {
        val shown = card ?: return
        val now = SystemClock.elapsedRealtime()
        val opening = min(1f, (now - shown.start) / 420f)
        val closing = if (shown.closing == 0L) 0f else min(1f, (now - shown.closing) / 260f)
        if (closing >= 1f) {
            card = null
            shown.onDone()
            return
        }
        val progress = if (shown.closing == 0L) OvershootInterpolator(1.4f).getInterpolation(opening) else 1f - closing
        val dim = (150 * min(progress, 1f)).toInt().coerceIn(0, 150)
        fill.color = Color.argb(dim, 0, 0, 0)
        canvas.drawRect(0f, 0f, s, s, fill)

        val w = s * 0.74f
        val h = s * 0.52f
        val cx = s / 2f
        val cy = s / 2f
        // A flip: the card is edge-on at the start and turns to face the player.
        val angle = (1f - progress.coerceIn(0f, 1f)) * 90f
        val sx = abs(cos(Math.toRadians(angle.toDouble()))).toFloat().coerceAtLeast(0.02f)
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(sx, 0.9f + 0.1f * progress.coerceIn(0f, 1f))
        rect.set(-w / 2, -h / 2, w / 2, h / 2)
        fill.color = Color.parseColor("#66000000")
        canvas.drawRoundRect(RectF(rect.left + s * 0.01f, rect.top + s * 0.015f, rect.right + s * 0.01f, rect.bottom + s * 0.015f), s * 0.025f, s * 0.025f, fill)
        fill.shader = paperShader
        canvas.drawRoundRect(rect, s * 0.025f, s * 0.025f, fill)
        fill.shader = null
        stroke.color = shown.color
        stroke.strokeWidth = s * 0.006f
        canvas.drawRoundRect(rect, s * 0.025f, s * 0.025f, stroke)

        val head = RectF(rect.left, rect.top, rect.right, rect.top + h * 0.22f)
        fill.shader = LinearGradient(0f, head.top, 0f, head.bottom, lighten(shown.color, 0.1f), darken(shown.color, 0.2f), Shader.TileMode.CLAMP)
        path.reset()
        path.addRoundRect(head, floatArrayOf(s * 0.025f, s * 0.025f, s * 0.025f, s * 0.025f, 0f, 0f, 0f, 0f), Path.Direction.CW)
        canvas.drawPath(path, fill)
        fill.shader = null
        text.textSize = h * 0.11f
        val saved = text.color
        text.color = Color.WHITE
        canvas.drawText(shown.title.uppercase(), 0f, head.centerY() + text.textSize * 0.35f, text)
        text.color = saved

        body.textSize = h * 0.075f
        val lines = wrap(shown.text, w * 0.86f, body)
        var y = head.bottom + h * 0.16f
        for (line in lines.take(6)) {
            canvas.drawText(line, 0f, y, body)
            y += body.textSize * 1.25f
        }
        body.textSize = h * 0.055f
        val savedBody = body.color
        body.color = Color.parseColor("#7A6E5E")
        canvas.drawText("нажми, чтобы продолжить", 0f, rect.bottom - h * 0.07f, body)
        body.color = savedBody
        canvas.restore()
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
