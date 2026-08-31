package com.volvo960.obdctl.prefs

import android.content.Context
import com.volvo960.obdctl.data.VehicleDataPoller

/** Small persisted settings: the chosen dongle and the running counters. */
class AppPrefs(context: Context) : VehicleDataPoller.TripStore {
    private val prefs = context.applicationContext.getSharedPreferences("volvo960_prefs", Context.MODE_PRIVATE)

    var lastDeviceAddress: String?
        get() = prefs.getString(KEY_DEVICE_ADDRESS, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_ADDRESS, value).apply()

    override var tripKm: Double
        get() = double(KEY_TRIP_KM)
        set(value) = putDouble(KEY_TRIP_KM, value)

    /** Feeds the odometer tile. The car's own odometer isn't readable, so this counts from install. */
    override var totalKm: Double
        get() = double(KEY_TOTAL_KM)
        set(value) = putDouble(KEY_TOTAL_KM, value)

    /** Litres burnt on the current trip. */
    override var tripFuelL: Double
        get() = double(KEY_TRIP_FUEL)
        set(value) = putDouble(KEY_TRIP_FUEL, value)

    /**
     * What the tank holds, litres. Entered by hand after filling up and then
     * drawn down by the computed flow — the car's level sender reports a
     * percentage that is far too coarse and lags for minutes after a fill.
     */
    override var tankLiters: Double
        get() = double(KEY_TANK_LITERS)
        set(value) = putDouble(KEY_TANK_LITERS, value)

    private fun double(key: String): Double = Double.fromBits(prefs.getLong(key, 0L))

    private fun putDouble(key: String, value: Double) {
        prefs.edit().putLong(key, value.toRawBits()).apply()
    }

    companion object {
        private const val KEY_DEVICE_ADDRESS = "last_device_address"
        private const val KEY_TRIP_KM = "trip_km"
        private const val KEY_TOTAL_KM = "total_km"
        private const val KEY_TRIP_FUEL = "trip_fuel_l"
        private const val KEY_TANK_LITERS = "tank_liters"
    }
}
