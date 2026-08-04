package com.volvo960.obdctl.data

/**
 * Everything the dashboard draws. Each reading is nullable so a gauge can
 * tell "not measured yet / source unavailable" apart from "measured zero" —
 * the difference matters on a car where several sources are still unknown.
 */
data class VehicleState(
    val speedKmh: Int? = null,
    val rpm: Int? = null,
    val coolantTempC: Int? = null,
    val atfTempC: Int? = null,
    val fuelLevelPercent: Int? = null,
    val tripKm: Double = 0.0,
)
