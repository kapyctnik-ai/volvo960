package com.volvo960.obdctl.ui.main

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.volvo960.obdctl.VolvoApp
import com.volvo960.obdctl.data.Actuator
import com.volvo960.obdctl.service.HoldService
import com.volvo960.obdctl.service.HoldStatus
import com.volvo960.obdctl.transport.ConnectionState
import com.volvo960.obdctl.transport.Elm327Transport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val COOLANT_TEMP_PID = "0105"
        private const val COOLANT_POLL_INTERVAL_MS = 3_000L
    }

    private val app = application as VolvoApp

    val connectionState: StateFlow<ConnectionState> = app.transport.connectionState
    val activeHolds: StateFlow<Map<Long, HoldStatus>> = app.holdManager.activeHolds
    val actuators = app.repository.observeAll()

    private val _coolantTempC = MutableStateFlow<Int?>(null)
    /** Live engine coolant temperature in °C, from the standard OBD-II PID 0105. Null while not connected or not yet read. */
    val coolantTempC: StateFlow<Int?> = _coolantTempC.asStateFlow()

    init {
        // Standard Mode 01 PID 05 is part of the OBD-II spec (unlike actuator
        // control, which is manufacturer-specific), so it's safe to poll
        // automatically over the same shared transport queue as everything else.
        viewModelScope.launch {
            connectionState.collectLatest { state ->
                if (state is ConnectionState.Connected) {
                    pollCoolantTemp()
                } else {
                    _coolantTempC.value = null
                }
            }
        }
    }

    private suspend fun pollCoolantTemp() {
        while (true) {
            when (val result = app.transport.sendRaw(COOLANT_TEMP_PID, 3_000L)) {
                is Elm327Transport.CommandResult.Success -> parseCoolantTemp(result.response)?.let { _coolantTempC.value = it }
                is Elm327Transport.CommandResult.Error -> Unit
            }
            delay(COOLANT_POLL_INTERVAL_MS)
        }
    }

    /** Parses a "41 05 xx" Mode 01 PID 05 response; temperature is xx (hex) - 40 °C. */
    private fun parseCoolantTemp(response: String): Int? {
        val bytes = response.uppercase()
            .split(Regex("[\\s\r\n]+"))
            .filter { it.matches(Regex("[0-9A-F]{2}")) }
        val modeIndex = bytes.indexOf("41")
        if (modeIndex == -1 || modeIndex + 2 >= bytes.size) return null
        if (bytes[modeIndex + 1] != "05") return null
        val raw = bytes[modeIndex + 2].toIntOrNull(16) ?: return null
        return raw - 40
    }

    fun connect(device: BluetoothDevice) {
        app.prefs.lastDeviceAddress = device.address
        HoldService.start(app)
        app.transport.connect(device)
    }

    fun disconnect() {
        app.holdManager.stopAll()
        app.transport.disconnect()
        HoldService.stop(app)
    }

    fun isHeld(actuatorId: Long): Boolean = app.holdManager.isHeld(actuatorId)

    fun needsWarning(actuator: Actuator): Boolean = !actuator.warningAcknowledged

    fun startHold(actuator: Actuator) {
        HoldService.start(app)
        app.holdManager.start(actuator)
    }

    fun stopHold(actuatorId: Long) {
        app.holdManager.stop(actuatorId)
    }

    fun stopAll() {
        app.holdManager.stopAll()
    }

    fun sendOnce(actuator: Actuator) {
        viewModelScope.launch { app.holdManager.sendOnce(actuator) }
    }

    fun deleteActuator(actuator: Actuator) {
        if (app.holdManager.isHeld(actuator.id)) app.holdManager.stop(actuator.id)
        viewModelScope.launch { app.repository.delete(actuator) }
    }

    fun acknowledgeWarning(actuatorId: Long) {
        viewModelScope.launch { app.repository.acknowledgeWarning(actuatorId) }
    }
}
