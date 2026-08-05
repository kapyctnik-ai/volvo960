package com.volvo960.obdctl.ui.dash

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import org.json.JSONObject

/**
 * Photographic cluster artwork from `assets/cluster/`.
 *
 * The dial face is a photograph of the real instrument panel with its needles
 * removed; only the needles, the counter windows and the tell-tale are drawn
 * on top at runtime. That is what makes this read as the actual panel rather
 * than as a redrawing of it — no amount of primitive-drawing reproduces the
 * moulded plastic, the glass or the printing.
 *
 * Needles stay vector: they are flat tapered shapes, so drawing them is exact
 * rather than approximate, and it avoids shipping and scaling sprite sheets.
 *
 * The whole thing is optional. With no artwork installed the view draws every
 * gauge itself.
 */
class ClusterAssets private constructor(
    val face: Bitmap,
    val geometry: Geometry,
) {
    /**
     * A needle's outline, in face pixels, measured from the dial centre along
     * the pointing direction: it starts [baseRadius] out (the hub cap hides
     * the root) and tapers from [baseHalfWidth] to [tipHalfWidth] at [tipRadius].
     */
    data class Needle(
        val baseHalfWidth: Float,
        val baseRadius: Float,
        val tipHalfWidth: Float,
        val tipRadius: Float,
    )

    /**
     * A dial, in face pixels. [startAngle] is where the scale's minimum sits
     * and [sweepAngle] how far it runs, both in degrees measured from the
     * three o'clock direction, clockwise positive.
     */
    data class Dial(
        val cx: Float,
        val cy: Float,
        val startAngle: Float,
        val sweepAngle: Float,
        val needle: Needle?,
    )

    data class Geometry(
        val fuel: Dial?,
        val clock: Dial?,
        val speedo: Dial?,
        val tacho: Dial?,
        val temp: Dial?,
        val hourNeedle: Needle?,
        val minuteNeedle: Needle?,
        val secondNeedle: Needle?,
        val odometerRect: RectF?,
        val tripRect: RectF?,
        val resetKnob: RectF?,
        /** Where the turn-signal arrows sit; used for the two numeric readouts. */
        val coolantRect: RectF?,
        val voltageRect: RectF?,
    )

    companion object {
        private const val DIR = "cluster"

        fun load(context: Context): ClusterAssets? = runCatching {
            val assets = context.assets
            val names = assets.list(DIR)?.toSet() ?: return null
            if (!names.contains("cluster_face.png") || !names.contains("cluster_geometry.json")) return null

            val face = assets.open("$DIR/cluster_face.png").use { BitmapFactory.decodeStream(it) } ?: return null
            val json = assets.open("$DIR/cluster_geometry.json").use { it.readBytes().decodeToString() }
            ClusterAssets(face, parseGeometry(JSONObject(json)))
        }.getOrNull()

        private fun parseGeometry(root: JSONObject): Geometry {
            fun needle(o: JSONObject?): Needle? {
                val n = o?.optJSONObject("needle") ?: return null
                return Needle(
                    baseHalfWidth = n.optDouble("baseHalfWidth", 4.0).toFloat(),
                    baseRadius = n.optDouble("baseRadius", 20.0).toFloat(),
                    tipHalfWidth = n.optDouble("tipHalfWidth", 1.5).toFloat(),
                    tipRadius = n.optDouble("tipRadius", 100.0).toFloat(),
                )
            }

            fun dial(key: String): Dial? {
                val o = root.optJSONObject(key) ?: return null
                return Dial(
                    cx = o.optDouble("cx").toFloat(),
                    cy = o.optDouble("cy").toFloat(),
                    startAngle = o.optDouble("startAngle", 135.0).toFloat(),
                    sweepAngle = o.optDouble("sweepAngle", 270.0).toFloat(),
                    needle = needle(o),
                )
            }

            fun rect(owner: String, key: String): RectF? {
                val a = root.optJSONObject(owner)?.optJSONArray(key) ?: return null
                if (a.length() < 4) return null
                return RectF(
                    a.optDouble(0).toFloat(),
                    a.optDouble(1).toFloat(),
                    a.optDouble(2).toFloat(),
                    a.optDouble(3).toFloat(),
                )
            }

            val clock = root.optJSONObject("clock")
            fun clockNeedle(key: String): Needle? {
                val n = clock?.optJSONObject(key) ?: return null
                return Needle(
                    baseHalfWidth = n.optDouble("baseHalfWidth", 3.0).toFloat(),
                    baseRadius = n.optDouble("baseRadius", 12.0).toFloat(),
                    tipHalfWidth = n.optDouble("tipHalfWidth", 1.0).toFloat(),
                    tipRadius = n.optDouble("tipRadius", 100.0).toFloat(),
                )
            }

            return Geometry(
                fuel = dial("fuel"),
                clock = dial("clock"),
                speedo = dial("speedo"),
                tacho = dial("tacho"),
                temp = dial("temp"),
                hourNeedle = clockNeedle("hourNeedle"),
                minuteNeedle = clockNeedle("minuteNeedle"),
                secondNeedle = clockNeedle("secondNeedle"),
                odometerRect = rect("speedo", "odometerRect"),
                tripRect = rect("speedo", "tripRect"),
                resetKnob = rect("speedo", "resetKnobRect"),
                coolantRect = rect("readouts", "coolantRect"),
                voltageRect = rect("readouts", "voltageRect"),
            )
        }
    }
}
