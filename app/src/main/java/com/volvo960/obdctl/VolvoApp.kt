package com.volvo960.obdctl

import android.app.Application
import com.volvo960.obdctl.data.Actuator
import com.volvo960.obdctl.data.ActuatorBehavior
import com.volvo960.obdctl.data.ActuatorRepository
import com.volvo960.obdctl.data.AppDatabase
import com.volvo960.obdctl.data.VehicleDataPoller
import com.volvo960.obdctl.prefs.AppPrefs
import com.volvo960.obdctl.service.AutoFanController
import com.volvo960.obdctl.service.HoldManager
import com.volvo960.obdctl.service.NotificationHelper
import com.volvo960.obdctl.transport.CommandLogger
import com.volvo960.obdctl.transport.Elm327Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Holds the app-wide singletons. The transport and hold manager must outlive
 * any single Activity (a hold survives screen rotation and can keep running
 * with no UI visible at all) and even outlive [com.volvo960.obdctl.service.HoldService]
 * being bound, so they live here rather than in a ViewModel or the service.
 */
class VolvoApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var logger: CommandLogger
        private set
    lateinit var transport: Elm327Transport
        private set
    lateinit var repository: ActuatorRepository
        private set
    lateinit var prefs: AppPrefs
        private set
    lateinit var notificationHelper: NotificationHelper
        private set
    lateinit var holdManager: HoldManager
        private set
    lateinit var vehicleData: VehicleDataPoller
        private set
    lateinit var autoFan: AutoFanController
        private set

    override fun onCreate() {
        super.onCreate()
        logger = CommandLogger(this)
        transport = Elm327Transport(logger)
        repository = ActuatorRepository(AppDatabase.get(this).actuatorDao())
        prefs = AppPrefs(this)
        notificationHelper = NotificationHelper(this)
        holdManager = HoldManager(transport, appScope) { actuator, reason ->
            notificationHelper.postWatchdogAlert(actuator.id, actuator.name, reason)
        }
        vehicleData = VehicleDataPoller(transport, appScope, prefs) {
            holdManager.activeHolds.value.isNotEmpty()
        }
        vehicleData.start()
        autoFan = AutoFanController(repository, holdManager, prefs, appScope)
        autoFan.observe(vehicleData.state)
        seedRegistryIfEmpty()
    }

    /**
     * Installs the radiator-fan card. The sequence below is a decoded capture
     * of a tool already proven to work on this car, not a guess: the ECU
     * answers `83 13 7A F0 0E 0E` to the control frame, and `F0` is `B0 + 0x40`
     * — a positive response in KWP terms.
     *
     * The test lives exactly as long as the diagnostic session, so the frame
     * is sent once when the session opens and switching off is simply closing
     * it. Nothing is repeated onto the bus in between.
     */
    private fun seedRegistryIfEmpty() {
        if (prefs.fanActuatorSeeded) return
        appScope.launch {
            val existing = repository.observeAll().first()
            // Drop earlier generations of this card: the placeholder that had
            // no control bytes, and the version that repeated the toggle frame
            // as its tick and so switched the fan on and off in a loop.
            existing.filter { it.command.contains("ПУСТО") || it.command.contains("B0") }
                .forEach { repository.delete(it) }
            for ((name, frame) in listOf(
                "Вентилятор радиатора (Low)" to FAN_LOW_FRAME,
                "Вентилятор радиатора (High)" to FAN_HIGH_FRAME,
            )) {
                repository.save(
                    Actuator(
                        name = name,
                        // Open the session and fire the frame once. The session
                        // then stays open, and what holds the output on is the
                        // tester-present message armed with ATWM, which the
                        // adapter repeats on its own — the protocol's own
                        // mechanism for exactly this, and no bus traffic from us.
                        initScript = FAN_SESSION_OPEN + "\n" + frame,
                        // Adapter-only, so the hold keeps its timeout,
                        // notification and stop button without putting anything
                        // on the K-line.
                        command = KEEP_ALIVE_TICK,
                        offCommand = FAN_SESSION_CLOSE,
                        behavior = ActuatorBehavior.HOLD_REPEAT,
                        repeatIntervalMs = 2_000,
                        autoStopTimeoutMs = 5 * 60_000,
                        // The 5-baud slow init in the setup script takes a
                        // couple of seconds on its own.
                        responseTimeoutMs = 9_000,
                        notes = "Motronic M4.4, ECU 7A, KWP D3B0. Кадр шлётся один раз, " +
                            "выход держит tester-present по ATWM, пока сессия открыта.",
                    )
                )
            }
            prefs.fanActuatorSeeded = true
        }
    }

    private companion object {
        /** Volvo's own K-line protocol, engine ECU 7A, tester address 13. */
        val FAN_SESSION_OPEN = """
            ATL1
            ATS1
            ATSP 3
            ATH1
            ATAL
            ATKW0
            ATSR 13
            ATE0
            ATAT 1
            ATST 32
            ATPC
            ATIIA 7A
            ATWM 82 7A 13 A1
            ATSW 5A
            ATSI
            ATSH 85 7A 13
        """.trimIndent()

        /**
         * `B0 <id> 32 03` drives one output; the ECU acknowledges with
         * `83 13 7A F0 <id> <id>`. Both frames below are decoded captures.
         *
         * The frame is a momentary command, not a latch: sent once with the
         * session closed straight after, the fan ran well under a second;
         * resent on a two-second interval it audibly pulsed on and off.
         */
        const val FAN_LOW_FRAME = "B00E 3203"
        const val FAN_HIGH_FRAME = "B01F 3203"

        /**
         * Ticked while the output is already on. Reading the adapter's voltage
         * never reaches the car, so nothing is sent onto the K-line — the
         * session is held up by the wake-up message alone.
         */
        const val KEEP_ALIVE_TICK = "ATRV"

        /**
         * Ends the session and restores the generic OBD-II setup the dashboard
         * polling expects — without this the header stays pointed at the engine
         * ECU and ordinary Mode 01 requests come back malformed.
         */
        val FAN_SESSION_CLOSE = """
            ATSH 82 7A 13
            A0
            ATPC
            ATD
            ATE0
            ATL0
            ATH0
            ATSP 3
        """.trimIndent()
    }
}
