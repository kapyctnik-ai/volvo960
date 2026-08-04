package com.volvo960.obdctl.prefs

import android.content.Context
import com.volvo960.obdctl.data.VehicleDataPoller

/** Small persisted settings that don't belong in the actuator registry. */
class AppPrefs(context: Context) : VehicleDataPoller.TripStore {
    private val prefs = context.applicationContext.getSharedPreferences("volvo960_prefs", Context.MODE_PRIVATE)

    var lastDeviceAddress: String?
        get() = prefs.getString(KEY_DEVICE_ADDRESS, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_ADDRESS, value).apply()

    /** Set once the radiator-fan entries have been inserted, so deleting them sticks. */
    var fanActuatorSeeded: Boolean
        get() = prefs.getBoolean(KEY_FAN_SEEDED, false)
        set(value) = prefs.edit().putBoolean(KEY_FAN_SEEDED, value).apply()

    /** Switch the fan on by itself once the coolant reaches [autoFanOnC]. */
    var autoFanEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_FAN, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_FAN, value).apply()

    var autoFanOnC: Int
        get() = prefs.getInt(KEY_AUTO_FAN_ON, 90)
        set(value) = prefs.edit().putInt(KEY_AUTO_FAN_ON, value).apply()

    override var tripKm: Double
        get() = Double.fromBits(prefs.getLong(KEY_TRIP_KM, 0L))
        set(value) = prefs.edit().putLong(KEY_TRIP_KM, value.toRawBits()).apply()

    /** Feeds the odometer window. The car's own odometer isn't readable, so this counts from install. */
    override var totalKm: Double
        get() = Double.fromBits(prefs.getLong(KEY_TOTAL_KM, 0L))
        set(value) = prefs.edit().putLong(KEY_TOTAL_KM, value.toRawBits()).apply()

    companion object {
        private const val KEY_DEVICE_ADDRESS = "last_device_address"
        // Bumped when the seeded fan cards change shape, so existing installs
        // get the corrected version instead of keeping the broken one.
        private const val KEY_FAN_SEEDED = "fan_actuator_seeded_v3"
        private const val KEY_AUTO_FAN = "auto_fan_enabled"
        private const val KEY_AUTO_FAN_ON = "auto_fan_on_c"
        private const val KEY_TRIP_KM = "trip_km"
        private const val KEY_TOTAL_KM = "total_km"
    }
}
