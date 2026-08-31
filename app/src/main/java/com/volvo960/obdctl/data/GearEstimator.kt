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
    private var currentGear: Int? = null
    private var sinceSave = 0

    init {
        load()
    }

    /**
     * @param moving false while the injectors are cut or the car is stopped —
     *   the ratio is still valid on the overrun, so only a stopped car and a
     *   disconnected reading are excluded.
     * @return the gear, or null while it cannot be told.
     */
    fun update(rpm: Int?, speedKmh: Int?): Int? {
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
            match.ratio += (ratio - match.ratio) * LEARN_RATE
            match.samples++
            currentGear = gearOf(match)
        } else if (clusters.size < MAX_CLUSTERS) {
            val fresh = Cluster(ratio, 1)
            clusters += fresh
            currentGear = gearOf(fresh)
        }

        if (++sinceSave >= SAVE_EVERY) {
            sinceSave = 0
            save()
        }
        return currentGear
    }

    /**
     * Gears are numbered by ratio, highest first — that is what first gear is.
     * Nothing is shown until a second gear has been seen: with one cluster there
     * is no telling whether it is first or fifth.
     */
    private fun gearOf(cluster: Cluster): Int? {
        if (cluster.samples < MIN_SAMPLES) return null
        val believable = clusters.filter { it.samples >= MIN_SAMPLES }
        if (believable.size < 2) return null
        val order = believable.sortedByDescending { it.ratio }
        val index = order.indexOfFirst { it === cluster }
        return if (index == -1) null else index + 1
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
