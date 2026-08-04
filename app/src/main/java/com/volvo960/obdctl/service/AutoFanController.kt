package com.volvo960.obdctl.service

import com.volvo960.obdctl.data.ActuatorRepository
import com.volvo960.obdctl.data.VehicleState
import com.volvo960.obdctl.prefs.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Switches the radiator fan on once the coolant passes the configured
 * threshold and off again after it has dropped clear of it.
 *
 * The gap between the two thresholds is deliberate: without it, a reading
 * hovering on the limit would switch a physical relay on and off every poll.
 */
class AutoFanController(
    private val repository: ActuatorRepository,
    private val holdManager: HoldManager,
    private val prefs: AppPrefs,
    private val scope: CoroutineScope,
) {
    private companion object {
        /** How far below the on-threshold the coolant must fall to switch off. */
        const val HYSTERESIS_C = 6
        const val FAN_ACTUATOR_MARKER = "B01F 3203"
    }

    @Volatile private var engaged = false

    fun observe(state: StateFlow<VehicleState>) {
        scope.launch {
            state.collect { vehicle ->
                val temp = vehicle.coolantTempC ?: return@collect
                if (!prefs.autoFanEnabled) {
                    if (engaged) release()
                    return@collect
                }
                val onAt = prefs.autoFanOnC
                if (!engaged && temp >= onAt) engage()
                if (engaged && temp <= onAt - HYSTERESIS_C) release()
            }
        }
    }

    private suspend fun engage() {
        val fan = fanActuator() ?: return
        engaged = true
        holdManager.start(fan)
    }

    private suspend fun release() {
        val fan = fanActuator() ?: return
        engaged = false
        holdManager.stop(fan.id)
    }

    /** The high-speed fan card, matched by its control frame rather than its name. */
    private suspend fun fanActuator() =
        repository.observeAll().first().firstOrNull { it.initScript.contains(FAN_ACTUATOR_MARKER) }
}
