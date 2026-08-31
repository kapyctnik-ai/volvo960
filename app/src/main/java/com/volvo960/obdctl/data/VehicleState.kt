package com.volvo960.obdctl.data

/**
 * Everything the tiles can show. Anything the car doesn't report stays null and
 * its tile shows a dash — an invented number on a dashboard is worse than a
 * blank one.
 */
data class VehicleState(
    val speedKmh: Int? = null,
    val rpm: Int? = null,
    val coolantTempC: Int? = null,
    val intakeTempC: Int? = null,
    val engineLoadPercent: Int? = null,
    val throttlePercent: Int? = null,
    /** Worked out from the rpm-to-speed ratio; null until the gears are learnt. */
    val gear: Int? = null,
    /** Litres per hour burnt right now. */
    val fuelRateLph: Double? = null,
    /** Litres per 100 km right now; null while stopped, where L/h is the honest figure. */
    val consumptionL100: Double? = null,
    /** Trip average, litres per 100 km. */
    val averageL100: Double? = null,
    /** How the fuel rate was obtained, for the tile's subtitle. */
    val fuelSource: FuelSource? = null,
    val tripKm: Double = 0.0,
    val totalKm: Double = 0.0,
    /** Fuel burnt on this trip, litres. */
    val tripFuelL: Double = 0.0,
    /** What the tank is believed to hold, litres — filled in by hand. */
    val tankLiters: Double = 0.0,
    /** Kilometres left on the tank at the trip average. */
    val rangeKm: Int? = null,
)

/** Where the litres-per-hour figure comes from; they are not equally trustworthy. */
enum class FuelSource {
    /** PID 015E, the ECU's own figure. Exact, and rare on a 1996 car. */
    ECU_FUEL_RATE,

    /** PID 0110, mass air flow over the stoichiometric ratio. Good. */
    MAF,

    /** MAP + intake temperature + rpm, with an assumed volumetric efficiency. Rough. */
    SPEED_DENSITY,
}
