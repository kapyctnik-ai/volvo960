package com.volvo960.obdctl.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.volvo960.obdctl.R
import com.volvo960.obdctl.VolvoApp
import com.volvo960.obdctl.transport.ConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

/**
 * Owns the connection for as long as it is worth owning.
 *
 * The whole point of running as a foreground service is that switching away
 * from the app — or locking the screen — doesn't interrupt the link. The whole
 * point of [Elm327Transport.onGaveUp] is the opposite: once the adapter is
 * plainly not there any more, the service and the process both go away rather
 * than sit on the radio draining the battery in a parked car.
 */
@SuppressLint("MissingPermission")
class ObdService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.volvo960.obdctl.action.START"
        const val ACTION_STOP = "com.volvo960.obdctl.action.STOP"
        const val EXTRA_ADDRESS = "device_address"

        fun start(context: Context, address: String) {
            val intent = Intent(context, ObdService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ADDRESS, address)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ObdService::class.java).apply { action = ACTION_STOP })
        }
    }

    private val app: VolvoApp get() = application as VolvoApp
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false

    /**
     * A dark screen means nobody is reading the dashboard, whether or not the
     * activity is still technically in front — worth knowing, because it is
     * the cue to slow everything down.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> app.setScreenOn(true)
                Intent.ACTION_SCREEN_OFF -> app.setScreenOn(false)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        app.setScreenOn(pm?.isInteractive ?: true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown(kill = false, reason = null)
                return START_NOT_STICKY
            }
            else -> {
                val address = intent?.getStringExtra(EXTRA_ADDRESS) ?: app.prefs.lastDeviceAddress
                if (address == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startConnection(address)
            }
        }
        // Not sticky: a process the system killed should stay dead until the
        // user opens the app again. Restarting it behind their back is exactly
        // the battery drain this design is trying to avoid.
        return START_NOT_STICKY
    }

    private fun startConnection(address: String) {
        goForeground(getString(R.string.notif_title_connecting), address)
        if (started) return
        started = true

        app.transport.onGaveUp = {
            val reason = (app.transport.connectionState.value as? ConnectionState.GaveUp)?.reason
                ?: getString(R.string.status_disconnected)
            shutdown(kill = true, reason = reason)
        }

        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            null
        }
        if (device == null) {
            shutdown(kill = false, reason = getString(R.string.status_no_device))
            return
        }
        app.prefs.lastDeviceAddress = address
        app.transport.connect(device)

        lifecycleScope.launch {
            combine(app.transport.connectionState, app.vehicleData.state) { state, vehicle ->
                state to vehicle
            }.collect { (state, vehicle) ->
                // The wake lock is held only while there is traffic to keep
                // alive. Holding it for the life of the service kept the CPU
                // awake through every reconnect wait and every dead link —
                // which is what flattened the battery with nothing to show.
                if (state is ConnectionState.Connected) acquireWakeLock() else releaseWakeLock()

                val title = when (state) {
                    is ConnectionState.Connected -> getString(R.string.notif_title_connected)
                    ConnectionState.Connecting -> getString(R.string.notif_title_connecting)
                    is ConnectionState.Failed -> getString(R.string.notif_title_retrying)
                    is ConnectionState.GaveUp -> getString(R.string.notif_title_giving_up)
                    ConnectionState.Disconnected -> getString(R.string.notif_title_idle)
                }
                val text = when (state) {
                    is ConnectionState.Connected -> {
                        val speed = vehicle.speedKmh
                        val coolant = vehicle.coolantTempC
                        // Blanked readings mean nothing has arrived recently;
                        // saying so beats showing a number from ten minutes ago.
                        if (speed == null && coolant == null) {
                            getString(R.string.notif_text_no_data)
                        } else {
                            getString(R.string.notif_text_live, speed ?: 0, coolant ?: 0)
                        }
                    }
                    is ConnectionState.Failed -> state.reason
                    is ConnectionState.GaveUp -> state.reason
                    else -> address
                }
                goForeground(title, text)
            }
        }
    }

    private fun goForeground(title: String, text: String) {
        startForeground(
            NotificationHelper.FOREGROUND_NOTIFICATION_ID,
            app.notificationHelper.buildForegroundNotification(title, text),
        )
    }

    /**
     * A partial wake lock is what keeps polling alive with the screen off; the
     * foreground service on its own does not stop the CPU from sleeping.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val lock = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "volvo960:obd").also {
            it.setReferenceCounted(false)
            wakeLock = it
        }
        lock.acquire()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    /**
     * [kill] is the user's own rule: when the adapter is gone for good, take
     * the process with it. Stopping the service alone leaves the app resident,
     * and that is what was flattening the phone overnight.
     */
    private fun shutdown(kill: Boolean, reason: String?) {
        if (reason != null && kill) app.notificationHelper.postShutdownAlert(reason)
        app.transport.onGaveUp = null
        app.transport.disconnect()
        // Only a shutdown for good releases the transport: release() cancels
        // its scope permanently, and a plain stop has to leave it reusable.
        if (kill) app.transport.release()
        releaseWakeLock()
        wakeLock = null
        started = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (kill) {
            // Give the notification a moment to land, then take the process
            // down — Activities included.
            lifecycleScope.launch {
                delay(600)
                exitProcess(0)
            }
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenReceiver) } catch (_: IllegalArgumentException) { }
        releaseWakeLock()
        wakeLock = null
        super.onDestroy()
    }
}
