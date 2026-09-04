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
 * The gearbox is known — an AW30-40 four-speed automatic — so its four ratios
 * are fixed here, and the only thing to learn is the scale that turns them into
 * rpm per km/h: `final drive x 1000 / (60 x rolling circumference)`. The axle
 * and tyres give 33.6, but the car reads a few per cent under that because OBD
 * speed comes off the speedometer sender, so the scale is calibrated from what
 * is actually observed, within a band around the arithmetic.
 *
 * Naming then needs no history at all: the current ratio is matched to the
 * nearest of the four gears at the calibrated scale. Earlier versions named
 * gears by rank among the ratios seen so far and could announce a sixth gear
 * on a four-speed box, because a torque converter makes every gear show up
 * twice — locked and slipping — and rank counted clusters, not gears.
 *
 * Lock-up is read off the same number: with the converter locked there is no
 * slip and the ratio sits on the gear's value; slipping, it sits a few per
 * cent above. Calibration therefore uses the lowest ratio seen in each gear,
 * which is the locked one.
 */
class GearEstimator(private val store: Store) {

    interface Store {
        /** Learnt ratios, serialised as `ratio:samples;ratio:samples`. */
        var gearRatios: String
    }

    private data class Cluster(var ratio: Double, var samples: Int)

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
        const val MATCH_TOLERANCE = 0.05
        /** A cluster is only believed once it has been seen this often. */
        const val MIN_SAMPLES = 8
        /** Four gears, locked and slipping, plus room for a little noise. */
        const val MAX_CLUSTERS = 10
        /** How much each new sample moves a cluster's centre. */
        const val LEARN_RATE = 0.15
        const val SAVE_EVERY = 10

        /** AW30-40: 2.393 / 1.450 / 1.000 / 0.694. */
        val GEAR_RATIOS = doubleArrayOf(2.393, 1.450, 1.000, 0.694)

        /**
         * What this car should read in direct drive: a 4.00 axle on 205/55 R16.
         * The tyre is 16 x 25.4 + 2 x 205 x 0.55 = 631.9 mm across, so 1.985 m
         * around, and 4000 / (60 x 1.985) = 33.6.
         */
        const val EXPECTED_SCALE = 33.6
        /** How far the calibrated scale may stray from the arithmetic. */
        const val SCALE_BAND = 0.15

