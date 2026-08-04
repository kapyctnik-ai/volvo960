package com.volvo960.obdctl.data

import android.os.SystemClock
import com.volvo960.obdctl.transport.ConnectionState
import com.volvo960.obdctl.transport.Elm327Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Reads the live values the dashboard shows, one request at a time through the
 * shared transport queue.
 *
 * Everything here is standard Mode 01, which the engine ECU answers. Readings
 * the car declines simply stay null rather than being faked — several sources
 * on this car are still unidentified, and a gauge showing an invented number
 * is worse than a gauge showing nothing.
 */
class VehicleDataPoller(
    private val transport: Elm327Transport,
    private val scope: CoroutineScope,
    private val prefs: TripStore,
) {
    interface TripStore {
        var tripKm: Double
    }

    private companion object {
        const val PID_COOLANT = "0105"
        const val PID_RPM = "010C"
        const val PID_SPEED = "010D"
        const val PID_FUEL = "012F"
        const val REQUEST_TIMEOUT_MS = 2_500L
        const val CYCLE_PAUSE_MS = 60L
        /** Stop counting trip distance once readings stop arriving. */
        const val SPEED_STALE_MS = 4_000L
    }

    private val _state = MutableStateFlow(VehicleState(tripKm = prefs.tripKm))
    val state: StateFlow<VehicleState> = _state.asStateFlow()

    private var job: Job? = null
    private var lastSpeedAtMs = 0L

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun resetTrip() {
        prefs.tripKm = 0.0
        _state.update { it.copy(tripKm = 0.0) }
    }

    private suspend fun loop() {
        while (true) {
            if (transport.connectionState.value !is ConnectionState.Connected) {
                _state.update { VehicleState(tripKm = it.tripKm) }
                lastSpeedAtMs = 0L
                delay(1_000)
                continue
            }
            readByte(PID_RPM, "0C", wantBytes = 2)?.let { (a, b) ->
                _state.update { it.copy(rpm = ((a * 256) + b) / 4) }
            }
            readByte(PID_SPEED, "0D", wantBytes = 1)?.let { (a, _) ->
                _state.update { it.copy(speedKmh = a) }
                accumulateTrip(a)
            }
            readByte(PID_COOLANT, "05", wantBytes = 1)?.let { (a, _) ->
                _state.update { it.copy(coolantTempC = a - 40) }
            }
            readByte(PID_FUEL, "2F", wantBytes = 1)?.let { (a, _) ->
                _state.update { it.copy(fuelLevelPercent = a * 100 / 255) }
            }
            delay(CYCLE_PAUSE_MS)
        }
    }

    /** Adds the distance covered since the previous speed sample. */
    private fun accumulateTrip(speedKmh: Int) {
        val now = SystemClock.elapsedRealtime()
        val previous = lastSpeedAtMs
        lastSpeedAtMs = now
        if (previous == 0L) return
        val elapsedMs = now - previous
        if (elapsedMs <= 0 || elapsedMs > SPEED_STALE_MS) return
        val km = speedKmh * (elapsedMs / 3_600_000.0)
        if (km <= 0.0) return
        val total = prefs.tripKm + km
        prefs.tripKm = total
        _state.update { it.copy(tripKm = total) }
    }

    /**
     * Sends [pid] and pulls the data bytes out of the `41 <pid> ...` reply.
     * Returns null whenever the car declined or the reply didn't parse.
     */
    private suspend fun readByte(pid: String, pidEcho: String, wantBytes: Int): Pair<Int, Int>? {
        val result = transport.sendRaw(pid, REQUEST_TIMEOUT_MS)
        if (result !is Elm327Transport.CommandResult.Success) return null
        val bytes = result.response.uppercase()
            .split(Regex("[^0-9A-F]+"))
            .filter { it.length == 2 }
        val at = bytes.indexOfFirst { it == "41" }
        if (at == -1 || at + 1 >= bytes.size || bytes[at + 1] != pidEcho) return null
        val first = bytes.getOrNull(at + 2)?.toIntOrNull(16) ?: return null
        if (wantBytes == 1) return first to 0
        val second = bytes.getOrNull(at + 3)?.toIntOrNull(16) ?: return null
        return first to second
    }
}
