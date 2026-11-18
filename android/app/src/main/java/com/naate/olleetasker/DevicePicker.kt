package com.naate.olleetasker

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Lets the user choose the watch from paired devices and a live BLE scan. Devices advertising the
 * Forge service, or named "Ollee", are listed first.
 */
@SuppressLint("MissingPermission")
class DevicePicker(private val activity: Activity, private val onPicked: (String) -> Unit) {

    private class Entry(val address: String, var name: String?, var ollee: Boolean, val paired: Boolean)

    fun show() {
        val adapter = activity.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            toast(R.string.bluetooth_off)
            return
        }
        val missing = Perms.missing(activity, Perms.SCAN)
        if (missing.isNotEmpty()) {
            activity.requestPermissions(missing, REQUEST_PERMISSION)
            toast(R.string.grant_then_retry)
            return
        }

        val entries = adapter.bondedDevices.orEmpty()
            .map { Entry(it.address, it.name, looksLikeOllee(it.name), paired = true) }
            .toMutableList()
        val listAdapter = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1)
        fun refresh() {
            entries.sortWith(compareByDescending<Entry> { it.ollee }.thenByDescending { it.paired }.thenBy { it.name ?: "\uFFFF" })
            listAdapter.clear()
            listAdapter.addAll(entries.map(::label))
        }

        val scanner = adapter.bluetoothLeScanner
        val handler = Handler(Looper.getMainLooper())
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                val forge = record?.serviceUuids?.any { it.uuid == Ble.FORGE_SERVICE } == true
                val name = record?.deviceName ?: result.device.name
                handler.post {
                    val existing = entries.find { it.address == result.device.address }
                    if (existing == null) {
                        if (name == null && !forge) return@post
                        entries += Entry(result.device.address, name, forge || looksLikeOllee(name), paired = false)
                    } else {
                        if (existing.name == null) existing.name = name
                        existing.ollee = existing.ollee || forge
                    }
                    refresh()
                }
            }

            override fun onScanFailed(errorCode: Int) {
                handler.post { Toast.makeText(activity, "Scan failed (error $errorCode)", Toast.LENGTH_LONG).show() }
            }
        }
        val stopScan = Runnable { runCatching { scanner?.stopScan(callback) } }

        refresh()
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.pick_watch_title)
            .setAdapter(listAdapter) { _, which -> onPicked(entries[which].address) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnDismissListener {
            handler.removeCallbacks(stopScan)
            stopScan.run()
        }
        dialog.show()

        if (scanner != null) {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            handler.postDelayed(stopScan, SCAN_DURATION_MS)
        }
    }

    private fun label(e: Entry): String {
        val tags = listOfNotNull(if (e.paired) "paired" else null, if (e.ollee) "Ollee" else null)
        val star = if (e.ollee) "★ " else ""
        return "$star${e.name ?: "Unknown"}\n${e.address}" + if (tags.isEmpty()) "" else " · ${tags.joinToString(" · ")}"
    }

    private fun looksLikeOllee(name: String?) = name?.contains("ollee", ignoreCase = true) == true

    private fun toast(res: Int) = Toast.makeText(activity, res, Toast.LENGTH_LONG).show()

    companion object {
        const val REQUEST_PERMISSION = 42
        private const val SCAN_DURATION_MS = 30_000L
    }
}
