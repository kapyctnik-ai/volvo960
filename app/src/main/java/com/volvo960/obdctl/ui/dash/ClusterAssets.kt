package com.volvo960.obdctl.ui.dash

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import org.json.JSONObject

/**
 * Photographic cluster artwork, if it has been dropped into
 * `assets/cluster/`. Drawing the real dial face as an image and compositing
 * only the needles on top is the one way this can look like the actual
 * instrument panel rather than a redrawing of it — vector primitives can
 * approximate the layout but never the material.
 *
 * Everything is optional: with no artwork installed the view falls back to
 * drawing the gauges itself.
 */
class ClusterAssets private constructor(
    val face: Bitmap,
    val needleLong: Bitmap,
    val needleShort: Bitmap,
    val needleSecond: Bitmap?,
    val hub: Bitmap?,
    val geometry: Geometry,
) {
    /** Pixel coordinates within [face]; the view scales them to its own size. */
    data class Dial(
        val cx: Float,
        val cy: Float,
        val r: Float,
        val startAngle: Float,
        val sweepAngle: Float,
    )

    data class Geometry(
        val fuel: Dial?,
        val clock: Dial?,
        val speedo: Dial?,
        val tacho: Dial?,
        val temp: Dial?,
        val odometerRect: RectF?,
        val tripRect: RectF?,
        val resetKnob: Dial?,
    )

    companion object {
        private const val DIR = "cluster"

        fun load(context: Context): ClusterAssets? {
            val assets = context.assets
            val names = runCatching { assets.list(DIR)?.toSet() }.getOrNull() ?: return null
            if (!names.contains("cluster_face.png")) return null

            fun bitmap(name: String): Bitmap? = runCatching {
                assets.open("$DIR/$name").use { BitmapFactory.decodeStream(it) }
            }.getOrNull()

            val face = bitmap("cluster_face.png") ?: return null
            val long = bitmap("needle_long.png") ?: return null
            val short = bitmap("needle_short.png") ?: long
            val geometryJson = runCatching {
                assets.open("$DIR/cluster_geometry.json").use { it.readBytes().decodeToString() }
            }.getOrNull() ?: return null

            val geometry = runCatching { parseGeometry(JSONObject(geometryJson)) }.getOrNull() ?: return null

            return ClusterAssets(
                face = face,
                needleLong = long,
                needleShort = short,
                needleSecond = bitmap("needle_second.png"),
                hub = bitmap("hub.png"),
                geometry = geometry,
            )
        }

        private fun parseGeometry(root: JSONObject): Geometry {
            fun dial(key: String): Dial? {
                val o = root.optJSONObject(key) ?: return null
                return Dial(
                    cx = o.optDouble("cx").toFloat(),
                    cy = o.optDouble("cy").toFloat(),
                    r = o.optDouble("r").toFloat(),
                    startAngle = o.optDouble("startAngle", 150.0).toFloat(),
                    sweepAngle = o.optDouble("sweepAngle", 240.0).toFloat(),
                )
            }

            fun rect(owner: String, key: String): RectF? {
                val array = root.optJSONObject(owner)?.optJSONArray(key) ?: return null
                if (array.length() < 4) return null
                return RectF(
                    array.optDouble(0).toFloat(),
                    array.optDouble(1).toFloat(),
                    array.optDouble(2).toFloat(),
                    array.optDouble(3).toFloat(),
                )
            }

            val speedo = root.optJSONObject("speedo")
            val knob = speedo?.optJSONObject("resetKnob")?.let {
                Dial(
                    cx = it.optDouble("cx").toFloat(),
                    cy = it.optDouble("cy").toFloat(),
                    r = it.optDouble("r").toFloat(),
                    startAngle = 0f,
                    sweepAngle = 0f,
                )
            }

            return Geometry(
                fuel = dial("fuel"),
                clock = dial("clock"),
                speedo = dial("speedo"),
                tacho = dial("tacho"),
                temp = dial("temp"),
                odometerRect = rect("speedo", "odometerRect"),
                tripRect = rect("speedo", "tripRect"),
                resetKnob = knob,
            )
        }
    }
}
