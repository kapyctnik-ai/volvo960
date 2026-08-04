package com.volvo960.obdctl

import android.app.Application
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
    }
}