        /** How far a ratio may sit from a gear's value and still be that gear. */
        const val GEAR_TOLERANCE = 0.15
        /** Within this of the gear's value the converter is locked; above it, slipping. */
        const val LOCKUP_SLIP = 0.035
        /** Clusters this far from every gear are noise and get dropped. */
        const val PRUNE_TOLERANCE = 0.20
    }

    private val clusters = mutableListOf<Cluster>()
    private var lastRatio: Double? = null
    private var currentGear: String? = null
    private var sinceSave = 0
    private var scale = EXPECTED_SCALE

    init {
        load()
        calibrate()
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
        // Neither naming nor learning is done from that — the previous reading
        // has to agree with this one.
        if (previous == null || abs(ratio - previous) / ratio > STEADY_TOLERANCE) {
            return currentGear
        }

        learn(ratio)
        currentGear = nameFor(ratio)
        return currentGear
    }

    private fun learn(ratio: Double) {
        val match = clusters.minByOrNull { abs(it.ratio - ratio) / it.ratio }
        if (match != null && abs(match.ratio - ratio) / match.ratio <= MATCH_TOLERANCE) {
            val wasBelieved = match.samples >= MIN_SAMPLES
            match.ratio += (ratio - match.ratio) * LEARN_RATE
            match.samples++
            if (!wasBelieved && match.samples >= MIN_SAMPLES) {
                // A cluster just became evidence; recalibrate and keep it.
                calibrate()
                sinceSave = 0
                save()
                return
            }
        } else {
            if (clusters.size >= MAX_CLUSTERS) prune(force = true)
            if (clusters.size < MAX_CLUSTERS) clusters += Cluster(ratio, 1)
        }
        if (++sinceSave >= SAVE_EVERY) {
            sinceSave = 0
            prune(force = false)
            calibrate()
            save()
        }
    }

    /**
     * Sets [scale] from the believed clusters.
     *
     * Each believed cluster implies a scale for each gear it could be; the one
     * that explains the most clusters wins, ties going to the arithmetic. It
     * is then refined to the lowest ratio seen per gear — the locked-up
     * reading — so that "L" means what it says.
     */
    private fun calibrate() {
        val believed = clusters.filter { it.samples >= MIN_SAMPLES }
        if (believed.isEmpty()) {
            scale = EXPECTED_SCALE
            return
        }
        val lowest = EXPECTED_SCALE * (1 - SCALE_BAND)
        val highest = EXPECTED_SCALE * (1 + SCALE_BAND)

        var bestScale = EXPECTED_SCALE
        var bestExplained = -1
        var bestDistance = Double.MAX_VALUE
        for (cluster in believed) {
            for (gear in GEAR_RATIOS) {
                val candidate = cluster.ratio / gear
                if (candidate < lowest || candidate > highest) continue
                val explained = believed.count { gearIndex(it.ratio, candidate) != null }
                val distance = abs(candidate - EXPECTED_SCALE)
                if (explained > bestExplained || (explained == bestExplained && distance < bestDistance)) {
                    bestScale = candidate
                    bestExplained = explained
                    bestDistance = distance
                }
            }
        }

        // Refine on the locked readings: per gear, the lowest believed ratio.
        val perGear = HashMap<Int, Double>()
        for (cluster in believed) {
            val index = gearIndex(cluster.ratio, bestScale) ?: continue
            val current = perGear[index]
            if (current == null || cluster.ratio < current) perGear[index] = cluster.ratio
        }
        if (perGear.isNotEmpty()) {
            val refined = perGear.entries.map { (index, ratio) -> ratio / GEAR_RATIOS[index] }.average()
            scale = refined.coerceIn(lowest, highest)
        } else {
            scale = bestScale
        }
    }

    private fun gearIndex(ratio: Double, atScale: Double): Int? {
        var bestIndex = -1
        var bestError = Double.MAX_VALUE
        GEAR_RATIOS.forEachIndexed { index, gear ->
            val error = abs(ratio - atScale * gear) / (atScale * gear)
            if (error < bestError) {
                bestError = error
                bestIndex = index
            }
        }
        return if (bestIndex == -1 || bestError > GEAR_TOLERANCE) null else bestIndex
    }

    /** "3", "4L", or null when the ratio is not near any gear. */
    private fun nameFor(ratio: Double): String? {
        val index = gearIndex(ratio, scale) ?: return null
        val slip = ratio / (scale * GEAR_RATIOS[index]) - 1.0
        return if (slip <= LOCKUP_SLIP) "${index + 1}L" else "${index + 1}"
    }

    /**
     * Drops clusters that no gear explains. With [force] the weakest one goes
     * regardless, to make room; otherwise only the ones that are plainly noise.
     */
    private fun prune(force: Boolean) {
        val noise = clusters.filter { cluster ->
            GEAR_RATIOS.minOf { abs(cluster.ratio - scale * it) / (scale * it) } > PRUNE_TOLERANCE
        }
        clusters.removeAll(noise.toSet())
        if (force && clusters.size >= MAX_CLUSTERS) {
            clusters.minByOrNull { it.samples }?.let { clusters.remove(it) }
        }
    }

    /**
     * What has been learnt, as lines of "ratio -> gear", for the diagnostic
     * dialog. Being able to see this is the difference between "the gear is
     * wrong" and knowing which ratio was misread.
     */
    fun describe(): List<String> {
        if (clusters.isEmpty()) return emptyList()
        // The gap between what the axle and tyres say and what the car reports
        // is the speed reading itself: the sender over-reads, and that same
        // error is in trip distance and in litres per 100 km.
        val bias = (EXPECTED_SCALE / scale - 1.0) * 100.0
        val note = when {
            bias > 2.0 -> "скорость по OBD завышена примерно на %.0f %%".format(bias)
            bias < -2.0 -> "скорость по OBD занижена примерно на %.0f %%".format(-bias)
            else -> "скорость по OBD сходится с расчётом"
        }
        val header = "AW30-40, масштаб %.1f (расчётный %.1f)\n%s".format(scale, EXPECTED_SCALE, note)
        val rows = clusters.sortedByDescending { it.ratio }.map { cluster ->
            val name = if (cluster.samples >= MIN_SAMPLES) nameFor(cluster.ratio) ?: "шум" else "мало данных"
            "%.1f  ->  %s   (%d замеров)".format(cluster.ratio, name, cluster.samples)
        }
        return listOf(header) + rows
    }

    /** Forgets the learnt ratios — for a tyre change, or a bad first drive. */
    fun reset() {
        clusters.clear()
        lastRatio = null
        currentGear = null
        scale = EXPECTED_SCALE
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
