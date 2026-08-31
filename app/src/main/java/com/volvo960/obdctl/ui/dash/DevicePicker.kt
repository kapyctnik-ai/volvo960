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
 *
 * The list always says what it is doing. An empty dialog that quietly means
 * "no permission" or "still looking" is indistinguishable from a hung one.
 */
@SuppressLint("MissingPermission")
class DevicePicker(private val context: Context) {

    companion object {
        private const val SCAN_DURATION_MS = 15_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var scanCallback: ScanCallback? = null

    private val entries = mutableListOf<BluetoothDevice>()
    private val rssi = HashMap<String, Int>()
    private var scanning = false
    private var listAdapter: ArrayAdapter<String>? = null
    private var dialog: AlertDialog? = null

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
        rssi.clear()
        entries += try {
            adapter.bondedDevices.orEmpty().toList()
        } catch (e: SecurityException) {
            emptyList()
        }

        val adapterList = ArrayAdapter(context, android.R.layout.simple_list_item_1, rows())
        listAdapter = adapterList
        dialog = AlertDialog.Builder(context)
            .setTitle(R.string.dialog_title_choose_device)
            .setAdapter(adapterList) { _, which -> entries.getOrNull(which)?.let(onPicked) }
            // Fifteen seconds is not always enough, and a dongle that was
            // asleep shows up on the second sweep.
            .setNeutralButton(R.string.action_rescan, null)
            .setNegativeButton(R.string.action_cancel, null)
            .setOnDismissListener { stopScan() }
            .create()
        dialog?.setOnShowListener { shown ->
            (shown as AlertDialog).getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                startScan()
            }
        }
        dialog?.show()

        startScan()
    }

    /**
     * One row per device, plus a status row when there is nothing to show —
     * the reason matters: missing permission is fixed differently from a
     * dongle that is switched off.
     */
    private fun rows(): List<String> {
        if (entries.isEmpty()) {
            return listOf(
                when {
                    !canScan() -> context.getString(R.string.picker_no_scan_permission)
                    scanning -> context.getString(R.string.picker_scanning)
                    else -> context.getString(R.string.picker_nothing_found)
                }
            )
        }
        return entries.map { device ->
            val name = try { device.name } catch (e: SecurityException) { null } ?: "?"
            val kind = when (device.type) {
                BluetoothDevice.DEVICE_TYPE_LE -> "LE"
                BluetoothDevice.DEVICE_TYPE_CLASSIC -> "SPP"
                BluetoothDevice.DEVICE_TYPE_DUAL -> "SPP+LE"
                else -> "?"
            }
            val signal = rssi[device.address]?.let { " · $it dBm" }.orEmpty()
            "$name · $kind$signal\n${device.address}"
        }
    }

    private fun refresh() {
        val adapterList = listAdapter ?: return
        handler.post {
            adapterList.clear()
            adapterList.addAll(rows())
            adapterList.notifyDataSetChanged()
            dialog?.setTitle(
                if (scanning) {
                    context.getString(R.string.dialog_title_choose_device_scanning, entries.size)
                } else {
                    context.getString(R.string.dialog_title_choose_device)
                }
            )
        }
    }

    private fun startScan() {
        if (scanning) return
        if (!canScan()) {
            refresh()
            return
        }
        val scanner = bluetoothAdapter()?.bluetoothLeScanner ?: return
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                rssi[device.address] = result.rssi
                if (entries.any { it.address == device.address }) {
                    refresh()
                    return
                }
                entries += device
                refresh()
            }

            override fun onScanFailed(errorCode: Int) {
                scanning = false
                refresh()
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
        scanning = true
        refresh()
        // A scan left running is a battery leak in its own right.
        handler.postDelayed({ stopScan() }, SCAN_DURATION_MS)
    }

    private fun stopScan() {
        val callback = scanCallback ?: run {
            if (scanning) {
                scanning = false
                refresh()
            }
            return
        }
        scanCallback = null
        scanning = false
        try {
            bluetoothAdapter()?.bluetoothLeScanner?.stopScan(callback)
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
        }
        refresh()
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
