package com.volvo960.obdctl.data

import android.os.SystemClock
import com.volvo960.obdctl.transport.CommandLogger
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
import kotlin.math.max

/**
 * Reads the live values the tiles show, one request at a time through the
 * shared transport queue.
 *
 * ## Fuel consumption on ISO 9141-2
 *
 * There is no "litres burnt so far" reading in OBD-II, and no injector duty
 * cycle either — SAE J1979 has neither. What it does have is enough to compute
 * the flow, in three tiers, best first:
 *
 *  1. **PID 015E** — engine fuel rate, litres per hour, `(256A+B)/20`. The
 *     ECU's own number. Defined late in J1979, so a 1996 Motronic almost
 *     certainly lacks it; asked for anyway because it costs one request.
 *  2. **PID 0110** — mass air flow, grams per second, `(256A+B)/100`. Petrol
 *     burns at 14.7 g of air per gram of fuel and weighs about 745 g/l, so
 *     `l/h = MAF * 3600 / (14.7 * 745)`. Fuel trims (PIDs 0106/0107) correct
 *     for the mixture the ECU is actually commanding.
 *  3. **MAP + IAT + rpm** — speed density, for engines with no MAF sensor.
 *     The ideal gas law gives the air mass the engine swallows:
 *     `MAF = MAP * (rpm * displacement * VE / 120) / (0.287 * (IAT + 273))`,
 *     then tier 2's arithmetic. Volumetric efficiency is assumed, so this is
 *     an estimate — flagged as such on the tile.
 *
 * Litres per 100 km is then `l/h / km/h * 100`, and standing still it stays
 * null because that division is meaningless — the tile shows l/h instead.
 *
 * Which tier applies is settled by asking the car: PID `0100`/`0120`/`0140`
 * return bitmaps of what it supports. Nothing is assumed about the Volvo's
 * ECU beyond that.
 *
 * Polling deliberately never drops the connection. On ISO 9141 a request can
 * take seconds and this ECU ignores plenty of standard PIDs; treating that as
 * a broken link put the app in a connect/fail/reconnect loop.
 */
