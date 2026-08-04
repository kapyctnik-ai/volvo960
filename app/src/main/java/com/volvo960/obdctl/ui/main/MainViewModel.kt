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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as VolvoApp

    val connectionState: StateFlow<ConnectionState> = app.transport.connectionState
    val activeHolds: StateFlow<Map<Long, HoldStatus>> = app.holdManager.activeHolds
    val actuators = app.repository.observeAll()

    /** Live coolant temperature, read by the app-wide poller that also feeds the dashboard. */
    val coolantTempC = app.vehicleData.state.map { it.coolantTempC }

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
