package com.volvo960.obdctl.ui.dash

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.volvo960.obdctl.R
import com.volvo960.obdctl.VolvoApp
import com.volvo960.obdctl.data.Actuator
import com.volvo960.obdctl.databinding.ActivityDashBinding
import com.volvo960.obdctl.service.HoldService
import com.volvo960.obdctl.transport.ConnectionState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The cluster, full screen and free of app chrome — no title bar, no system
 * bars, no button strip. Controls live on the cluster itself:
 *
 *  - tap the fan tell-tale: cycle off → low → high → off
 *  - long-press the tell-tale: toggle the automatic fan
 *  - tap the trip knob: zero the trip counter
 *  - long-press anywhere else: open the registry and console
 */
class DashActivity : AppCompatActivity() {

    private companion object {
        const val FAN_LOW_MARKER = "B00E 3203"
        const val FAN_HIGH_MARKER = "B01F 3203"
    }

    private lateinit var binding: ActivityDashBinding
    private val app: VolvoApp get() = application as VolvoApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        goFullScreen()
        // A cluster that blanks while driving is useless.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding.dashboard.onTripReset = {
            app.vehicleData.resetTrip()
            Toast.makeText(this, R.string.trip_reset_done, Toast.LENGTH_SHORT).show()
        }
        binding.dashboard.onFanTap = { cycleFan() }
        binding.dashboard.onFanLongPress = {
            val enabled = !app.prefs.autoFanEnabled
            app.prefs.autoFanEnabled = enabled
            Toast.makeText(
                this,
                if (enabled) getString(R.string.auto_fan_on, app.prefs.autoFanOnC) else getString(R.string.auto_fan_off),
                Toast.LENGTH_SHORT,
            ).show()
        }
        binding.dashboard.onOpenControls = {
            startActivity(Intent(this, com.volvo960.obdctl.ui.main.MainActivity::class.java))
        }

        observe()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullScreen()
    }

    private fun goFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(app.vehicleData.state, app.holdManager.activeHolds) { vehicle, holds ->
                        vehicle to holds.isNotEmpty()
                    }.collect { (vehicle, fanOn) ->
                        binding.dashboard.submit(
                            speedKmh = vehicle.speedKmh,
                            rpm = vehicle.rpm,
                            coolantTempC = vehicle.coolantTempC,
                            atfTempC = vehicle.atfTempC,
                            fuelPercent = vehicle.fuelLevelPercent,
                            tripKm = vehicle.tripKm,
                            totalKm = vehicle.totalKm,
                            fanOn = fanOn,
                        )
                    }
                }
                launch {
                    // Connection state alone flickers while the adapter settles,
                    // so the last complaint is kept on screen until a reading
                    // actually succeeds. A message that vanishes before it can
                    // be read is worse than no message.
                    combine(app.transport.connectionState, app.vehicleData.lastError) { state, pollError ->
                        when (state) {
                            is ConnectionState.Failed -> getString(R.string.dash_status_failed, state.reason)
                            ConnectionState.Disconnected -> getString(R.string.dash_status_disconnected)
                            ConnectionState.Connecting -> getString(R.string.dash_status_connecting)
                            is ConnectionState.Connected -> pollError?.let {
                                getString(R.string.dash_status_failed, it)
                            }
                        }
                    }.collect { message ->
                        binding.textDashStatus.text = message.orEmpty()
                        binding.textDashStatus.visibility = if (message == null) View.GONE else View.VISIBLE
                    }
                }
            }
        }
    }

    /** Off → low → high → off, so one tell-tale covers both fan speeds. */
    private fun cycleFan() {
        lifecycleScope.launch {
            val low = findFan(FAN_LOW_MARKER)
            val high = findFan(FAN_HIGH_MARKER)
            if (low == null || high == null) {
                Toast.makeText(this@DashActivity, R.string.fan_not_ready, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val lowOn = app.holdManager.isHeld(low.id)
            val highOn = app.holdManager.isHeld(high.id)
            HoldService.start(app)
            when {
                !lowOn && !highOn -> app.holdManager.start(low)
                lowOn -> {
                    app.holdManager.stop(low.id)
                    app.holdManager.start(high)
                }
                else -> app.holdManager.stop(high.id)
            }
        }
    }

    private suspend fun findFan(marker: String): Actuator? =
        app.repository.observeAll().first().firstOrNull { it.initScript.contains(marker) }
}
