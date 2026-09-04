package com.volvo960.obdctl.prefs

import android.content.Context
import com.volvo960.obdctl.data.GearEstimator
import com.volvo960.obdctl.data.VehicleDataPoller

/** Small persisted settings: the chosen dongle and the running counters. */
class AppPrefs(context: Context) : VehicleDataPoller.TripStore, GearEstimator.Store {
    private val prefs = context.applicationContext.getSharedPreferences("volvo960_prefs", Context.MODE_PRIVATE)

    var lastDeviceAddress: String?
        get() = prefs.getString(KEY_DEVICE_ADDRESS, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_ADDRESS, value).apply()

    /**
     * Which radio to use. A dual-mode dongle advertises both and only trying
     * one of them is a guess; "auto" tries Classic first because it is cheaper
     * on battery, but a dongle whose serial bridge is really on LE needs to be
     * told.
     */
    var transportPreference: String
        get() = prefs.getString(KEY_TRANSPORT, TRANSPORT_AUTO) ?: TRANSPORT_AUTO
        set(value) = prefs.edit().putString(KEY_TRANSPORT, value).apply()

    /** "SPP" or "BLE": the radio that carried the last working connection. */
    var lastWorkingLink: String?
        get() = prefs.getString(KEY_LAST_LINK, null)
        set(value) = prefs.edit().putString(KEY_LAST_LINK, value).apply()

    /**
     * ELM327 protocol number for `ATSP`. "3" is ISO 9141-2, which is what a
     * 1996 960 is wired for; "4"/"5" are KWP2000 and "0" lets the adapter hunt.
     * The self-test rewrites this when it finds a protocol the ECU answers on.
     */
    var obdProtocol: String
        get() = prefs.getString(KEY_PROTOCOL, "3") ?: "3"
        set(value) = prefs.edit().putString(KEY_PROTOCOL, value).apply()

    /** Learnt gear ratios; see [GearEstimator]. Survives restarts so the car is only learnt once. */
    override var gearRatios: String
        get() = prefs.getString(KEY_GEAR_RATIOS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEAR_RATIOS, value).apply()

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

    /** Litres burnt since the app was installed; pairs with [totalKm]. */
    override var totalFuelL: Double
        get() = double(KEY_TOTAL_FUEL)
        set(value) = putDouble(KEY_TOTAL_FUEL, value)

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
        const val TRANSPORT_AUTO = "auto"
        const val TRANSPORT_SPP = "spp"
        const val TRANSPORT_BLE = "ble"

        private const val KEY_DEVICE_ADDRESS = "last_device_address"
        private const val KEY_TRANSPORT = "transport_preference"
        private const val KEY_PROTOCOL = "obd_protocol"
        private const val KEY_LAST_LINK = "last_working_link"
        private const val KEY_GEAR_RATIOS = "gear_ratios"
        private const val KEY_TRIP_KM = "trip_km"
        private const val KEY_TOTAL_KM = "total_km"
        private const val KEY_TRIP_FUEL = "trip_fuel_l"
        private const val KEY_TOTAL_FUEL = "total_fuel_l"
        private const val KEY_TANK_LITERS = "tank_liters"
    }
}
