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
     * Drops a ready-made radiator-fan card into an empty registry so the first
     * target actuator is already on screen, wired up and only missing its
     * control bytes. Those bytes are car-specific and are captured from a tool
     * already proven to work on this car (README explains how) — they are
     * deliberately left blank rather than guessed, because a wrong sequence
     * sent to the engine ECU can trip something other than the fan.
     */
    private fun seedRegistryIfEmpty() {
        if (prefs.registrySeeded) return
        appScope.launch {
            if (repository.observeAll().first().isNotEmpty()) {
                prefs.registrySeeded = true
                return@launch
            }
            repository.save(
                Actuator(
                    name = "Вентилятор радиатора (M4.4)",
                    initScript = """
                        # Volvo Motronic M4.4 / KWP D3B0 / ECU 7A
                        ATZ
                        ATE0
                        ATL0
                        ATSP3
                        ATKW0
                        ATSH 84 7A F1
                    """.trimIndent(),
                    command = """
                        # ПУСТО: вставь сюда команду теста вентилятора (hex).
                        # Как её снять — см. README, раздел про btsnoop.
                    """.trimIndent(),
                    behavior = ActuatorBehavior.HOLD_REPEAT,
                    repeatIntervalMs = 2_000,
                    autoStopTimeoutMs = 5 * 60_000,
                    notes = "Байты команды не подставлены — карточка ничего не шлёт, пока не заполнишь поле команды.",
                )
            )
            prefs.registrySeeded = true
        }
    }
}
