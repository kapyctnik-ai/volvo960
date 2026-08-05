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
 * the car declines stay null rather than being faked — several sources on this
 * car are still unidentified, and a gauge showing an invented number is worse
 * than a gauge showing nothing.
 *
 * Polling deliberately never drops the connection. On ISO 9141 a request can
 * take seconds, and this ECU ignores plenty of standard PIDs; treating that as
 * a broken link put the app in a connect/fail/reconnect loop.
 */
class VehicleDataPoller(
    private val transport: Elm327Transport,
    private val scope: CoroutineScope,
    private val prefs: TripStore,
    /**
     * True while an actuator holds a manufacturer session open. Generic Mode 01
     * requests sent into such a session break it, so polling stands down.
     */
    private val sessionBusy: () -> Boolean = { false },
) {
    interface TripStore {
        var tripKm: Double
        var totalKm: Double
    }

    private companion object {
        const val PID_SUPPORTED = "0100"
        const val PID_COOLANT = "0105"
        const val PID_RPM = "010C"
        const val PID_SPEED = "010D"
        const val PID_FUEL = "012F"

        /**
         * Generous on purpose: the first request after a quiet spell performs
         * the 5-baud initialisation, which alone takes a couple of seconds.
         */
        const val REQUEST_TIMEOUT_MS = 8_000L
        const val CYCLE_PAUSE_MS = 120L
        /** Backs off after repeated silence instead of hammering the bus. */
        const val BACKOFF_AFTER_FAILURES = 3
        const val BACKOFF_MS = 5_000L
        /** Stop counting trip distance once readings stop arriving. */
        const val SPEED_STALE_MS = 6_000L
    }

    private val _state = MutableStateFlow(VehicleState(tripKm = prefs.tripKm, totalKm = prefs.totalKm))
    val state: StateFlow<VehicleState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    /** Last polling complaint, kept for display; cleared by the next good read. */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var job: Job? = null
    private var lastSpeedAtMs = 0L
    private var consecutiveFailures = 0

    /** PIDs the ECU said it implements. Null until asked, empty if it wouldn't say. */
    private var supported: Set<String>? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            // A parse slip must not be able to take the gauges out for the rest
            // of the session: keep looping and surface it instead.
            while (true) {
                try {
                    loop()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _lastError.value = "опрос: ${e.javaClass.simpleName} ${e.message.orEmpty()}"
                    delay(2_000)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Zeroes the trip counter only; the odometer window keeps counting. */
    fun resetTrip() {
        prefs.tripKm = 0.0
        _state.update { it.copy(tripKm = 0.0) }
    }

    private suspend fun loop() {
        while (true) {
            if (transport.connectionState.value !is ConnectionState.Connected) {
                _state.update { VehicleState(tripKm = it.tripKm, totalKm = it.totalKm) }
                lastSpeedAtMs = 0L
                supported = null
                consecutiveFailures = 0
                delay(1_000)
                continue
            }

            if (sessionBusy()) {
                lastSpeedAtMs = 0L
                delay(500)
                continue
            }

            if (supported == null) supported = probeSupportedPids()

            var readAnything = false
            if (wanted(PID_RPM)) {
                read(PID_RPM, "0C", 2)?.let { (a, b) ->
                    readAnything = true
                    _state.update { it.copy(rpm = ((a * 256) + b) / 4) }
                }
            }
            if (wanted(PID_SPEED)) {
                read(PID_SPEED, "0D", 1)?.let { (a, _) ->
                    readAnything = true
                    _state.update { it.copy(speedKmh = a) }
                    accumulateTrip(a)
                }
            }
            if (wanted(PID_COOLANT)) {
                read(PID_COOLANT, "05", 1)?.let { (a, _) ->
                    readAnything = true
                    _state.update { it.copy(coolantTempC = a - 40) }
                }
            }
            if (wanted(PID_FUEL)) {
                read(PID_FUEL, "2F", 1)?.let { (a, _) ->
                    readAnything = true
                    _state.update { it.copy(fuelLevelPercent = a * 100 / 255) }
                }
            }

            if (readAnything) {
                consecutiveFailures = 0
                _lastError.value = null
            } else {
                consecutiveFailures++
            }
            delay(if (consecutiveFailures >= BACKOFF_AFTER_FAILURES) BACKOFF_MS else CYCLE_PAUSE_MS)
        }
    }

    /**
     * Mode 01 PID 00 returns a bitmap of which PIDs 01–20 the ECU implements.
     * Asking once saves repeatedly requesting readings this car never answers,
     * each of which costs a full timeout.
     */
    private suspend fun probeSupportedPids(): Set<String> {
        val result = transport.sendRaw(PID_SUPPORTED, REQUEST_TIMEOUT_MS, dropOnFailure = false)
        if (result !is Elm327Transport.CommandResult.Success) return emptySet()
        val bytes = hexBytes(result.response)
        val at = bytes.indexOfFirst { it == "41" }
        // Bounds first: indexing before checking threw, and the exception killed
        // the polling coroutine outright, so every gauge stayed blank forever.
        if (at == -1 || bytes.size < at + 6 || bytes[at + 1] != "00") return emptySet()
        val mask = (2..5).fold(0L) { acc, i ->
            (acc shl 8) or (bytes.getOrNull(at + i)?.toLongOrNull(16) ?: return emptySet())
        }
        // Bit 31 is PID 01, bit 0 is PID 20.
        return (1..32).mapNotNull { pid ->
            if ((mask shr (32 - pid)) and 1L == 1L) "01%02X".format(pid) else null
        }.toSet()
    }

    /** Unknown support is treated as "try it": better a slow read than a blank gauge. */
    private fun wanted(pid: String): Boolean {
        val s = supported ?: return true
        return s.isEmpty() || pid in s
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
        val trip = prefs.tripKm + km
        val total = prefs.totalKm + km
        prefs.tripKm = trip
        prefs.totalKm = total
        _state.update { it.copy(tripKm = trip, totalKm = total) }
    }

    /**
     * Sends [pid] and pulls the data bytes out of the `41 <pid> ...` reply.
     * Returns null whenever the car declined or the reply didn't parse.
     */
    private suspend fun read(pid: String, pidEcho: String, wantBytes: Int): Pair<Int, Int>? {
        val result = transport.sendRaw(pid, REQUEST_TIMEOUT_MS, dropOnFailure = false)
        if (result !is Elm327Transport.CommandResult.Success) {
            (result as? Elm327Transport.CommandResult.Error)?.let { _lastError.value = it.message }
            return null
        }
        val bytes = hexBytes(result.response)
        val at = bytes.indexOfFirst { it == "41" }
        if (at == -1 || at + 1 >= bytes.size || bytes[at + 1] != pidEcho) return null
        val first = bytes.getOrNull(at + 2)?.toIntOrNull(16) ?: return null
        if (wantBytes == 1) return first to 0
        val second = bytes.getOrNull(at + 3)?.toIntOrNull(16) ?: return null
        return first to second
    }

    private fun hexBytes(response: String): List<String> =
        response.uppercase().split(Regex("[^0-9A-F]+")).filter { it.length == 2 }
}
