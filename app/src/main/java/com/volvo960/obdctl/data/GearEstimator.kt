package com.volvo960.obdctl.data

import kotlin.math.abs

/**
 * Works out which gear the car is in, because nothing reports it.
 *
 * No OBD-II PID carries the selected gear, and this ECU has no proprietary one
 * either. What it does have is the arithmetic: for a given gear the ratio of
 * engine speed to road speed is a constant, fixed by the gearbox, the final
 * drive and the rolling circumference. Two readings we already poll every pass
 * give it away.
 *
 * The ratios are learnt rather than hard-coded. Hard-coding needs the gearbox
 * variant, the differential and the exact tyre size — get any of them wrong and
 * every gear reads wrong. Learning needs none of them: steady driving in a gear
 * lands on the same ratio again and again, so the ratios cluster, and the
 * clusters are the gears. Sorted high to low they are first, second, and so on.
 *
 * Learnt clusters are persisted, so this only costs the first drive.
 */
class GearEstimator(private val store: Store) {

    interface Store {
        /** Learnt ratios, serialised as `ratio:samples;ratio:samples`. */
        var gearRatios: String
    }

    private data class Cluster(var ratio: Double, var samples: Int)

    private companion object {
        /** Below these the ratio is noise: creeping, or a clutch slipping. */
        const val MIN_SPEED_KMH = 15
        const val MIN_RPM = 700
        /** Physically possible range of rpm per km/h, well either side of any gearbox. */
        const val MIN_RATIO = 8.0
        const val MAX_RATIO = 200.0
        /** Two consecutive samples must agree this closely to count as steady. */
        const val STEADY_TOLERANCE = 0.04
        /** How far from a cluster's centre still belongs to it. */
        const val MATCH_TOLERANCE = 0.07

        /**
         * Two clusters this close are one gear seen twice: with the torque
         * converter locked and with it slipping. Real gears are nowhere near
         * this close — an AW30-40 steps 2.39 / 1.45 / 1.00 / 0.69, and the
         * tightest step on a 960 manual is a quarter — so anything inside this
         * band is lock-up, and it gets shown as such rather than counted as
         * another gear.
         */
        const val LOCKUP_TOLERANCE = 0.14
        /** A cluster is only believed once it has been seen this often. */
        const val MIN_SAMPLES = 8
        /** More than a gearbox could have; guards against noise breeding clusters. */
        const val MAX_CLUSTERS = 6
        /** How much each new sample moves a cluster's centre. */
        const val LEARN_RATE = 0.15
        const val SAVE_EVERY = 10
    }

    private val clusters = mutableListOf<Cluster>()
    private var lastRatio: Double? = null
    private var currentGear: String? = null
    private var sinceSave = 0

    init {
        load()
    }

    /**
     * @param moving false while the injectors are cut or the car is stopped —
     *   the ratio is still valid on the overrun, so only a stopped car and a
     *   disconnected reading are excluded.
     * @return the gear as it should be shown ("3", or "4L" when locked up), or
     *   null while it cannot be told.
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
            currentGear = gearOf(match)
            // Crossing into "believed" is worth writing down at once; the rest
            // of the time a periodic save keeps the writes down.
            if (!wasBelieved && match.samples >= MIN_SAMPLES) {
                sinceSave = 0
                save()
                return currentGear
            }
        } else if (clusters.size < MAX_CLUSTERS) {
            val fresh = Cluster(ratio, 1)
            clusters += fresh
            currentGear = gearOf(fresh)
            // A gear found for the first time is the whole point of learning,
            // and the app can be killed at any moment — save it now rather than
            // nine samples later.
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

    /**
     * Names the gear: "3" on its own, or "4L" when the torque converter is
     * locked up in it.
     *
     * Ratios are sorted highest first — first gear turns the engine fastest —
     * and neighbours within [LOCKUP_TOLERANCE] are folded into one gear, since
     * that gap is lock-up rather than a ratio change. Inside such a pair the
     * lower ratio is the locked one: with the converter locked there is no slip,
     * so the engine turns slower for the same road speed.
     *
     * Nothing is shown until two gears have been told apart — with one there is
     * no telling whether it is first or fifth.
     */
    private fun gearOf(cluster: Cluster): String? {
        if (cluster.samples < MIN_SAMPLES) return null
        val believable = clusters.filter { it.samples >= MIN_SAMPLES }.sortedByDescending { it.ratio }
        if (believable.size < 2) return null

        val groups = mutableListOf<MutableList<Cluster>>()
        for (candidate in believable) {
            val current = groups.lastOrNull()
            val previous = current?.last()
            if (previous != null && abs(previous.ratio - candidate.ratio) / previous.ratio <= LOCKUP_TOLERANCE) {
                current += candidate
            } else {
                groups += mutableListOf(candidate)
            }
        }
        if (groups.size < 2) return null

        val index = groups.indexOfFirst { group -> group.any { it === cluster } }
        if (index == -1) return null
        val group = groups[index]
        val number = index + 1
        return if (group.size > 1 && group.last() === cluster) "${number}L" else number.toString()
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
