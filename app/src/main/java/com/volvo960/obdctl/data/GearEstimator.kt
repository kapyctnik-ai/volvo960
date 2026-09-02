package com.volvo960.obdctl.data

import kotlin.math.abs

/**
 * Works out which gear the car is in, because nothing reports it.
 *
 * No OBD-II PID carries the selected gear, and this ECU has no proprietary one
 * either. What it does have is the arithmetic: for a given gear the ratio of
 * engine speed to road speed is a constant, fixed by the gearbox, the final
 * drive and the rolling circumference. Both halves are polled every pass.
 *
 * The first version numbered gears by rank — the highest ratio seen was called
 * first — which is wrong the moment you have not driven in every gear yet.
 * Cruising at 45 km/h and 1400 rpm reads 31 rpm per km/h and was announced as
 * first gear; it is direct drive, third on this car.
 *
 * So the ratios are matched against the gearbox instead. Two facts make that
 * possible without knowing the car's exact specification:
 *
 *  - the gearbox has a known set of internal ratios, and one of them is
 *    exactly 1.000 — direct drive is standard on both the four-speed automatic
 *    and the five-speed manual fitted to a 960;
 *  - the scale factor between an internal ratio and rpm-per-km/h is
 *    `final drive x 1000 / (60 x rolling circumference)`, and for anything a
 *    960 could be wearing that lands between 26 and 36. A 3.31 to 4.10 final
 *    drive on a 1.9 to 2.1 metre circumference cannot produce anything else.
 *
 * That range is what disambiguates. An observed 31 could be direct drive at a
 * scale of 31, or second gear at a scale of 21 — but 21 is not a scale any
 * 960 can have, so it is direct drive, and everything else follows. One
 * observed gear is enough to name it.
 */
class GearEstimator(private val store: Store) {

    interface Store {
        /** Learnt ratios, serialised as `ratio:samples;ratio:samples`. */
        var gearRatios: String
    }

    private data class Cluster(var ratio: Double, var samples: Int)

    /** A gearbox's internal ratios, highest first. */
    private class Gearbox(val name: String, val ratios: DoubleArray)

    private companion object {
        /** Below these the ratio is noise: creeping, or a converter slipping wildly. */
        const val MIN_SPEED_KMH = 15
        const val MIN_RPM = 700
        /** Physically possible range of rpm per km/h, well either side of any gearbox. */
        const val MIN_RATIO = 8.0
        const val MAX_RATIO = 200.0
        /** Two consecutive samples must agree this closely to count as steady. */
        const val STEADY_TOLERANCE = 0.04
        /** How far from a cluster's centre still belongs to it. */
        const val MATCH_TOLERANCE = 0.07
        /** A cluster is only believed once it has been seen this often. */
        const val MIN_SAMPLES = 8
        /** More than a gearbox could have; guards against noise breeding clusters. */
        const val MAX_CLUSTERS = 8
        /** How much each new sample moves a cluster's centre. */
        const val LEARN_RATE = 0.15
        const val SAVE_EVERY = 10

        /**
         * rpm per km/h in direct drive: `final drive x 1000 / (60 x circumference)`.
         * 3.31-4.10 on the axle, 1.9-2.1 m around the tyre. Nothing a 960 wears
         * falls outside this.
         */
        const val SCALE_MIN = 26.0
        const val SCALE_MAX = 36.0

        /**
         * How far a cluster may sit from a gearbox ratio and still be it.
         * Generous, because a slipping torque converter puts a gear up to
         * eight per cent out — and gears are 25 % apart at the very closest,
         * so there is nothing to confuse it with.
         */
        const val FIT_TOLERANCE = 0.15

        /**
         * The two gearboxes a 960 came with. The automatic is first because
         * this car has one, and because a torque converter is what makes a gear
         * show up twice.
         */
        val GEARBOXES = listOf(
            Gearbox("AW30-40", doubleArrayOf(2.393, 1.450, 1.000, 0.694)),
            Gearbox("M90", doubleArrayOf(3.54, 2.05, 1.38, 1.00, 0.81)),
        )
    }

    private val clusters = mutableListOf<Cluster>()
    private var lastRatio: Double? = null
    private var currentGear: String? = null
    private var sinceSave = 0

    init {
        load()
    }