class VehicleDataPoller(
    private val transport: Elm327Transport,
    private val scope: CoroutineScope,
    private val prefs: TripStore,
    private val logger: CommandLogger,
) {
    /** Persisted counters. Kept out of this class so they survive a restart. */
    interface TripStore {
        var tripKm: Double
        var totalKm: Double
        var tripFuelL: Double
        var tankLiters: Double
    }

    private companion object {
        const val PID_SUPPORTED_01 = "0100"
        const val PID_SUPPORTED_21 = "0120"
        const val PID_SUPPORTED_41 = "0140"
        const val PID_ENGINE_LOAD = "0104"
        const val PID_COOLANT = "0105"
        const val PID_SHORT_TRIM = "0106"
        const val PID_LONG_TRIM = "0107"
        const val PID_MAP = "010B"
        const val PID_RPM = "010C"
        const val PID_SPEED = "010D"
        const val PID_IAT = "010F"
        const val PID_MAF = "0110"
        const val PID_THROTTLE = "0111"
        const val PID_FUEL_LEVEL = "012F"
        const val PID_FUEL_RATE = "015E"

        /**
         * Generous on purpose: the first request after a quiet spell performs
         * the 5-baud initialisation, which alone takes a couple of seconds.
         */
        const val REQUEST_TIMEOUT_MS = 8_000L
        const val CYCLE_PAUSE_MS = 120L
        /** Backs off after repeated silence instead of hammering the bus. */
        const val BACKOFF_AFTER_FAILURES = 3
        const val BACKOFF_MS = 5_000L
        /** Stop counting distance and fuel once readings stop arriving. */
        const val SAMPLE_STALE_MS = 6_000L
        /** Slow-moving readings don't need a slot in every cycle. */
        const val SLOW_EVERY = 10

        /** B6304S/B6304F: 2 922 cm³, the 960's straight six. */
        const val DISPLACEMENT_L = 2.922
        /** Assumed volumetric efficiency for the speed-density fallback. */
        const val ASSUMED_VE = 0.80
        const val STOICH_AFR = 14.7
        const val PETROL_G_PER_L = 745.0
        const val AIR_GAS_CONSTANT = 0.287
    }

    private val _state = MutableStateFlow(
        VehicleState(tripKm = prefs.tripKm, totalKm = prefs.totalKm, tripFuelL = prefs.tripFuelL, tankLiters = prefs.tankLiters)
    )
    val state: StateFlow<VehicleState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    /** Last polling complaint, kept for display; cleared by the next good read. */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var job: Job? = null
    private var lastSampleAtMs = 0L
    private var consecutiveFailures = 0
    private var cycle = 0

    /** Filled by [probeCapabilities]; null until the car has been asked. */
    private var supported: Set<Int>? = null
    private var shortTrim: Double? = null
    private var longTrim: Double? = null
    private var lastMapKpa: Int? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            // A parse slip must not be able to take the tiles out for the rest
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

    /** Zeroes the trip counter and its fuel tally; the odometer keeps counting. */
    fun resetTrip() {
        prefs.tripKm = 0.0
        prefs.tripFuelL = 0.0
        _state.update { it.copy(tripKm = 0.0, tripFuelL = 0.0, averageL100 = null, rangeKm = null) }
    }

    /** Hand-entered tank contents, litres. */
    fun setTankLiters(liters: Double) {
        val clamped = liters.coerceIn(0.0, TANK_CAPACITY_L)
        prefs.tankLiters = clamped
        _state.update { it.copy(tankLiters = clamped, rangeKm = rangeFor(clamped, it.averageL100)) }
    }

    fun addTankLiters(delta: Double) = setTankLiters(prefs.tankLiters + delta)

    private suspend fun loop() {
        while (true) {
            if (transport.connectionState.value !is ConnectionState.Connected) {
                _state.update {
                    VehicleState(
                        tripKm = it.tripKm,
                        totalKm = it.totalKm,
                        tripFuelL = it.tripFuelL,
                        tankLiters = it.tankLiters,
                        averageL100 = it.averageL100,
                        rangeKm = it.rangeKm,
                    )
                }
                lastSampleAtMs = 0L
                consecutiveFailures = 0
                supported = null
                delay(1_000)
                continue
            }

            if (supported == null) probeCapabilities()

            var readAnything = false
            // Coolant first: it is the one reading this car is known to answer,
            // so a working link shows up on the dashboard immediately even if
            // everything else is ignored.
            read(PID_COOLANT, 0x05, 1)?.let { (a, _) ->
                readAnything = true
                _state.update { it.copy(coolantTempC = a - 40) }
            }
            // Speed and rpm are blanked when the request fails rather than
            // kept: a stale speed would go on adding distance and fuel that
            // the car never covered, and a needle frozen at the last value is
            // a lie the driver can act on.
            val rpm = read(PID_RPM, 0x0C, 2)?.let { (a, b) -> ((a * 256) + b) / 4 }
            if (rpm != null) readAnything = true
            _state.update { it.copy(rpm = rpm) }

            val speed = read(PID_SPEED, 0x0D, 1)?.first
            if (speed != null) readAnything = true
            _state.update { it.copy(speedKmh = speed) }
            read(PID_ENGINE_LOAD, 0x04, 1)?.let { (a, _) ->
                readAnything = true
                _state.update { it.copy(engineLoadPercent = a * 100 / 255) }
            }
            read(PID_THROTTLE, 0x11, 1)?.let { (a, _) ->
                readAnything = true
                _state.update { it.copy(throttlePercent = a * 100 / 255) }
            }
            if (cycle % SLOW_EVERY == 0) {
                read(PID_FUEL_LEVEL, 0x2F, 1)?.let { (a, _) ->
                    readAnything = true
                    _state.update { it.copy(fuelLevelPercent = a * 100 / 255) }
                }
                read(PID_IAT, 0x0F, 1)?.let { (a, _) ->
                    readAnything = true
                    _state.update { it.copy(intakeTempC = a - 40) }
                }
                readTrims()
            }

            val fuel = readFuelRate()
            if (fuel != null) readAnything = true
            integrate(fuel)

            if (readAnything) {
                consecutiveFailures = 0
                _lastError.value = null
            } else {
                consecutiveFailures++
            }
            cycle++
            delay(if (consecutiveFailures >= BACKOFF_AFTER_FAILURES) BACKOFF_MS else CYCLE_PAUSE_MS)
        }
    }

    /**
     * Asks the car which PIDs it implements. Each `01 00 / 20 / 40` reply is a
     * 32-bit map where the top bit is the next PID up; bit 0 of each map says
     * whether the following map exists.
     *
     * A car that refuses to answer leaves [supported] as an empty set, which
     * means "ask for everything and let NO DATA sort it out" — better than
     * silently showing nothing.
     */
    private suspend fun probeCapabilities() {
        val found = mutableSetOf<Int>()
        var base = 0x00
        for (request in listOf(PID_SUPPORTED_01, PID_SUPPORTED_21, PID_SUPPORTED_41)) {
            val bytes = readBytes(request, base, 4) ?: break
            var bits = 0L
            bytes.forEach { bits = (bits shl 8) or it.toLong() }
            for (i in 0 until 32) {
                if ((bits shr (31 - i)) and 1L == 1L) found += base + i + 1
            }
            // Bit 0 of the map is "the next block exists".
            if (bits and 1L == 0L) break
            base += 0x20
        }
        supported = found
        if (found.isNotEmpty()) {
            _lastError.value = null
            // Goes to the traffic log rather than the status line: it is
            // diagnostic detail, not a complaint about anything.
            logger.logError("поддерживаемые PID: " + found.joinToString(" ") { "%02X".format(it) })
        }
    }

    private fun supports(pid: Int): Boolean {
        val known = supported ?: return true
        return known.isEmpty() || known.contains(pid)
    }

    private suspend fun readTrims() {
        shortTrim = read(PID_SHORT_TRIM, 0x06, 1)?.let { (a, _) -> (a - 128) * 100.0 / 128.0 }
        longTrim = read(PID_LONG_TRIM, 0x07, 1)?.let { (a, _) -> (a - 128) * 100.0 / 128.0 }
    }

    /** Litres per hour, by whichever tier the car supports. */
    private suspend fun readFuelRate(): Pair<Double, FuelSource>? {
        if (supports(0x5E)) {
            read(PID_FUEL_RATE, 0x5E, 2)?.let { (a, b) ->
                return ((a * 256 + b) / 20.0) to FuelSource.ECU_FUEL_RATE
            }
        }
        if (supports(0x10)) {
            read(PID_MAF, 0x10, 2)?.let { (a, b) ->
                val mafGs = (a * 256 + b) / 100.0
                return litresPerHour(mafGs) to FuelSource.MAF
            }
        }
        if (supports(0x0B)) {
            read(PID_MAP, 0x0B, 1)?.let { (a, _) -> lastMapKpa = a }
        }
        val map = lastMapKpa ?: return null
        val rpm = _state.value.rpm ?: return null
        val iat = _state.value.intakeTempC ?: 20
        if (rpm <= 0) return 0.0 to FuelSource.SPEED_DENSITY
        // Four-stroke: one intake stroke per two revolutions, so litres of air
        // per second is rpm/2 * displacement / 60.
        val litresPerSecond = rpm * DISPLACEMENT_L * ASSUMED_VE / 120.0
        val massGs = map * litresPerSecond / (AIR_GAS_CONSTANT * (iat + 273.15))
        return litresPerHour(massGs) to FuelSource.SPEED_DENSITY
    }

    /**
     * Air grams per second to fuel litres per hour, corrected by the trims the
     * ECU is applying — positive trim means it is adding fuel beyond
     * stoichiometric, and that fuel is burnt just the same.
     */
    private fun litresPerHour(mafGs: Double): Double {
        val trim = 1.0 + ((shortTrim ?: 0.0) + (longTrim ?: 0.0)) / 100.0
        val fuelGs = mafGs / STOICH_AFR * trim.coerceIn(0.5, 1.5)
        return max(0.0, fuelGs * 3600.0 / PETROL_G_PER_L)
    }

    /**
     * Advances distance, fuel burnt and the tank on the time since the previous
     * sample. One place does all of it so the numbers can never disagree about
     * how much time passed.
     */
    private fun integrate(fuel: Pair<Double, FuelSource>?) {
        val now = SystemClock.elapsedRealtime()
        val previous = lastSampleAtMs
        lastSampleAtMs = now
        val speed = _state.value.speedKmh
        val elapsedMs = now - previous

        val instant = fuel?.let { (lph, _) ->
            if (speed != null && speed > 3) lph / speed * 100.0 else null
        }

        if (previous == 0L || elapsedMs <= 0 || elapsedMs > SAMPLE_STALE_MS) {
            _state.update {
                it.copy(fuelRateLph = fuel?.first, consumptionL100 = instant, fuelSource = fuel?.second)
            }
            return
        }

        val hours = elapsedMs / 3_600_000.0
        val km = (speed ?: 0) * hours
        val litres = (fuel?.first ?: 0.0) * hours

        val tripKm = prefs.tripKm + km
        val totalKm = prefs.totalKm + km
        val tripFuel = prefs.tripFuelL + litres
        val tank = (prefs.tankLiters - litres).coerceAtLeast(0.0)
        prefs.tripKm = tripKm
        prefs.totalKm = totalKm
        prefs.tripFuelL = tripFuel
        prefs.tankLiters = tank

        val average = if (tripKm > 0.3 && tripFuel > 0.01) tripFuel / tripKm * 100.0 else null
        _state.update {
            it.copy(
                fuelRateLph = fuel?.first,
                consumptionL100 = instant,
                fuelSource = fuel?.second,
                tripKm = tripKm,
                totalKm = totalKm,
                tripFuelL = tripFuel,
                tankLiters = tank,
                averageL100 = average,
                rangeKm = rangeFor(tank, average),
            )
        }
    }

    private fun rangeFor(tankLiters: Double, averageL100: Double?): Int? {
        if (averageL100 == null || averageL100 <= 0.1) return null
        return (tankLiters / averageL100 * 100.0).toInt()
    }

    /**
     * Sends [pid] and pulls the data bytes out of the `41 <pid> ...` reply.
     * Returns null whenever the car declined or the reply didn't parse.
     */
    private suspend fun read(pid: String, pidEcho: Int, wantBytes: Int): Pair<Int, Int>? {
        if (!supports(pidEcho)) return null
        val bytes = readBytes(pid, pidEcho, wantBytes) ?: return null
        val first = bytes.getOrNull(0) ?: return null
        return first to (bytes.getOrNull(1) ?: 0)
    }

    private suspend fun readBytes(pid: String, pidEcho: Int, wantBytes: Int): List<Int>? {
        val result = transport.sendRaw(pid, REQUEST_TIMEOUT_MS, dropOnFailure = false)
        if (result !is Elm327Transport.CommandResult.Success) {
            (result as? Elm327Transport.CommandResult.Error)?.let { _lastError.value = it.message }
            return null
        }
        val bytes = hexBytes(result.response)
        val echo = "%02X".format(pidEcho)
        // Mode 01 answers with mode+0x40; find that, then the echoed PID.
        val at = bytes.indexOfFirst { it == "41" }
        if (at == -1 || at + 1 >= bytes.size || bytes[at + 1] != echo) return null
        val data = bytes.drop(at + 2).take(wantBytes).mapNotNull { it.toIntOrNull(16) }
        return if (data.size == wantBytes) data else null
    }

    private fun hexBytes(response: String): List<String> =
        response.uppercase().split(Regex("[^0-9A-F]+")).filter { it.length == 2 }
}

/** The 960's tank, litres. Fixed: the level sender is not trusted for volume. */
const val TANK_CAPACITY_L = 80.0
