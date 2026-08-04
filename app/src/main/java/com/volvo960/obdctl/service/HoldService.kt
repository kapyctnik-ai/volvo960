package com.volvo960.obdctl.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.volvo960.obdctl.R
import com.volvo960.obdctl.VolvoApp
import com.volvo960.obdctl.transport.ConnectionState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the Bluetooth link and any active hold-repeat loops alive while the
 * app is backgrounded: foreground service with a persistent notification,
 * plus a WAKE_LOCK held only while at least one hold is running.
 *
 * The actual transport and hold-repeat logic live in [VolvoApp] as
 * application-scoped singletons so they survive independently of this
 * service's bind/unbind lifecycle; this class only supplies the
 * foreground-service guarantee and renders their state into a notification.
 */
class HoldService : LifecycleService() {

    companion object {
        private const val WAKE_LOCK_TAG = "volvo960:hold"
        private const val WAKE_LOCK_SAFETY_TIMEOUT_MS = 10 * 60_000L

        fun start(context: Context) {
            val intent = Intent(context, HoldService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HoldService::class.java))
        }
    }

    private lateinit var app: VolvoApp
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        app = application as VolvoApp
        startForeground(
            NotificationHelper.FOREGROUND_NOTIFICATION_ID,
            app.notificationHelper.buildForegroundNotification(connectionLabel(app.transport.connectionState.value), emptyList())
        )
        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    /**
     * Renders transport/hold state into the notification. Does NOT decide
     * whether the service should stop — [start] and [stop] are called
     * explicitly by the UI around connect/disconnect, because the flows here
     * emit their current value immediately on subscription (a fresh service
     * would otherwise see a stale "Disconnected" and stop itself before
     * [com.volvo960.obdctl.transport.Elm327Transport.connect] even runs).
     */
    private fun observeState() {
        lifecycleScope.launch {
            combine(app.transport.connectionState, app.holdManager.activeHolds) { conn, holds -> conn to holds }
                .collect { (conn, holds) ->
                    updateWakeLock(holds.isNotEmpty())
                    val notification = app.notificationHelper.buildForegroundNotification(connectionLabel(conn), holds.values.toList())
                    getSystemService(NotificationManager::class.java)
                        ?.notify(NotificationHelper.FOREGROUND_NOTIFICATION_ID, notification)
                }
        }
    }

    private fun updateWakeLock(shouldHold: Boolean) {
        if (shouldHold) {
            val lock = wakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply { setReferenceCounted(false) }
                .also { wakeLock = it }
            lock.acquire(WAKE_LOCK_SAFETY_TIMEOUT_MS)
        } else {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
    }

    private fun connectionLabel(state: ConnectionState): String = when (state) {
        is ConnectionState.Connected -> getString(R.string.status_connected, state.deviceName)
        ConnectionState.Connecting -> getString(R.string.status_connecting)
        is ConnectionState.Failed -> getString(R.string.status_failed, state.reason)
        ConnectionState.Disconnected -> getString(R.string.status_disconnected)
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
