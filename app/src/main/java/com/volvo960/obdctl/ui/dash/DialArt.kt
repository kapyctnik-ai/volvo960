package com.volvo960.obdctl.ui.dash

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * The three dial faces, cut out of the photograph of the real 960 cluster and
 * used one at a time. Nothing is redrawn: the moulded plastic, the printing and
 * the glass are the photograph's, and only the needles are vector, because a
 * needle has to move and a flat tapered shape draws exactly.
 *
 * The numbers below come from the crop that produced the assets — the pivot is
 * the dial's real centre inside its piece, which is not the piece's centre
 * (the crops are framed on the printing, not on the hub).
 */
object DialArt {

    /**
     * @param pivotX/[pivotY] the needle's hub, in piece pixels.
     * @param startAngle where the scale's zero sits, degrees from three
     *   o'clock, clockwise positive; [sweepAngle] how far the scale runs.
     */
    data class Piece(
        val asset: String,
        val size: Int,
        val pivotX: Float,
        val pivotY: Float,
        val startAngle: Float = 135f,
        val sweepAngle: Float = 270f,
    )

    /**
     * A needle's outline, in piece pixels, measured from the hub along the
     * pointing direction: it starts [baseRadius] out (the hub cap hides the
     * root) and tapers from [baseHalfWidth] to [tipHalfWidth] at [tipRadius].
     */
    data class Needle(
        val baseHalfWidth: Float,
        val baseRadius: Float,
        val tipHalfWidth: Float,
        val tipRadius: Float,
    )

    val SPEEDO = Piece("cluster/piece_speedo.png", 490, 245f, 245f)
    val TACHO = Piece("cluster/piece_tacho.png", 420, 200f, 204f)
    val CLOCK = Piece("cluster/piece_clock.png", 410, 198f, 192f)

    val SPEEDO_NEEDLE = Needle(baseHalfWidth = 9f, baseRadius = 44f, tipHalfWidth = 2f, tipRadius = 205f)
    val TACHO_NEEDLE = Needle(baseHalfWidth = 7f, baseRadius = 34f, tipHalfWidth = 1.75f, tipRadius = 124f)
    val HOUR_NEEDLE = Needle(baseHalfWidth = 4.5f, baseRadius = 14f, tipHalfWidth = 1.5f, tipRadius = 88f)
    val MINUTE_NEEDLE = Needle(baseHalfWidth = 3.5f, baseRadius = 14f, tipHalfWidth = 1.25f, tipRadius = 132f)
    val SECOND_NEEDLE = Needle(baseHalfWidth = 1.2f, baseRadius = 10f, tipHalfWidth = 0.6f, tipRadius = 148f)

    private val cache = HashMap<String, Bitmap?>()

    /** Decoded once per asset: three faces are small, and re-decoding stutters. */
    @Synchronized
    fun bitmap(context: Context, piece: Piece): Bitmap? = cache.getOrPut(piece.asset) {
        runCatching {
            context.applicationContext.assets.open(piece.asset).use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }
}
