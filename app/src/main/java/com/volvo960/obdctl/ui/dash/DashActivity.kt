package com.volvo960.obdctl.ui.dash

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.volvo960.obdctl.R
import com.volvo960.obdctl.VolvoApp
import com.volvo960.obdctl.data.Actuator
import com.volvo960.obdctl.databinding.ActivityDashBinding
import com.volvo960.obdctl.transport.ConnectionState
import com.volvo960.obdctl.ui.main.MainActivity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The instrument cluster stand-in, and the screen the app opens on. Controls
 * that belong behind the wheel live on the strip underneath; everything else
 * (registry, console) is one tap away in [MainActivity].
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
        // A cluster that blanks while driving is useless.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding.switchAutoFan.isChecked = app.prefs.autoFanEnabled
        binding.switchAutoFan.setOnCheckedChangeListener { _, checked ->
            app.prefs.autoFanEnabled = checked
        }
        binding.buttonFanLow.setOnClickListener { toggleFan(FAN_LOW_MARKER) }
        binding.buttonFanHigh.setOnClickListener { toggleFan(FAN_HIGH_MARKER) }
        binding.buttonResetTrip.setOnClickListener { app.vehicleData.resetTrip() }
        binding.buttonControls.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        observe()
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.vehicleData.state.collect { vehicle ->
                        binding.dashboard.submit(
                            speedKmh = vehicle.speedKmh,
                            rpm = vehicle.rpm,
                            coolantTempC = vehicle.coolantTempC,
                            atfTempC = vehicle.atfTempC,
                            fuelPercent = vehicle.fuelLevelPercent,
                            tripKm = vehicle.tripKm,
                        )
                    }
                }
                launch {
                    combine(app.transport.connectionState, app.holdManager.activeHolds) { state, holds ->
                        state to holds
                    }.collect { (state, holds) ->
                        binding.textDashStatus.text = when (state) {
                            is ConnectionState.Connected ->
                                getString(R.string.dash_status_connected, state.deviceName, app.prefs.autoFanOnC)
                            ConnectionState.Connecting -> getString(R.string.dash_status_connecting)
                            is ConnectionState.Failed -> getString(R.string.dash_status_failed, state.reason)
                            ConnectionState.Disconnected -> getString(R.string.dash_status_disconnected)
                        }
                        val lowOn = holds.values.any { it.actuatorName.contains("Low") }
                        val highOn = holds.values.any { it.actuatorName.contains("High") }
                        binding.buttonFanLow.alpha = if (lowOn) 1f else 0.5f
                        binding.buttonFanHigh.alpha = if (highOn) 1f else 0.5f
                    }
                }
            }
        }
    }

    private fun toggleFan(marker: String) {
        lifecycleScope.launch {
            val fan = findFan(marker)
            if (fan == null) {
                Toast.makeText(this@DashActivity, R.string.fan_not_ready, Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (app.holdManager.isHeld(fan.id)) {
                app.holdManager.stop(fan.id)
            } else {
                com.volvo960.obdctl.service.HoldService.start(app)
                app.holdManager.start(fan)
            }
        }
    }

    private suspend fun findFan(marker: String): Actuator? =
        app.repository.observeAll().first().firstOrNull { it.initScript.contains(marker) }
}
