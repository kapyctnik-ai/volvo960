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
    private val gears: GearEstimator,
) {
    /** Persisted counters. Kept out of this class so they survive a restart. */
    interface TripStore {
        var tripKm: Double
        var totalKm: Double
        var tripFuelL: Double
        var totalFuelL: Double
        var tankLiters: Double
    }

    /** Forgets the learnt gear ratios — after a tyre or gearbox change. */
    fun resetGears() = gears.reset()

    /** What the gear estimator has learnt, for the diagnostic dialog. */
    fun describeGears(): List<String> = gears.describe()

    /**
     * Wipes every conclusion the poller has drawn, for when the user demands a
     * clean start. Nothing learnt about a link that is being thrown away should
     * get a vote in what happens next.
     */
    fun hardReset() {
        datalessResets = 0
        lastLinkResetAtMs = 0L
        resetForNewConnection()
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
        const val PID_FUEL_RATE = "015E"

        /**
         * Generous on purpose: the first request after a quiet spell performs
         * the 5-baud initialisation, which alone takes a couple of seconds.
         */
        const val REQUEST_TIMEOUT_MS = 8_000L
        /**
         * Once anything has answered, the slow init is behind us and a request
         * that takes five seconds is not going to arrive. Waiting the full
         * eight for each of ten PIDs is most of a minute of radio per cycle.
         */
        const val REQUEST_TIMEOUT_WARM_MS = 4_000L
        /** Just enough to let the K-line settle between passes. */
        const val CYCLE_PAUSE_MS = 60L
        /**
         * With the screen off or the app in the background there is nobody to
         * see a needle move. Trip distance and fuel are integrated from the
         * samples, and a pass a second is plenty for that.
         */
        const val BACKGROUND_PAUSE_MS = 1_200L
        /** Backs off after repeated silence instead of hammering the bus. */
        const val BACKOFF_AFTER_FAILURES = 3
        const val BACKOFF_MS = 5_000L
        /** A car that has said nothing for a while is off; stop chasing it. */
        const val DEEP_BACKOFF_AFTER_FAILURES = 10
        const val DEEP_BACKOFF_MS = 30_000L
        /** Readings older than this are not shown; a frozen gauge reads as a live one. */
        const val STALE_MS = 10_000L
        /**
         * Before giving up, put the adapter through a reset: it can sit there
         * answering while holding a session the car has long since dropped, and
         * only ATZ plus a fresh bus initialisation clears that.
         */
        const val RESET_LINK_WITHOUT_DATA_MS = 60_000L
        /** Rebuilds of the link, each given a minute, before calling it a day. */
        const val MAX_DATALESS_RESETS = 5
        /** How many times to check whether the adapter understands the reply-count digit. */
        const val MAX_HINT_PROBES = 3
        /** A PID that never answers is dropped after this many silent tries. */
        const val MUTE_AFTER_SILENT = 3
        /** ...and retried this often, in case it only sleeps with the engine off. */
        const val RETRY_MUTED_EVERY = 40
        /** Stop counting distance and fuel once readings stop arriving. */
        const val SAMPLE_STALE_MS = 6_000L

        /** B6304S/B6304F: 2 922 cm³, the 960's straight six. */
        const val DISPLACEMENT_L = 2.922
        /** Assumed volumetric efficiency for the speed-density fallback. */
        const val ASSUMED_VE = 0.80
        const val STOICH_AFR = 14.7
        const val PETROL_G_PER_L = 745.0
        const val AIR_GAS_CONSTANT = 0.287

        /**
         * How far above the calibrated closed position still counts as shut, %.
         * The sensor sits at 7 % released on this car, so the cut-off holds up
         * to 9 % and the smallest real press cancels it.
         */
        const val THROTTLE_CLOSED_MARGIN = 2
        /** Above this the ECU cuts fuel on a closed throttle... */
        const val RPM_FUEL_CUT = 1_300
        /** ...and below this it puts it back, so the engine keeps running. */
        const val RPM_FUEL_RESUME = 1_100
    }

    private val _state = MutableStateFlow(
        VehicleState(
            tripKm = prefs.tripKm,
            totalKm = prefs.totalKm,
            tripFuelL = prefs.tripFuelL,
            tankLiters = prefs.tankLiters,
            // The averages are derived from persisted counters, so they are
            // known before the car has said a word.
            averageL100 = averageOf(prefs.tripFuelL, prefs.tripKm),
            averageAllL100 = averageOf(prefs.totalFuelL, prefs.totalKm),
        ).let { it.copy(rangeKm = rangeFor(it.tankLiters, it.averageL100)) }
    )
    val state: StateFlow<VehicleState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    /** Last polling complaint, kept for display; cleared by the next good read. */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var job: Job? = null
    /** Set while the dashboard is not on screen; slows the cycle right down. */
    @Volatile var lowPower = false

    private var lastSampleAtMs = 0L
    /** When a PID last parsed successfully — the only proof the car is awake. */
    private var lastDataAtMs = 0L

    /**
     * How many times the link has been rebuilt without a single reading coming
     * back. This, rather than a wall clock, is what decides to give up.
     *
     * A clock that survived reconnects looked right and was badly wrong: after
     * a long silence it was already expired, so the first failed cycle of a
     * fresh connection gave up instantly — every attempt died before the bus
     * had even been initialised, and the app sat there having "given up" while
     * a perfectly good adapter waited.
     */
    private var datalessResets = 0
    private var consecutiveFailures = 0
    private var cycle = 0
    private var seenGeneration = -1
    private var lastLinkResetAtMs = 0L

    /** Filled by [probeCapabilities]; null until the car has been asked. */
    private var supported: Set<Int>? = null
    private val silentTries = HashMap<String, Int>()
    private var everAnswered = false
    private var shortTrim: Double? = null
    private var longTrim: Double? = null
    private var lastMapKpa: Int? = null
    private var slowCursor = 0
    private var rpmMisses = 0
    private var speedMisses = 0
    /** Lowest throttle reading seen this session — the sensor's closed position. */
    private var closedThrottlePercent = 100
    private var overrunActive = false
    private var responseHint = true
    private var hintProbes = 0
    /** Which consumption tier answered; set once, so the others stop being asked. */
    private var lockedSource: FuelSource? = null

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
            // A reconnect starts from nothing: the adapter has been reset, the
            // bus needs its slow initialisation again, and everything learnt
            // about what answers belongs to the link that just died. Watching
            // for a Disconnected state in between is not enough — a drop and a
            // reconnect both fit inside one slow request.
            if (transport.connectionGeneration != seenGeneration) {
                seenGeneration = transport.connectionGeneration
                resetForNewConnection()
            }

            if (transport.connectionState.value !is ConnectionState.Connected) {
                _state.update {
                    VehicleState(
                        tripKm = it.tripKm,
                        totalKm = it.totalKm,
                        tripFuelL = it.tripFuelL,
                        tankLiters = it.tankLiters,
                        averageL100 = it.averageL100,
                        averageAllL100 = it.averageAllL100,
                        rangeKm = it.rangeKm,
                    )
                }
                lastSampleAtMs = 0L
                lastDataAtMs = 0L
                consecutiveFailures = 0
                supported = null
                silentTries.clear()
                everAnswered = false
                lockedSource = null
                responseHint = true
                hintProbes = 0
                closedThrottlePercent = 100
                overrunActive = false
                delay(1_000)
                continue
            }

            // Four fast readings plus one slow one per pass, and nothing else.
            // ISO 9141-2 runs at 10.4 kbaud with a request-response round trip
            // of roughly a tenth of a second, so every request in the pass is
            // paid for in needle updates. These four earn their place: two are
            // the needles themselves, and the other two are what the
            // consumption figure is made of.
            var readAnything = false

            // Speed and rpm are blanked when requests fail rather than kept:
            // a stale speed would go on adding distance and fuel the car never
            // covered, and a needle frozen at the last value is a lie the
            // driver can act on. One miss is forgiven, though — the K-line
            // drops the odd reply, and a needle that falls to zero and climbs
            // back on every dropped reply is its own kind of lie.
            val rpmRead = read(PID_RPM, 0x0C, 2)?.let { (a, b) -> ((a * 256) + b) / 4 }
            rpmMisses = if (rpmRead == null) rpmMisses + 1 else 0
            val rpm = rpmRead ?: if (rpmMisses <= 1) _state.value.rpm else null
            if (rpmRead != null) readAnything = true
            _state.update { it.copy(rpm = rpm) }

            val speedRead = read(PID_SPEED, 0x0D, 1)?.first
            speedMisses = if (speedRead == null) speedMisses + 1 else 0
            val speed = speedRead ?: if (speedMisses <= 1) _state.value.speedKmh else null
            if (speedRead != null) readAnything = true
            val gear = gears.update(rpm, speed)
            _state.update { it.copy(speedKmh = speed, gear = gear) }

            // Throttle is in the fast pass because the fuel calculation needs
            // it: a closed throttle is half of what proves the injectors are
            // shut. Left in the slow rotation it would miss whole coasts.
            val throttle = read(PID_THROTTLE, 0x11, 1)?.let { (a, _) -> a * 100 / 255 }
            if (throttle != null) {
                readAnything = true
                // The sensor reads about 7 % released; a zero is a glitch and
                // would make the cut-off unreachable for the rest of the drive.
                if (throttle in 1 until closedThrottlePercent) closedThrottlePercent = throttle
            }
            _state.update { it.copy(throttlePercent = throttle) }

            val fuel = readFuelRate()
            if (fuel != null) readAnything = true
            integrate(fuel)

            if (readSlowOne()) readAnything = true

            if (readAnything) {
                consecutiveFailures = 0
                _lastError.value = null
            } else {
                consecutiveFailures++
                enforceStaleness()
            }

            // Asked once, after real readings have had their turn: three
            // unanswered bitmap requests at the head of the first cycle is
            // half a minute of blank screen before anything is even tried.
            if (supported == null && cycle >= 1) probeCapabilities()
            cycle++
            delay(
                when {
                    consecutiveFailures >= DEEP_BACKOFF_AFTER_FAILURES -> DEEP_BACKOFF_MS
                    consecutiveFailures >= BACKOFF_AFTER_FAILURES -> BACKOFF_MS
                    lowPower -> BACKGROUND_PAUSE_MS
                    else -> CYCLE_PAUSE_MS
                }
            )
        }
    }

    /**
     * Forgets everything that was true of the previous link: which PIDs went
     * silent, whether the adapter understands the reply-count digit, which
     * consumption tier answered, and — importantly — that anything had ever
     * answered at all, which is what keeps the first request after a
     * reconnection on the long timeout the 5-baud initialisation needs.
     */
    private fun resetForNewConnection() {
        silentTries.clear()
        everAnswered = false
        responseHint = true
        hintProbes = 0
        lockedSource = null
        supported = null
        consecutiveFailures = 0
        lastSampleAtMs = 0L
        lastDataAtMs = 0L
        cycle = 0
        lastLinkResetAtMs = 0L
    }

    /**
     * Blanks the live readings once they stop arriving, and stops the app when
     * they have not arrived for long enough that nobody is driving anything.
     *
     * A gauge holding its last value looks exactly like a gauge being fed, and
     * the notification said "связь есть · ОЖ 106" for twenty-five minutes after
     * the car had been walked away from. Showing nothing is the honest answer.
     */
    private suspend fun enforceStaleness() {
        if (lastDataAtMs == 0L) {
            // Never had a reading on this connection; start the clock at the
            // first failure so the give-up timer has something to measure from.
            lastDataAtMs = SystemClock.elapsedRealtime()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val silentFor = now - lastDataAtMs
        if (silentFor >= RESET_LINK_WITHOUT_DATA_MS &&
            now - lastLinkResetAtMs >= RESET_LINK_WITHOUT_DATA_MS
        ) {
            lastLinkResetAtMs = now
            datalessResets++
            if (datalessResets >= MAX_DATALESS_RESETS) {
                // Several full rebuilds of the link, each given a minute, and
                // not one reading. Nobody is driving this.
                transport.abandon("машина не отвечает, $datalessResets перезапуска подряд")
            } else {
                transport.resetLink("нет данных ${silentFor / 1000} с")
            }
            return
        }
        if (silentFor < STALE_MS) return
        _state.update {
            it.copy(
                speedKmh = null,
                rpm = null,
                coolantTempC = null,
                intakeTempC = null,
                engineLoadPercent = null,
                throttlePercent = null,
                gear = null,
                fuelRateLph = null,
                consumptionL100 = null,
            )
        }
    }

    /**
     * One background reading per pass, round robin. None of these move fast
     * enough to be worth a slot in every cycle, and together they used to cost
     * more than the readings that do.
     */
    private suspend fun readSlowOne(): Boolean {
        val slot = slowCursor % 5
        slowCursor++
        return when (slot) {
            // Coolant sits here rather than in the fast pass: it is a
            // temperature, it moves in minutes, and every request it takes is a
            // request the needles do not get.
            0 -> read(PID_COOLANT, 0x05, 1)?.let { (a, _) ->
                _state.update { it.copy(coolantTempC = a - 40) }
            } != null
            1 -> read(PID_ENGINE_LOAD, 0x04, 1)?.let { (a, _) ->
                _state.update { it.copy(engineLoadPercent = a * 100 / 255) }
            } != null
            2 -> read(PID_IAT, 0x0F, 1)?.let { (a, _) ->
                _state.update { it.copy(intakeTempC = a - 40) }
            } != null
            3 -> read(PID_SHORT_TRIM, 0x06, 1)?.let { (a, _) ->
                shortTrim = (a - 128) * 100.0 / 128.0
            } != null
            else -> read(PID_LONG_TRIM, 0x07, 1)?.let { (a, _) ->
                longTrim = (a - 128) * 100.0 / 128.0
            } != null
        }
    }

    /**
     * Asks the car which PIDs it implements. Each `01 00 / 20 / 40` reply is a
     * 32-bit map where the top bit is the next PID up; bit 0 of each map says
     * whether the following map exists.
     *
     * The result is diagnostic only — it is logged, never used to decide what
     * to ask for. Bitmaps on old ECUs under-report, and a reading that is not
     * requested because a bitmap denied it is a reading lost for nothing.
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

    /** Litres per hour, by whichever tier the car supports. */
    private suspend fun readFuelRate(): Pair<Double, FuelSource>? {
        val rate = measuredFuelRate()
        // The ECU's own figure already knows about the cut-off; only the
        // air-derived tiers need correcting.
        if (rate != null && rate.second != FuelSource.ECU_FUEL_RATE) {
            overrunActive = onOverrun()
            if (overrunActive) return 0.0 to rate.second
        } else {
            overrunActive = false
        }
        return rate
    }

    private suspend fun measuredFuelRate(): Pair<Double, FuelSource>? {
        // Tried by what actually answers, not by what the bitmap claims: an
        // ECU whose bitmap under-reports would otherwise never be asked. Once
        // a tier answers it is locked in — retrying the better tiers on every
        // pass would spend a request each on PIDs already known to be silent.
        if (lockedSource == null || lockedSource == FuelSource.ECU_FUEL_RATE) {
            read(PID_FUEL_RATE, 0x5E, 2)?.let { (a, b) ->
                lockedSource = FuelSource.ECU_FUEL_RATE
                return ((a * 256 + b) / 20.0) to FuelSource.ECU_FUEL_RATE
            }
            // A locked tier that misses once is a miss, not a reason to drop
            // to a worse tier: falling through here used to switch a working
            // MAF car onto the MAP estimate for good after one ignored request.
            if (lockedSource == FuelSource.ECU_FUEL_RATE) return null
        }
        if (lockedSource == null || lockedSource == FuelSource.MAF) {
            read(PID_MAF, 0x10, 2)?.let { (a, b) ->
                lockedSource = FuelSource.MAF
                val mafGs = (a * 256 + b) / 100.0
                return litresPerHour(mafGs) to FuelSource.MAF
            }
            if (lockedSource == FuelSource.MAF) return null
        }
        read(PID_MAP, 0x0B, 1)?.let { (a, _) ->
            lastMapKpa = a
            lockedSource = FuelSource.SPEED_DENSITY
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
     * True while the engine is being driven by the car rather than driving it,
     * with the injectors shut — coasting in gear off the throttle.
     *
     * This matters because every tier of the consumption calculation measures
     * *air*, and on the overrun the engine pumps plenty of air while burning
     * nothing at all: Motronic cuts the injectors entirely. Without this the
     * dashboard reads three-odd litres an hour rolling down a hill with the
     * throttle shut, which is exactly backwards — that is the one moment the
     * car uses none.
     *
     * Three things have to hold at once, and all three are needed:
     *  - the throttle is shut (idle position, calibrated from the lowest value
     *    seen this session — the sensor reads 12-16 % closed on this car);
     *  - engine speed is above the cut-off's re-enable point, since below it
     *    the ECU turns the injectors back on to keep the engine alive;
     *  - the car is actually moving, otherwise this is just idling in neutral,
     *    where the injectors are very much working.
     */
    private fun onOverrun(): Boolean {
        val throttle = _state.value.throttlePercent ?: return false
        val rpm = _state.value.rpm ?: return false
        val speed = _state.value.speedKmh ?: return false
        if (speed < 5) return false
        if (throttle > closedThrottlePercent + THROTTLE_CLOSED_MARGIN) return false
        // Hysteresis: fuel comes back before the engine reaches idle, so the
        // reading must not flicker between zero and a figure while the revs
        // fall through the threshold.
        val threshold = if (overrunActive) RPM_FUEL_RESUME else RPM_FUEL_CUT
        return rpm >= threshold
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
        val totalFuel = prefs.totalFuelL + litres
        val tank = (prefs.tankLiters - litres).coerceAtLeast(0.0)
        prefs.tripKm = tripKm
        prefs.totalKm = totalKm
        prefs.tripFuelL = tripFuel
        prefs.totalFuelL = totalFuel
        prefs.tankLiters = tank

        val average = averageOf(tripFuel, tripKm)
        val averageAll = averageOf(totalFuel, totalKm)
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
                averageAllL100 = averageAll,
                rangeKm = rangeFor(tank, average),
            )
        }
    }

    private fun averageOf(litres: Double, km: Double): Double? =
        if (km > 0.3 && litres > 0.01) litres / km * 100.0 else null

    private fun rangeFor(tankLiters: Double, averageL100: Double?): Int? {
        if (averageL100 == null || averageL100 <= 0.1) return null
        return (tankLiters / averageL100 * 100.0).toInt()
    }

    /**
     * Sends [pid] and pulls the data bytes out of the `41 <pid> ...` reply.
     * Returns null whenever the car declined or the reply didn't parse.
     */
    private suspend fun read(pid: String, pidEcho: Int, wantBytes: Int): Pair<Int, Int>? {
        if (isMuted(pid)) return null
        val bytes = readBytes(pid, pidEcho, wantBytes)
        if (bytes == null) {
            silentTries[pid] = (silentTries[pid] ?: 0) + 1
            return null
        }
        silentTries.remove(pid)
        everAnswered = true
        lastDataAtMs = SystemClock.elapsedRealtime()
        datalessResets = 0
        val first = bytes.getOrNull(0) ?: return null
        return first to (bytes.getOrNull(1) ?: 0)
    }

    /**
     * A PID this ECU ignores costs a full timeout every cycle, and this car
     * ignores plenty of them. After a few silent tries it is dropped, and only
     * retried occasionally — some readings genuinely only appear with the
     * engine running.
     */
    private fun isMuted(pid: String): Boolean {
        val silent = silentTries[pid] ?: return false
        if (silent < MUTE_AFTER_SILENT) return false
        return cycle % RETRY_MUTED_EVERY != 0
    }

    /**
     * A trailing digit on the request tells the ELM327 how many replies to
     * expect. Without it the adapter sits out its full timeout after the answer
     * in case a second ECU speaks, which on this single-ECU car is dead time on
     * every single request — the largest cost in the whole poll cycle.
     *
     * Older clones do not understand the digit, so the first few failures are
     * retried plain; if the plain form works the hint is dropped for good.
     */
    private suspend fun readBytes(pid: String, pidEcho: Int, wantBytes: Int): List<Int>? {
        val hinted = exchange(if (responseHint) pid + "1" else pid, pidEcho, wantBytes)
        if (hinted != null) return hinted
        if (responseHint && hintProbes < MAX_HINT_PROBES) {
            hintProbes++
            val plain = exchange(pid, pidEcho, wantBytes)
            if (plain != null) {
                responseHint = false
                logger.logError("адаптер не понял счётчик ответов, отключил его")
                return plain
            }
        }
        return null
    }

    private suspend fun exchange(pid: String, pidEcho: Int, wantBytes: Int): List<Int>? {
        val timeout = if (everAnswered) REQUEST_TIMEOUT_WARM_MS else REQUEST_TIMEOUT_MS
        val result = transport.sendRaw(pid, timeout, dropOnFailure = false)
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
