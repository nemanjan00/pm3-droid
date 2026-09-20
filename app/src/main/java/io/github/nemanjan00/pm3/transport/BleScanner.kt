package io.github.nemanjan00.pm3.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid

/**
 * Scans for Proxmark5 BWM modules.
 *
 * The BWM advertises its SPP service UUID (0xAE86), so the scan filters on
 * that rather than on the device name. Name-matching would miss a renamed
 * module and would also require the scan response to have arrived.
 *
 * Filtering by service UUID is also what lets the manifest declare
 * BLUETOOTH_SCAN with `neverForLocation`: nothing here derives location.
 */
@SuppressLint("MissingPermission") // caller holds BLUETOOTH_SCAN; see MainActivity
class BleScanner(private val context: Context) {

    private var callback: ScanCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, BluetoothDevice>()

    val isScanning: Boolean get() = callback != null

    /**
     * Starts a scan, reporting the accumulated device list on each hit.
     *
     * Stops itself after [durationMs]; a BLE scan left running is a real
     * battery cost, and Android throttles an app that starts more than five
     * scans in 30 seconds.
     */
    fun start(
        durationMs: Long = SCAN_DURATION_MS,
        onResult: (List<BluetoothDevice>) -> Unit,
        onFinished: () -> Unit = {},
    ) {
        val adapter = adapterOf(context) ?: return
        if (!adapter.isEnabled) return
        val scanner = adapter.bluetoothLeScanner ?: return
        if (callback != null) return

        found.clear()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                if (found.put(device.address, device) == null) {
                    onResult(found.values.toList())
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                var added = false
                results.forEach { r ->
                    r.device?.let { if (found.put(it.address, it) == null) added = true }
                }
                if (added) onResult(found.values.toList())
            }

            override fun onScanFailed(errorCode: Int) {
                stop()
                onFinished()
            }
        }

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BleSppTransport.SPP_SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        callback = cb
        try {
            scanner.startScan(filters, settings, cb)
        } catch (_: SecurityException) {
            callback = null
            onFinished()
            return
        }

        handler.postDelayed({
            stop()
            onFinished()
        }, durationMs)
    }

    fun stop() {
        val cb = callback ?: return
        callback = null
        runCatching {
            adapterOf(context)?.bluetoothLeScanner?.stopScan(cb)
        }
    }

    companion object {
        private const val SCAN_DURATION_MS = 12_000L
    }
}
