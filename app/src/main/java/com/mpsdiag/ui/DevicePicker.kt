package com.mpsdiag.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Toast
import com.mpsdiag.R
import com.mpsdiag.transport.BleTransport
import com.mpsdiag.transport.BluetoothSppTransport
import com.mpsdiag.transport.Transport

/**
 * Shows paired Bluetooth devices plus anything found by a short BLE scan,
 * and hands back a ready-to-open [Transport] for the chosen adapter.
 */
@SuppressLint("MissingPermission") // Caller checks permissions first.
class DevicePicker(private val activity: Activity, private val onPicked: (Transport) -> Unit) {

    private data class Entry(val device: BluetoothDevice, val ble: Boolean) {
        @SuppressLint("MissingPermission")
        override fun toString(): String {
            val name = device.name?.takeIf { it.isNotBlank() } ?: "Unknown"
            return "$name${if (ble) "  (BLE)" else ""}\n${device.address}"
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val entries = mutableListOf<Entry>()
    private lateinit var listAdapter: ArrayAdapter<Entry>
    private var scanning = false
    private var bt: BluetoothAdapter? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            if (entries.any { it.device.address == d.address }) return
            if (d.name.isNullOrBlank()) return
            entries += Entry(d, ble = true)
            listAdapter.notifyDataSetChanged()
        }
    }

    fun show() {
        val manager = activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        if (adapter == null) {
            Toast.makeText(activity, R.string.bt_not_available, Toast.LENGTH_LONG).show()
            return
        }
        if (!adapter.isEnabled) {
            Toast.makeText(activity, R.string.bt_turn_on, Toast.LENGTH_LONG).show()
            return
        }
        bt = adapter
        entries.clear()
        adapter.bondedDevices
            .sortedByDescending { looksLikeObd(it) }
            .forEach { entries += Entry(it, ble = it.type == BluetoothDevice.DEVICE_TYPE_LE) }

        listAdapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, entries)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.pick_adapter)
            .setAdapter(listAdapter) { _, which ->
                stopScan()
                val e = entries[which]
                onPicked(if (e.ble) BleTransport(activity.applicationContext, e.device) else BluetoothSppTransport(adapter, e.device))
            }
            .setNeutralButton(R.string.scan_ble, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { stopScan() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { startScan() }
        }
        dialog.show()
        if (entries.isEmpty()) startScan()
    }

    private fun startScan() {
        val scanner = bt?.bluetoothLeScanner ?: return
        if (scanning) return
        scanning = true
        Toast.makeText(activity, R.string.scanning, Toast.LENGTH_SHORT).show()
        scanner.startScan(scanCallback)
        handler.postDelayed({ stopScan() }, 10_000)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            bt?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
    }

    private fun looksLikeObd(d: BluetoothDevice): Boolean {
        val n = d.name?.uppercase() ?: return false
        return listOf("OBD", "ELM", "VLINK", "V-LINK", "VGATE", "ICAR", "KONNWEI", "VEEPEAK", "OBDLINK")
            .any { n.contains(it) }
    }
}
