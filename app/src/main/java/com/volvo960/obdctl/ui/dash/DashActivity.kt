package com.volvo960.obdctl.ui.dash

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Toast
import android.view.WindowManager
import android.widget.EditText
import android.widget.GridLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.volvo960.obdctl.R
import com.volvo960.obdctl.VolvoApp
import com.volvo960.obdctl.data.FuelSource
import com.volvo960.obdctl.data.SelfTest
import com.volvo960.obdctl.data.TANK_CAPACITY_L
import com.volvo960.obdctl.data.VehicleState
import com.volvo960.obdctl.databinding.ActivityDashBinding
import com.volvo960.obdctl.prefs.AppPrefs
import com.volvo960.obdctl.service.ObdService
import com.volvo960.obdctl.transport.ConnectionState
import com.volvo960.obdctl.ui.console.ConsoleActivity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The dashboard: three pieces of the real cluster up top, a grid of readings
 * under them, the hand-managed tank at the bottom. Portrait only — this is a
 * phone clipped to the windscreen, not a screen replacing the cluster.
 *
 * Controls stay off the tiles: the status line picks the dongle (long-press for
 * the raw console), and the trip tile resets on a long press.
 */
class DashActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashBinding
    private val app: VolvoApp get() = application as VolvoApp

    private lateinit var tileCoolant: TileView
    private lateinit var tileRpm: TileView
    private lateinit var tileConsumption: TileView
    private lateinit var tileFlow: TileView
    private lateinit var tileAverage: TileView
    private lateinit var tileFuelLevel: TileView
    private lateinit var tileTrip: TileView
    private lateinit var tileTotal: TileView
    private lateinit var tileLoad: TileView
    private lateinit var tileIntake: TileView

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // A dashboard that blanks while driving is useless.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding.dialSpeedo.mode = DialView.Mode.SPEEDO
        binding.dialTacho.mode = DialView.Mode.TACHO
        binding.dialClock.mode = DialView.Mode.CLOCK

        buildTiles()
        requestRuntimePermissions()

        binding.textStatus.setOnClickListener { onStatusTapped() }
        binding.textStatus.setOnLongClickListener {
            startActivity(Intent(this, ConsoleActivity::class.java))
            true
        }

        binding.buttonPlus10.setOnClickListener { app.vehicleData.addTankLiters(10.0) }
        binding.buttonPlus20.setOnClickListener { app.vehicleData.addTankLiters(20.0) }
        binding.buttonMinus5.setOnClickListener { app.vehicleData.addTankLiters(-5.0) }
        binding.buttonSetTank.setOnClickListener { askTankLiters() }

        observe()
        autoConnect()
    }

    private fun buildTiles() {
        val grid = binding.gridTiles
        fun tile(labelRes: Int, unit: String): TileView {
            val view = TileView(this).apply {
                label = getString(labelRes)
                this.unit = unit
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = resources.getDimensionPixelSize(R.dimen.tile_height)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            grid.addView(view, params)
            return view
        }
        tileCoolant = tile(R.string.tile_coolant, "°C")
        tileRpm = tile(R.string.tile_rpm, getString(R.string.unit_rpm))
        tileConsumption = tile(R.string.tile_consumption, getString(R.string.unit_l100))
        tileFlow = tile(R.string.tile_flow, getString(R.string.unit_lph))
        tileAverage = tile(R.string.tile_average, getString(R.string.unit_l100))
        tileFuelLevel = tile(R.string.tile_fuel_level, "%")
        tileTrip = tile(R.string.tile_trip, getString(R.string.unit_km))
        tileTotal = tile(R.string.tile_total, getString(R.string.unit_km))
        tileLoad = tile(R.string.tile_load, "%")
        tileIntake = tile(R.string.tile_intake, "°C")

        tileTrip.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.trip_reset_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ -> app.vehicleData.resetTrip() }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
            true
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun hasScanPermission(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(this, needed) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_CONNECT
            perms += Manifest.permission.BLUETOOTH_SCAN
        } else {
            // A LE scan below Android 12 is gated on location, not Bluetooth.
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    /** Reconnects to the last dongle on launch; that is what the app is for. */
    private fun autoConnect(force: Boolean = false) {
        val address = app.prefs.lastDeviceAddress ?: return
        if (force || app.transport.connectionState.value is ConnectionState.Disconnected) {
            ObdService.start(this, address)
        }
    }

    private fun onStatusTapped() {
        val connected = app.transport.connectionState.value is ConnectionState.Connected
        val items = arrayOf(
            getString(R.string.action_self_test),
            getString(R.string.action_choose_device),
            getString(R.string.action_transport),
            getString(if (connected) R.string.action_disconnect else R.string.action_connect_now),
            getString(R.string.action_open_console),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> runSelfTest()
                    1 -> pickDevice()
                    2 -> chooseTransport()
                    3 -> if (connected) ObdService.stop(this) else autoConnect(force = true)
                    else -> startActivity(Intent(this, ConsoleActivity::class.java))
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * Lets the radio be forced. A dual-mode dongle advertises Classic and LE
     * both, and "auto" takes Classic first because it costs less battery — but
     * on some dongles only the LE side is really wired to the ELM327.
     */
    private fun chooseTransport() {
        val values = listOf(AppPrefs.TRANSPORT_AUTO, AppPrefs.TRANSPORT_SPP, AppPrefs.TRANSPORT_BLE)
        val labels = arrayOf(
            getString(R.string.transport_auto),
            getString(R.string.transport_spp),
            getString(R.string.transport_ble),
        )
        val current = values.indexOf(app.prefs.transportPreference).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.action_transport)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                app.prefs.transportPreference = values[which]
                app.transport.transportPreference = values[which]
                dialog.dismiss()
                // The choice only takes effect on a fresh attempt.
                val address = app.prefs.lastDeviceAddress
                if (address != null) {
                    ObdService.stop(this)
                    ObdService.start(this, address)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * Ignition on, engine off — that is all this needs, and it says which half
     * is at fault instead of leaving a screen full of dashes to interpret.
     */
    private fun runSelfTest() {
        if (app.transport.connectionState.value !is ConnectionState.Connected) {
            Toast.makeText(this, R.string.console_not_connected, Toast.LENGTH_LONG).show()
            return
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.selftest_title)
            .setMessage(getString(R.string.selftest_running))
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.show()
        lifecycleScope.launch {
            val result = SelfTest(app.transport).run(app.prefs.obdProtocol)
            if (result.workingProtocol != null && result.workingProtocol != app.prefs.obdProtocol) {
                app.prefs.obdProtocol = result.workingProtocol
                app.transport.protocol = result.workingProtocol
            }
            dialog.setMessage(selfTestReport(result))
        }
    }

    private fun selfTestReport(result: SelfTest.Result): String {
        val lines = mutableListOf<String>()
        lines += if (result.adapterId != null) {
            getString(R.string.selftest_adapter_ok, result.adapterId.replace("\n", " "))
        } else {
            getString(R.string.selftest_adapter_bad)
        }
        result.voltage?.let { lines += getString(R.string.selftest_voltage, it) }
        result.protocolName?.let { lines += getString(R.string.selftest_protocol, it) }
        lines += if (result.ecuAnswered) {
            getString(R.string.selftest_ecu_ok, result.workingProtocol.orEmpty())
        } else {
            getString(R.string.selftest_ecu_bad)
        }
        result.coolantC?.let { lines += getString(R.string.selftest_coolant, it) }
        result.supportedPids?.let { lines += getString(R.string.selftest_raw, it.replace("\n", " ")) }
        return lines.joinToString("\n\n")
    }

    private fun pickDevice() {
        // Without the scan permission the picker can only ever show paired
        // devices, and a LE dongle is never among them — so ask first and say
        // why, instead of opening a dialog that cannot find anything.
        if (!hasScanPermission()) {
            requestRuntimePermissions()
            Toast.makeText(this, R.string.picker_no_scan_permission, Toast.LENGTH_LONG).show()
        }
        DevicePicker(this).show { device ->
            app.prefs.lastDeviceAddress = device.address
            ObdService.start(this, device.address)
        }
    }

    private fun askTankLiters() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(format1(app.vehicleData.state.value.tankLiters))
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.tank_dialog_title, TANK_CAPACITY_L.toInt()))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val liters = input.text.toString().replace(',', '.').toDoubleOrNull()
                if (liters != null) app.vehicleData.setTankLiters(liters)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.vehicleData.state.collect { render(it) }
                }
                launch {
                    combine(
                        app.transport.connectionState,
                        app.vehicleData.state,
                        app.vehicleData.lastError,
                    ) { state, vehicle, error -> Triple(state, vehicle, error) }
                        .collect { (state, vehicle, error) -> renderStatus(state, vehicle, error) }
                }
                launch {
                    // Traffic goes on screen until a tile actually has a value,
                    // then gets out of the way.
                    combine(app.logger.recent, app.vehicleData.state) { lines, vehicle ->
                        val haveReading = vehicle.coolantTempC != null || vehicle.rpm != null ||
                            vehicle.speedKmh != null
                        if (haveReading) emptyList() else lines.takeLast(14)
                    }.collect { lines ->
                        binding.textDebugLog.text = lines.joinToString("\n")
                        binding.textDebugLog.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
                    }
                }
            }
        }
    }

    private fun render(state: VehicleState) {
        binding.dialSpeedo.setValue(state.speedKmh?.toFloat())
        binding.dialSpeedo.caption = state.speedKmh?.toString()
        binding.dialTacho.setValue(state.rpm?.toFloat())

        tileCoolant.value = state.coolantTempC?.toString()
        // 105 °C is where this engine stops being merely warm.
        tileCoolant.valueColor = state.coolantTempC?.let {
            if (it >= 105) Color.parseColor("#E5482F") else null
        }
        tileRpm.value = state.rpm?.toString()
        // The method is part of the reading: MAP-based figures are an estimate
        // and the status line is often busy with something else.
        val source = when (state.fuelSource) {
            FuelSource.ECU_FUEL_RATE -> getString(R.string.fuel_short_ecu)
            FuelSource.MAF -> getString(R.string.fuel_short_maf)
            FuelSource.SPEED_DENSITY -> getString(R.string.fuel_short_map)
            null -> null
        }
        tileConsumption.value = state.consumptionL100?.let { format1(it) }
        tileConsumption.unit = listOfNotNull(getString(R.string.unit_l100), source).joinToString(" · ")
        tileFlow.value = state.fuelRateLph?.let { format1(it) }
        tileFlow.unit = listOfNotNull(getString(R.string.unit_lph), source).joinToString(" · ")
        tileAverage.value = state.averageL100?.let { format1(it) }
        tileFuelLevel.value = state.fuelLevelPercent?.toString()
        tileTrip.value = format1(state.tripKm)
        tileTotal.value = format1(state.totalKm)
        tileLoad.value = state.engineLoadPercent?.toString()
        tileIntake.value = state.intakeTempC?.toString()

        binding.textTank.text = getString(R.string.tank_value, format1(state.tankLiters), TANK_CAPACITY_L.toInt())
        binding.progressTank.progress = (state.tankLiters * 10).toInt().coerceIn(0, 800)
        binding.textRange.text = state.rangeKm?.let { getString(R.string.tank_range, it) }.orEmpty()
    }

    private fun renderStatus(state: ConnectionState, vehicle: VehicleState, error: String?) {
        val base = when (state) {
            is ConnectionState.Connected -> getString(R.string.dash_status_connected, state.deviceName)
            ConnectionState.Connecting -> getString(R.string.dash_status_connecting)
            is ConnectionState.Failed -> getString(R.string.dash_status_failed, state.reason)
            is ConnectionState.GaveUp -> getString(R.string.dash_status_gave_up, state.reason)
            ConnectionState.Disconnected -> getString(R.string.dash_status_disconnected)
        }
        // Where the fuel figure comes from decides how much to trust it, so it
        // is stated rather than hidden.
        val source = when (vehicle.fuelSource) {
            FuelSource.ECU_FUEL_RATE -> getString(R.string.fuel_source_ecu)
            FuelSource.MAF -> getString(R.string.fuel_source_maf)
            FuelSource.SPEED_DENSITY -> getString(R.string.fuel_source_map)
            null -> null
        }
        val complaint = error?.takeIf { state !is ConnectionState.Connected || vehicle.coolantTempC == null }
        binding.textStatus.text = listOfNotNull(base, source, complaint).joinToString(" · ")
    }

    private fun format1(value: Double): String = String.format(Locale.US, "%.1f", value)
}
