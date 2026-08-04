package com.volvo960.obdctl.prefs

import android.content.Context

/** Small persisted settings that don't belong in the actuator registry. */
class AppPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("volvo960_prefs", Context.MODE_PRIVATE)

    var lastDeviceAddress: String?
        get() = prefs.getString(KEY_DEVICE_ADDRESS, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_ADDRESS, value).apply()

    companion object {
        private const val KEY_DEVICE_ADDRESS = "last_device_address"
    }
}
