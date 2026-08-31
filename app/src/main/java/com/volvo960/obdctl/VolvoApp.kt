package com.volvo960.obdctl

import android.app.Application
import com.volvo960.obdctl.data.GearEstimator
import com.volvo960.obdctl.data.VehicleDataPoller
import com.volvo960.obdctl.prefs.AppPrefs
import com.volvo960.obdctl.service.NotificationHelper
import com.volvo960.obdctl.transport.CommandLogger
import com.volvo960.obdctl.transport.Elm327Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Holds the app-wide singletons. The transport and the poller must outlive any
 * single Activity — the connection keeps running with no UI on screen at all —
 * so they live here rather than in a ViewModel.
 */
class VolvoApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var logger: CommandLogger
        private set
    lateinit var transport: Elm327Transport
        private set
    lateinit var prefs: AppPrefs
        private set
    lateinit var notificationHelper: NotificationHelper
        private set
    lateinit var vehicleData: VehicleDataPoller
        private set

    override fun onCreate() {
        super.onCreate()
        logger = CommandLogger(this)
        transport = Elm327Transport(this, logger)
        prefs = AppPrefs(this)
        notificationHelper = NotificationHelper(this)
        transport.transportPreference = prefs.transportPreference
        transport.protocol = prefs.obdProtocol
        vehicleData = VehicleDataPoller(transport, appScope, prefs, logger, GearEstimator(prefs))
        vehicleData.start()
    }
}
