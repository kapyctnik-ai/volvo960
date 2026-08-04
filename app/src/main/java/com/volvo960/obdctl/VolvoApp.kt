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
        vehicleData = VehicleDataPoller(transport, appScope, prefs)
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
     * The actuator test only lives as long as the diagnostic session, so the
     * session teardown is the off-script and the control frame is what the
     * hold loop repeats.
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
                        // One complete open-toggle-close session, which frees
                        // the bus again immediately — see KEEP_ALIVE_TICK.
                        initScript = toggleSession(frame),
                        command = KEEP_ALIVE_TICK,
                        // The ECU latches the output — it keeps running even
                        // with the key out — so switching off has to be an
                        // explicit toggle back, not just dropping the session.
                        offCommand = toggleSession(frame),
                        behavior = ActuatorBehavior.HOLD_REPEAT,
                        repeatIntervalMs = 2_000,
                        autoStopTimeoutMs = 5 * 60_000,
                        // The 5-baud slow init in the setup script takes a
                        // couple of seconds on its own.
                        responseTimeoutMs = 9_000,
                        notes = "Motronic M4.4, ECU 7A, KWP D3B0. Команда переключает выход, " +
                            "поэтому шлётся один раз при включении и один раз при выключении.",
                    )
                )
            }
            prefs.fanActuatorSeeded = true
        }
    }

    private companion object {
        /**
         * One self-contained toggle: open the manufacturer session, send the
         * control frame, close it and put the adapter back on generic OBD-II.
         *
         * Closing it straight away is deliberate. The ECU latches the output,
         * so the session isn't needed to keep the fan running — and leaving it
         * open would monopolise the bus, blocking the dashboard's readings for
         * as long as the fan is on.
         */
        fun toggleSession(frame: String): String =
            FAN_SESSION_OPEN + "\n" + frame + "\n" + FAN_SESSION_CLOSE

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
            ATSI
            ATSH 85 7A 13
        """.trimIndent()

        /**
         * `B0 <id> 32 03` drives one output; the ECU acknowledges with
         * `83 13 7A F0 <id> <id>`. Both frames below are decoded captures.
         *
         * The frame toggles rather than sets: resending it on a repeat
         * interval switched the fan on and straight back off again.
         */
        const val FAN_LOW_FRAME = "B00E 3203"
        const val FAN_HIGH_FRAME = "B01F 3203"

        /**
         * What the hold loop repeats once the output is already latched on.
         * Reading the adapter's voltage touches the ELM only, never the car,
         * so the hold keeps its timeout, notification and stop button without
         * disturbing anything — and the bus stays free for the dashboard.
         *
         * Trade-off: because this always answers, the no-response watchdog can
         * no longer tell that the ECU went away — only that the adapter did.
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
