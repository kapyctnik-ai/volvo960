package com.volvo960.obdctl.transport

/** Observable state of the link to the ELM327 dongle. */
sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val deviceName: String, val deviceAddress: String) : ConnectionState()

    /** A single attempt failed; another one is scheduled. */
    data class Failed(val reason: String) : ConnectionState()

    /**
     * Every retry is spent. Nothing else will be attempted — the app shuts
     * itself down from here rather than sitting on the radio all night.
     */
    data class GaveUp(val reason: String) : ConnectionState()
}
