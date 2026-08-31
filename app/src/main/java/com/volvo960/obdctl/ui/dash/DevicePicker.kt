package com.volvo960.obdctl.ui.dash

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.volvo960.obdctl.R

/**
 * Picks the dongle: paired devices first, then whatever a LE scan turns up.
 *
 * The scan matters because an ELM327 v1.5 on Bluetooth LE never appears in the
 * paired list — LE dongles generally don't bond at all, so a picker built only
 * from `bondedDevices` cannot see them.
 */
@SuppressLint("MissingPermission")
class DevicePicker(private val context: Context) {

    companion object {
        private const val SCAN_DURATION_MS = 12_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var scanCallback: ScanCallback? = null

    private val entries = mutableListOf<BluetoothDevice>()

    fun show(onPicked: (BluetoothDevice) -> Unit) {
        val adapter = bluetoothAdapter()
        if (adapter == null || !adapter.isEnabled) {
            AlertDialog.Builder(context)
                .setMessage(R.string.bluetooth_off)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        entries.clear()
        entries += try { adapter.bondedDevices.orEmpty().toList() } catch (e: SecurityException) { emptyList() }

        val listAdapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, labels())
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.dialog_title_choose_device)
            .setAdapter(listAdapter) { _, which -> entries.getOrNull(which)?.let(onPicked) }
            .setNegativeButton(R.string.action_cancel, null)
            .setOnDismissListener { stopScan() }
            .create()
        dialog.show()

        startScan {
            listAdapter.clear()
            listAdapter.addAll(labels())
            listAdapter.notifyDataSetChanged()
        }
    }

    private fun labels(): List<String> = entries.map { device ->
        val name = try { device.name } catch (e: SecurityException) { null } ?: "?"
        val kind = when (device.type) {
            BluetoothDevice.DEVICE_TYPE_LE -> "LE"
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> "SPP"
            BluetoothDevice.DEVICE_TYPE_DUAL -> "SPP+LE"
            else -> "?"
        }
        "$name · $kind\n${device.address}"
    }

    private fun startScan(onFound: () -> Unit) {
        if (!canScan()) return
        val scanner = bluetoothAdapter()?.bluetoothLeScanner ?: return
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                if (entries.any { it.address == device.address }) return
                entries += device
                onFound()
            }
        }
        scanCallback = callback
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(null, settings, callback)
        } catch (e: SecurityException) {
            scanCallback = null
            return
        }
        // A scan left running is a battery leak in its own right.
        handler.postDelayed({ stopScan() }, SCAN_DURATION_MS)
    }

    private fun stopScan() {
        val callback = scanCallback ?: return
        scanCallback = null
        try {
            bluetoothAdapter()?.bluetoothLeScanner?.stopScan(callback)
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
        }
    }

    private fun canScan(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            // Before Android 12 a LE scan is a location capability.
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(context, needed) == PackageManager.PERMISSION_GRANTED
    }

    private fun bluetoothAdapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
}
