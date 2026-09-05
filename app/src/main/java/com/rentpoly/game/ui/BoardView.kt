package com.rentpoly.game.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import com.rentpoly.game.model.Board
import com.rentpoly.game.model.CellType
import com.rentpoly.game.model.Game
import com.rentpoly.game.model.Group
import kotlin.math.min

/**
 * The board, drawn from scratch: forty squares around the edge, colour bands,
 * houses, owner marks, tokens, and the dice in the middle. Everything is
 * vector, so it is sharp at any size and there is nothing to ship.
 *
 * Tokens keep their own on-screen positions so they can walk square by square
 * rather than teleport; [animateWalk] drives that.
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

    /** Called with a cell index when the user taps a square. */
    var onCellTapped: ((Int) -> Unit)? = null

    /** Short text shown in the middle of the board. */
    var centreText: String = ""
        set(value) { if (field != value) { field = value; invalidate() } }

    private companion object {
        const val CORNER = 0.135f
        val BOARD_BG = Color.parseColor("#DCE9D5")
        val CELL_BG = Color.parseColor("#F3F1E6")
        val CELL_BORDER = Color.parseColor("#2B2B2B")
        val TEXT = Color.parseColor("#1F1F1F")
        val MORTGAGE = Color.parseColor("#99000000")
        val HOUSE = Color.parseColor("#2E9E4A")
        val HOTEL = Color.parseColor("#D62828")
        const val STEP_MS = 190L
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = CELL_BORDER
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val emoji = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val tokenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tokenEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#55000000") }
    private val dicePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1A1A1A") }
    private val rect = RectF()

    /** Where each token is drawn right now, in board fractions (0..1). */
    private val tokenPos = HashMap<Int, PointF>()
    private var animator: ValueAnimator? = null
    private var diceShown: Pair<Int, Int> = 0 to 0
    private var diceRolling = false
    private var diceAnimator: ValueAnimator? = null

    private fun side(): Float = min(width, height).toFloat()
    private fun originX(): Float = (width - side()) / 2f
    private fun originY(): Float = (height - side()) / 2f

    /** Bounds of cell [i] in board fractions. */
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

    /** Which edge of the board cell [i] sits on: 0 bottom, 1 left, 2 top, 3 right. */
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

    fun syncTokens() {
        val g = game ?: return
        for (p in g.players) {
            if (!tokenPos.containsKey(p.id)) tokenPos[p.id] = cellCentre(p.position)
        }
    }

    /** Puts a token straight on a cell, no walking. */
    fun placeToken(player: Int, cell: Int) {
        tokenPos[player] = cellCentre(cell)
        invalidate()
    }

    /**
     * Walks a token from [from] by [steps] squares (negative = backwards),
     * one hop per square, then calls [onDone].
     */
    fun animateWalk(player: Int, from: Int, steps: Int, onDone: () -> Unit) {
        animator?.cancel()
        if (steps == 0) {
            placeToken(player, from)
            onDone()
            return
        }
        val dir = if (steps > 0) 1 else -1
        val count = kotlin.math.abs(steps)
        val points = ArrayList<PointF>(count + 1)
        var i = from
        points += cellCentre(i)
        repeat(count) {
            i = ((i + dir) % Board.SIZE + Board.SIZE) % Board.SIZE
            points += cellCentre(i)
        }
        val anim = ValueAnimator.ofFloat(0f, count.toFloat()).apply {
            duration = STEP_MS * count
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                val k = t.toInt().coerceIn(0, count - 1)
                val f = t - k
                // Ease within each hop so the token lands, not slides.
                val e = f * f * (3f - 2f * f)
                val a = points[k]
                val b = points[k + 1]
                // A small arc lifts the token off the board mid-hop.
                val lift = -0.012f * kotlin.math.sin(Math.PI * f).toFloat()
                tokenPos[player] = PointF(a.x + (b.x - a.x) * e, a.y + (b.y - a.y) * e + lift)
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    tokenPos[player] = points.last()
                    invalidate()
                    onDone()
                }
            })
        }
        animator = anim
        anim.start()
    }

    /** Tumbles the dice for a moment before settling on [d1] and [d2]. */
    fun animateDice(d1: Int, d2: Int, onDone: () -> Unit) {
        diceAnimator?.cancel()
        diceRolling = true
        val rnd = java.util.Random()
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                diceShown = if (t < 0.85f) (rnd.nextInt(6) + 1) to (rnd.nextInt(6) + 1) else d1 to d2
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    diceShown = d1 to d2
                    diceRolling = false
                    invalidate()
                    onDone()
                }
            })
        }
        diceAnimator = anim
        anim.start()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val size = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) w else min(w, h)
        setMeasuredDimension(size, size)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
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

    override fun onDraw(canvas: Canvas) {
        val s = side()
        val ox = originX()
        val oy = originY()
        canvas.save()
        canvas.translate(ox, oy)

        fill.shader = null
        fill.color = BOARD_BG
        rect.set(0f, 0f, s, s)
        canvas.drawRoundRect(rect, s * 0.01f, s * 0.01f, fill)

        val g = game
        for (i in 0 until Board.SIZE) drawCell(canvas, i, s, g)
        drawCentre(canvas, s, g)
        if (g != null) drawTokens(canvas, s, g)
        canvas.restore()
    }

    private fun drawCell(canvas: Canvas, i: Int, s: Float, g: Game?) {
        val cell = Board.cell(i)
        cellRect(i, rect)
        rect.set(rect.left * s, rect.top * s, rect.right * s, rect.bottom * s)
        fill.shader = null
        fill.color = CELL_BG
        canvas.drawRect(rect, fill)
        stroke.strokeWidth = s * 0.0025f
        canvas.drawRect(rect, stroke)

        val edge = edgeOf(i)
        val corner = i % 10 == 0
        val band = s * 0.028f
        val holding = g?.holdings?.get(i)

        // Colour band on the inner side of the square.
        if (cell.group != null && cell.type == CellType.PROPERTY) {
            fill.color = cell.group.color
            val b = RectF(rect)
            when (edge) {
                0 -> b.bottom = b.top + band
                1 -> b.left = b.right - band
                2 -> b.top = b.bottom - band
                else -> b.right = b.left + band
            }
            canvas.drawRect(b, fill)
            canvas.drawRect(b, stroke)
            if (holding != null && holding.houses > 0) drawHouses(canvas, b, edge, holding.houses, s)
        }

        // Owner mark on the outer edge.
        if (holding != null && holding.owner >= 0 && g != null) {
            fill.color = g.players[holding.owner].color
            val m = RectF(rect)
            val t = s * 0.014f
            when (edge) {
                0 -> m.top = m.bottom - t
                1 -> m.right = m.left + t
                2 -> m.bottom = m.top + t
                else -> m.left = m.right - t
            }
            canvas.drawRect(m, fill)
        }

        // Text, rotated to run along the square on the side columns.
        canvas.save()
        canvas.rotate(
            when (edge) { 1 -> 90f; 3 -> -90f; else -> 0f },
            rect.centerX(),
            rect.centerY(),
        )
        val w = if (edge == 1 || edge == 3) rect.height() else rect.width()
        val h = if (edge == 1 || edge == 3) rect.width() else rect.height()
        val cx = rect.centerX()
        val cy = rect.centerY()
        if (corner) {
            emoji.textSize = s * 0.05f
            canvas.drawText(cell.icon, cx, cy - s * 0.005f, emoji)
            text.textSize = s * 0.02f
            canvas.drawText(cell.name, cx, cy + s * 0.032f, text)
        } else {
            // Where the colour band ends up in local (rotated) coordinates:
            // the inner side of the square is local top for the bottom row,
            // the left column (rotated +90) and the right column (rotated
            // -90); only the top row has it at the local bottom.
            val bandInset = if (cell.type == CellType.PROPERTY) band else 0f
            val bandAtTop = edge != 2
            val localTop = cy - h / 2f + (if (bandAtTop) bandInset else 0f)
            val localBottom = cy + h / 2f - (if (bandAtTop) 0f else bandInset)
            text.textSize = s * 0.017f
            val lines = wrap(cell.name, w * 0.92f, text)
            var y = localTop + s * 0.008f + text.textSize
            for (line in lines.take(2)) {
                canvas.drawText(line, cx, y, text)
                y += text.textSize * 1.05f
            }
            if (cell.icon.isNotEmpty()) {
                emoji.textSize = s * 0.03f
                canvas.drawText(cell.icon, cx, (localTop + localBottom) / 2f + s * 0.018f, emoji)
            }
            val figure = if (cell.price > 0) cell.price else cell.tax
            if (figure > 0) {
                text.textSize = s * 0.016f
                canvas.drawText("$figure", cx, localBottom - s * 0.008f, text)
            }
        }
        canvas.restore()

        if (holding != null && holding.mortgaged) {
            fill.color = MORTGAGE
            canvas.drawRect(rect, fill)
            text.textSize = s * 0.02f
            val saved = text.color
            text.color = Color.WHITE
            canvas.drawText("ЗАЛОГ", rect.centerX(), rect.centerY() + s * 0.007f, text)
            text.color = saved
        }
    }

    private fun drawHouses(canvas: Canvas, band: RectF, edge: Int, houses: Int, s: Float) {
        val size = s * 0.016f
        if (houses >= 5) {
            fill.color = HOTEL
            val r = RectF(band.centerX() - size, band.centerY() - size * 0.6f, band.centerX() + size, band.centerY() + size * 0.6f)
            canvas.drawRoundRect(r, size * 0.2f, size * 0.2f, fill)
            return
        }
        fill.color = HOUSE
        val along = edge == 0 || edge == 2
        val len = if (along) band.width() else band.height()
        val gap = len / (houses + 1)
        for (k in 1..houses) {
            val cx = if (along) band.left + gap * k else band.centerX()
            val cy = if (along) band.centerY() else band.top + gap * k
            val r = RectF(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
            canvas.drawRoundRect(r, size * 0.2f, size * 0.2f, fill)
        }
    }

    private fun wrap(name: String, maxWidth: Float, paint: Paint): List<String> {
        if (paint.measureText(name) <= maxWidth) return listOf(name)
        val words = name.split(' ')
        val lines = ArrayList<String>()
        var line = ""
        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) <= maxWidth || line.isEmpty()) {
                line = candidate
            } else {
                lines += line
                line = word
            }
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }

    private fun drawCentre(canvas: Canvas, s: Float, g: Game?) {
        val c = CORNER * s
        val inner = RectF(c + s * 0.01f, c + s * 0.01f, s - c - s * 0.01f, s - c - s * 0.01f)
        fill.shader = LinearGradient(
            inner.left, inner.top, inner.right, inner.bottom,
            Color.parseColor("#C8DDB9"), Color.parseColor("#A9C99A"), Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(inner, s * 0.02f, s * 0.02f, fill)
        fill.shader = null

        // Logo, set diagonally like the printed board.
        canvas.save()
        canvas.rotate(-35f, inner.centerX(), inner.centerY())
        text.textSize = s * 0.075f
        val saved = text.color
        text.color = Color.parseColor("#33000000")
        canvas.drawText("RENTPOLY", inner.centerX() + s * 0.004f, inner.centerY() - s * 0.06f + s * 0.004f, text)
        text.color = Color.parseColor("#B22222")
        canvas.drawText("RENTPOLY", inner.centerX(), inner.centerY() - s * 0.06f, text)
        text.color = saved
        canvas.restore()

        // Dice.
        val d = s * 0.075f
        val gap = s * 0.02f
        val y = inner.centerY() + s * 0.06f
        drawDie(canvas, inner.centerX() - d - gap / 2, y - d / 2, d, diceShown.first)
        drawDie(canvas, inner.centerX() + gap / 2, y - d / 2, d, diceShown.second)

        // Message.
        text.textSize = s * 0.022f
        val lines = wrap(centreText, inner.width() * 0.9f, text)
        var ty = inner.bottom - s * 0.02f - (lines.size - 1) * text.textSize * 1.1f
        for (line in lines.take(3)) {
            canvas.drawText(line, inner.centerX(), ty, text)
            ty += text.textSize * 1.1f
        }
        if (g != null) {
            text.textSize = s * 0.02f
            canvas.drawText("Ход ${g.turnNumber} · ${g.player.name}", inner.centerX(), inner.top + s * 0.035f, text)
        }
    }

    private fun drawDie(canvas: Canvas, x: Float, y: Float, d: Float, value: Int) {
        rect.set(x + d * 0.04f, y + d * 0.06f, x + d * 1.04f, y + d * 1.06f)
        canvas.drawRoundRect(rect, d * 0.18f, d * 0.18f, shadow)
        rect.set(x, y, x + d, y + d)
        canvas.drawRoundRect(rect, d * 0.18f, d * 0.18f, dicePaint)
        stroke.strokeWidth = d * 0.03f
        canvas.drawRoundRect(rect, d * 0.18f, d * 0.18f, stroke)
        if (value == 0) return
        val r = d * 0.08f
        val a = d * 0.25f
        val b = d * 0.5f
        val cc = d * 0.75f
        fun pip(px: Float, py: Float) = canvas.drawCircle(x + px, y + py, r, pipPaint)
        when (value) {
            1 -> pip(b, b)
            2 -> { pip(a, a); pip(cc, cc) }
            3 -> { pip(a, a); pip(b, b); pip(cc, cc) }
            4 -> { pip(a, a); pip(cc, a); pip(a, cc); pip(cc, cc) }
            5 -> { pip(a, a); pip(cc, a); pip(b, b); pip(a, cc); pip(cc, cc) }
            else -> { pip(a, a); pip(cc, a); pip(a, b); pip(cc, b); pip(a, cc); pip(cc, cc) }
        }
    }

    private fun drawTokens(canvas: Canvas, s: Float, g: Game) {
        val r = s * 0.024f
        val alivePlayers = g.players.filter { !it.bankrupt }
        for ((n, p) in alivePlayers.withIndex()) {
            val pos = tokenPos[p.id] ?: continue
            // Spread tokens sharing a square so none hides another.
            val dx = ((n % 2) - 0.5f) * r * 1.6f
            val dy = ((n / 2) - 0.5f) * r * 1.6f
            val x = pos.x * s + dx
            val y = pos.y * s + dy
            canvas.drawCircle(x + r * 0.12f, y + r * 0.18f, r, shadow)
            tokenPaint.color = p.color
            canvas.drawCircle(x, y, r, tokenPaint)
            tokenEdge.strokeWidth = r * 0.18f
            canvas.drawCircle(x, y, r, tokenEdge)
            emoji.textSize = r * 1.3f
            canvas.drawText(p.token, x, y + r * 0.45f, emoji)
        }
    }
}
