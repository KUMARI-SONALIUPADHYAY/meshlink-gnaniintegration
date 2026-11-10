package com.meshlink.app.core.bluetooth

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.util.UUID

/** Scans real BLE advertisements and only reports MeshLink discovery beacons. */
class BleScanner(
    private val context: Context,
    private val onPeerFound: (MeshPeer) -> Unit,
    private val onStatusChanged: (String) -> Unit = {}
) {
    private val adapter get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var active = false
    @Volatile private var wanted = false
    private val handler = Handler(Looper.getMainLooper())
    private var retryDelayMs = 1_500L
    private val retry = object : Runnable {
        override fun run() {
            if (!wanted || active) return
            startScanAttempt()
        }
    }

    private val cb = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
        override fun onScanFailed(errorCode: Int) {
            active = false
            onStatusChanged("BLE scan failed ($errorCode); retrying")
            scheduleRetry()
        }
    }

    fun start() {
        wanted = true
        handler.removeCallbacks(retry)
        startScanAttempt()
    }

    private fun startScanAttempt() {
        if (!wanted || active) return
        if (!permission()) {
            onStatusChanged("BLE scan permission missing; scanning paused")
            scheduleRetry()
            return
        }
        val scanner = adapter?.bluetoothLeScanner ?: run {
            onStatusChanged("BLE scanner unavailable; retrying")
            scheduleRetry()
            return
        }
        try {
            // Do not require a service-UUID filter: the identity beacon is in manufacturer data.
            scanner.startScan(
                null,
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build(),
                cb
            )
            active = true
            retryDelayMs = 1_500L
            onStatusChanged("BLE scanning active")
        } catch (t: Throwable) {
            active = false
            onStatusChanged("BLE scan error: ${t.message ?: "unknown"}; retrying")
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (!wanted) return
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(15_000L)
    }

    fun stop() {
        wanted = false
        handler.removeCallbacks(retry)
        if (active) {
            try { adapter?.bluetoothLeScanner?.stopScan(cb) } catch (_: Throwable) { }
        }
        active = false
    }

    private fun handle(result: ScanResult) {
        val data = result.scanRecord?.getManufacturerSpecificData(MeshBleConstants.MANUFACTURER_ID) ?: return
        if (data.size != 21) return
        val b = ByteBuffer.wrap(data)
        if (b.int != MeshBleConstants.DISCOVERY_MAGIC) return
        if (b.get() != MeshBleConstants.DISCOVERY_VERSION) return
        val nodeId = UUID(b.long, b.long).toString()
        if (nodeId.isBlank()) return

        val device = result.device
        val name = "Authenticating…"
        onPeerFound(
            MeshPeer(
                address = device.address,
                nodeId = nodeId,
                rssi = result.rssi,
                displayName = name,
                lastSeen = System.currentTimeMillis()
            )
        )
    }

    private fun permission() = if (Build.VERSION.SDK_INT >= 31) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