    /**
     * @return the gear as it should be shown ("3", or "4L" when the torque
     *   converter is locked up in it), or null while it cannot be told.
     */
    fun update(rpm: Int?, speedKmh: Int?): String? {
        if (rpm == null || speedKmh == null || speedKmh < MIN_SPEED_KMH || rpm < MIN_RPM) {
            lastRatio = null
            currentGear = null
            return null
        }
        val ratio = rpm.toDouble() / speedKmh
        if (ratio < MIN_RATIO || ratio > MAX_RATIO) return currentGear

        val previous = lastRatio
        lastRatio = ratio
        // Mid-shift the ratio sweeps through every value between two gears.
        // Learning from that would smear the clusters together, so only steady
        // samples are used — the previous reading has to agree with this one.
        if (previous == null || abs(ratio - previous) / ratio > STEADY_TOLERANCE) {
            return currentGear
        }

        val match = clusters.minByOrNull { abs(it.ratio - ratio) / it.ratio }
        if (match != null && abs(match.ratio - ratio) / match.ratio <= MATCH_TOLERANCE) {
            val wasBelieved = match.samples >= MIN_SAMPLES
            match.ratio += (ratio - match.ratio) * LEARN_RATE
            match.samples++
            currentGear = nameFor(match)
            if (!wasBelieved && match.samples >= MIN_SAMPLES) {
                sinceSave = 0
                save()
                return currentGear
            }
        } else if (clusters.size < MAX_CLUSTERS) {
            val fresh = Cluster(ratio, 1)
            clusters += fresh
            currentGear = nameFor(fresh)
            // A gear found for the first time is the whole point of learning,
            // and the app can be killed at any moment — save it now.
            sinceSave = 0
            save()
            return currentGear
        }

        if (++sinceSave >= SAVE_EVERY) {
            sinceSave = 0
            save()
        }
        return currentGear
    }

    /** The scale factor and gearbox that best explain the ratios seen so far. */
    private class Fit(val scale: Double, val box: Gearbox, val error: Double)

    private fun bestFit(believed: List<Cluster>): Fit? {
        if (believed.isEmpty()) return null
        val anchor = believed.maxByOrNull { it.samples } ?: return null
        var best: Fit? = null
        for (box in GEARBOXES) {
            for (candidate in box.ratios) {
                // Assume the best-attested cluster is this gear, and see what
                // scale that implies. Most guesses are thrown out by physics
                // before anything else is even considered.
                val scale = anchor.ratio / candidate
                if (scale < SCALE_MIN || scale > SCALE_MAX) continue
                var total = 0.0
                var fits = true
                for (cluster in believed) {
                    val error = relativeErrorToNearest(cluster.ratio, scale, box)
                    if (error > FIT_TOLERANCE) {
                        fits = false
                        break
                    }
                    total += error * error
                }
                if (!fits) continue
                val fit = Fit(scale, box, total / believed.size)
                // Ties go to the first gearbox listed, which is the one this
                // car has.
                if (best == null || fit.error < best.error - 1e-9) best = fit
            }
        }
        return best
    }

    private fun relativeErrorToNearest(ratio: Double, scale: Double, box: Gearbox): Double =
        box.ratios.minOf { abs(ratio - scale * it) / (scale * it) }

    private fun gearIndex(ratio: Double, scale: Double, box: Gearbox): Int? {
        var bestIndex = -1
        var bestError = Double.MAX_VALUE
        box.ratios.forEachIndexed { index, gear ->
            val error = abs(ratio - scale * gear) / (scale * gear)
            if (error < bestError) {
                bestError = error
                bestIndex = index
            }
        }
        return if (bestIndex == -1 || bestError > FIT_TOLERANCE) null else bestIndex
    }

    /**
     * Names the gear a cluster belongs to: "3", or "4L" when the torque
     * converter is locked up in it.
     *
     * With the converter unlocked it slips, so the engine turns five to eight
     * per cent faster for the same road speed. Both readings match the same
     * gearbox ratio; the lower of the two is the locked one, because locked
     * means no slip.
     */
    private fun nameFor(cluster: Cluster): String? {
        if (cluster.samples < MIN_SAMPLES) return null
        val believed = clusters.filter { it.samples >= MIN_SAMPLES }
        val fit = bestFit(believed) ?: return rankName(cluster, believed)
        val index = gearIndex(cluster.ratio, fit.scale, fit.box) ?: return null
        val sameGear = believed.filter { gearIndex(it.ratio, fit.scale, fit.box) == index }
        val lowest = sameGear.minOf { it.ratio }
        val locked = sameGear.size > 1 && cluster.ratio <= lowest + 1e-9
        return if (locked) "${index + 1}L" else "${index + 1}"
    }

    /**
     * Fallback for ratios no gearbox explains — a different final drive, or
     * wheels far from standard. Numbers by rank, and says nothing until two
     * gears have been told apart, since one alone could be any of them.
     */
    private fun rankName(cluster: Cluster, believed: List<Cluster>): String? {
        if (believed.size < 2) return null
        val order = believed.sortedByDescending { it.ratio }
        val index = order.indexOfFirst { it === cluster }
        return if (index == -1) null else "${index + 1}?"
    }

    /** Forgets the learnt ratios — for a tyre change, or a bad first drive. */
    fun reset() {
        clusters.clear()
        lastRatio = null
        currentGear = null
        store.gearRatios = ""
    }

    private fun load() {
        clusters.clear()
        store.gearRatios.split(';').forEach { entry ->
            val parts = entry.split(':')
            if (parts.size != 2) return@forEach
            val ratio = parts[0].toDoubleOrNull() ?: return@forEach
            val samples = parts[1].toIntOrNull() ?: return@forEach
            if (ratio in MIN_RATIO..MAX_RATIO) clusters += Cluster(ratio, samples)
        }
    }

    private fun save() {
        store.gearRatios = clusters.joinToString(";") { "%.3f:%d".format(it.ratio, it.samples) }
    }
}
