package com.meshlink.app.core.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer
import java.util.UUID

/** Real BLE advertiser for MeshLink discovery. The primary packet stays small enough for legacy BLE. */
class BleAdvertiser(
    private val adapter: BluetoothAdapter,
    private val onStatusChanged: (String) -> Unit = {}
) {
    private val advertiser: BluetoothLeAdvertiser? get() = adapter.bluetoothLeAdvertiser
    private var active = false
    @Volatile private var wanted = false
    private var savedNodeId: String? = null
    private var savedDisplayName: String = "Mesh Node"
    private val handler = Handler(Looper.getMainLooper())
    private var retryDelayMs = 2_000L
    private val retry = object : Runnable {
        override fun run() {
            if (wanted && !active) startAttempt()
        }
    }

    private val callback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            active = true
            retryDelayMs = 2_000L
            onStatusChanged("BLE advertising active")
        }

        override fun onStartFailure(errorCode: Int) {
            active = false
            onStatusChanged("BLE advertising failed ($errorCode); retrying")
            scheduleRetry()
        }
    }

    @SuppressLint("MissingPermission")
    fun start(nodeId: String, displayName: String) {
        savedNodeId = nodeId
        savedDisplayName = displayName
        wanted = true
        handler.removeCallbacks(retry)
        startAttempt()
    }

    @SuppressLint("MissingPermission")
    private fun startAttempt() {
        if (!wanted || active) return
        val nodeId = savedNodeId ?: return
        val displayName = savedDisplayName
        val adv = advertiser ?: run {
            onStatusChanged("BLE advertising unavailable; retrying")
            scheduleRetry()
            return
        }
        val uuid = runCatching { UUID.fromString(nodeId) }.getOrNull() ?: run {
            onStatusChanged("Invalid mesh identity")
            return
        }

        // 4-byte magic + version + full 128-bit node UUID. This is compact enough
        // for legacy advertising and lets the scanner reject unrelated BLE devices.
        val manufacturerPayload = ByteBuffer.allocate(21)
            .putInt(MeshBleConstants.DISCOVERY_MAGIC)
            .put(MeshBleConstants.DISCOVERY_VERSION)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val primary = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(MeshBleConstants.MANUFACTURER_ID, manufacturerPayload)
            .build()

        // Keep the primary advertisement self-contained. The scanner intentionally
        // does not require a service-UUID filter, so we avoid a second scan-response
        // packet that can trigger DATA_TOO_LARGE on some Android BLE stacks.
        try {
            adv.startAdvertising(settings, primary, callback)
        } catch (t: Throwable) {
            active = false
            onStatusChanged("BLE advertising error: ${t.message ?: "unknown"}; retrying")
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (!wanted) return
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(15_000L)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        wanted = false
        handler.removeCallbacks(retry)
        if (active) try { advertiser?.stopAdvertising(callback) } catch (_: Throwable) { }
        active = false
        onStatusChanged("BLE advertising stopped")
    }
}
