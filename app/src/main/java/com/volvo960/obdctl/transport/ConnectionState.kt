package com.volvo960.obdctl.transport

/** Observable state of the link to the ELM327 dongle. */
sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val deviceName: String, val deviceAddress: String) : ConnectionState()
    data class Failed(val reason: String) : ConnectionState()
}
