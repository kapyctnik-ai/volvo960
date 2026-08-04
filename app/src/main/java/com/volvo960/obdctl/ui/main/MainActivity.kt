package com.volvo960.obdctl.ui.main

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.volvo960.obdctl.R
import com.volvo960.obdctl.VolvoApp
import com.volvo960.obdctl.data.Actuator
import com.volvo960.obdctl.databinding.ActivityMainBinding
import com.volvo960.obdctl.transport.ConnectionState
import com.volvo960.obdctl.ui.console.ConsoleActivity
import com.volvo960.obdctl.ui.editor.ActuatorEditActivity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private lateinit var activeHoldsAdapter: ActiveHoldsAdapter
    private lateinit var actuatorAdapter: ActuatorListAdapter
    private var lastActuators: List<Actuator> = emptyList()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestRuntimePermissions()

        activeHoldsAdapter = ActiveHoldsAdapter(onStop = { viewModel.stopHold(it) })
        binding.recyclerActiveHolds.layoutManager = LinearLayoutManager(this)
        binding.recyclerActiveHolds.adapter = activeHoldsAdapter

        actuatorAdapter = ActuatorListAdapter(
            isHeld = { viewModel.isHeld(it) },
            onToggleHold = ::onToggleHold,
            onSendOnce = { viewModel.sendOnce(it) },
            onEdit = { openEditor(it) },
            onDelete = ::confirmDelete,
        )
        binding.recyclerActuators.layoutManager = LinearLayoutManager(this)
        binding.recyclerActuators.adapter = actuatorAdapter

        binding.textSelectedDevice.text =
            (application as VolvoApp).prefs.lastDeviceAddress ?: getString(R.string.no_device_selected)

        binding.buttonChooseDevice.setOnClickListener { showDevicePicker() }
        binding.buttonConnect.setOnClickListener { onConnectClicked() }
        binding.buttonStopAll.setOnClickListener { viewModel.stopAll() }
        binding.buttonConsole.setOnClickListener { startActivity(Intent(this, ConsoleActivity::class.java)) }
        binding.buttonAddActuator.setOnClickListener { openEditor(null) }

        observeState()
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) perms += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms += Manifest.permission.POST_NOTIFICATIONS
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.connectionState.collect { state -> renderConnectionState(state) }
                }
                launch {
                    viewModel.coolantTempC.collect { tempC ->
                        binding.textCoolantTemp.text = if (tempC != null) {
                            getString(R.string.coolant_temp_value, tempC)
                        } else {
                            getString(R.string.coolant_temp_unknown)
                        }
                    }
                }
                launch {
                    combine(viewModel.actuators, viewModel.activeHolds) { list, holds -> list to holds }
                        .collect { (list, holds) ->
                            lastActuators = list
                            binding.textActuatorsEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                            actuatorAdapter.submit(list)

                            binding.textActiveHoldsEmpty.visibility = if (holds.isEmpty()) View.VISIBLE else View.GONE
                            activeHoldsAdapter.submit(holds.values.sortedBy { it.actuatorName })
                        }
                }
            }
        }
    }

    private fun renderConnectionState(state: ConnectionState) {
        binding.textConnectionStatus.text = when (state) {
            is ConnectionState.Connected -> getString(R.string.status_connected, state.deviceName)
            ConnectionState.Connecting -> getString(R.string.status_connecting)
            is ConnectionState.Failed -> getString(R.string.status_failed, state.reason)
            ConnectionState.Disconnected -> getString(R.string.status_disconnected)
        }
        // The status line gets overwritten by the next reconnect attempt within
        // ~1s, too fast to read — a longer-lived Toast makes the actual error
        // (e.g. a socket failure) legible instead of just flashing by.
        if (state is ConnectionState.Failed) {
            Toast.makeText(this, getString(R.string.status_failed, state.reason), Toast.LENGTH_LONG).show()
        }
        binding.buttonConnect.text = getString(
            if (state is ConnectionState.Connected || state is ConnectionState.Connecting) {
                R.string.action_disconnect
            } else {
                R.string.action_connect
            }
        )
    }

    private fun onConnectClicked() {
        val state = viewModel.connectionState.value
        if (state is ConnectionState.Connected || state is ConnectionState.Connecting) {
            viewModel.disconnect()
            return
        }
        val address = (application as VolvoApp).prefs.lastDeviceAddress
        val device = address?.let { getBondedDevice(it) }
        if (device == null) showDevicePicker() else connectToDevice(device)
    }

    private fun connectToDevice(device: BluetoothDevice) {
        binding.textSelectedDevice.text = deviceLabel(device)
        viewModel.connect(device)
    }

    private fun showDevicePicker() {
        val adapter = bluetoothAdapter()
        if (adapter == null) {
            AlertDialog.Builder(this)
                .setMessage(R.string.no_paired_devices)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestRuntimePermissions()
            return
        }
        val bonded = try {
            adapter.bondedDevices.toList()
        } catch (e: SecurityException) {
            emptyList()
        }
        if (bonded.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_title_choose_device)
                .setMessage(R.string.no_paired_devices)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val labels = bonded.map { deviceLabel(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_title_choose_device)
            .setItems(labels) { _, which -> connectToDevice(bonded[which]) }
            .show()
    }

    private fun deviceLabel(device: BluetoothDevice): String =
        "${try { device.name } catch (e: SecurityException) { null } ?: "?"} (${device.address})"

    private fun bluetoothAdapter(): BluetoothAdapter? =
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun getBondedDevice(address: String): BluetoothDevice? {
        val adapter = bluetoothAdapter() ?: return null
        return try {
            adapter.bondedDevices.firstOrNull { it.address == address }
        } catch (e: SecurityException) {
            null
        }
    }

    private fun openEditor(actuator: Actuator?) {
        val intent = Intent(this, ActuatorEditActivity::class.java)
        if (actuator != null) intent.putExtra(ActuatorEditActivity.EXTRA_ACTUATOR_ID, actuator.id)
        startActivity(intent)
    }

    private fun confirmDelete(actuator: Actuator) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.confirm_delete_actuator, actuator.name))
            .setPositiveButton(R.string.action_delete) { _, _ -> viewModel.deleteActuator(actuator) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun onToggleHold(actuator: Actuator, checked: Boolean) {
        if (checked) {
            if (viewModel.needsWarning(actuator)) showWarningDialog(actuator) else viewModel.startHold(actuator)
        } else {
            viewModel.stopHold(actuator.id)
        }
    }

    private fun showWarningDialog(actuator: Actuator) {
        var dontAskAgain = false
        AlertDialog.Builder(this)
            .setTitle(R.string.warning_title)
            .setMessage(getString(R.string.warning_message, actuator.name))
            .setMultiChoiceItems(arrayOf(getString(R.string.warning_dont_ask_again)), booleanArrayOf(false)) { _, _, checked ->
                dontAskAgain = checked
            }
            .setPositiveButton(R.string.action_proceed) { _, _ ->
                if (dontAskAgain) viewModel.acknowledgeWarning(actuator.id)
                viewModel.startHold(actuator)
            }
            .setNegativeButton(R.string.action_cancel) { _, _ -> actuatorAdapter.submit(lastActuators) }
            .setOnCancelListener { actuatorAdapter.submit(lastActuators) }
            .show()
    }
}
