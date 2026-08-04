package com.volvo960.obdctl

import android.app.Application
import com.volvo960.obdctl.data.Actuator
import com.volvo960.obdctl.data.ActuatorBehavior
import com.volvo960.obdctl.data.ActuatorRepository
import com.volvo960.obdctl.data.AppDatabase
import com.volvo960.obdctl.prefs.AppPrefs
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
            // Drop the earlier placeholder card, which carried no control bytes.
            existing.filter { it.command.contains("ПУСТО") }.forEach { repository.delete(it) }
            for ((name, frame) in listOf(
                "Вентилятор радиатора (Low)" to FAN_LOW_FRAME,
                "Вентилятор радиатора (High)" to FAN_HIGH_FRAME,
            )) {
                if (existing.any { it.command.contains(frame) }) continue
                repository.save(
                    Actuator(
                        name = name,
                        initScript = FAN_INIT_SCRIPT,
                        command = frame,
                        offCommand = FAN_OFF_SCRIPT,
                        behavior = ActuatorBehavior.HOLD_REPEAT,
                        repeatIntervalMs = 2_000,
                        autoStopTimeoutMs = 5 * 60_000,
                        // The 5-baud slow init in the setup script takes a
                        // couple of seconds on its own.
                        responseTimeoutMs = 9_000,
                        notes = "Motronic M4.4, ECU 7A, KWP D3B0. Снято с рабочего сеанса 850 OBD-II.",
                    )
                )
            }
            prefs.fanActuatorSeeded = true
        }
    }

    private companion object {
        /** Volvo's own K-line protocol, engine ECU 7A, tester address 13. */
        val FAN_INIT_SCRIPT = """
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
         */
        const val FAN_LOW_FRAME = "B00E 3203"
        const val FAN_HIGH_FRAME = "B01F 3203"

        val FAN_OFF_SCRIPT = """
            ATSH 82 7A 13
            A0
            ATPC
        """.trimIndent()
    }
}
