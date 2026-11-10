package com.meshlink.app.core.bluetooth

import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/** Coordinates local BLE advertising and scanning. Repeated start() calls are safe. */
class BleManager(private val context: Context, private val identityId: String, private val displayName: String) {
    private val adapter get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val scanner = BleScanner(
        context,
        { peer ->
            peers[peer.address] = peer
            onPeerDiscovered?.invoke(peer)
        },
        { status -> onStatusChanged?.invoke(status) }
    )
    private val peers = ConcurrentHashMap<String, MeshPeer>()
    private var advertiser: BleAdvertiser? = null

    var onPeerDiscovered: ((MeshPeer) -> Unit)? = null
    var onStatusChanged: ((String) -> Unit)? = null

    @Synchronized
    fun start() {
        val bluetoothAdapter = adapter ?: run {
            onStatusChanged?.invoke("Bluetooth unsupported")
            scanner.stop()
            advertiser?.stop()
            return
        }
        if (!permissions()) {
            scanner.stop()
            advertiser?.stop()
            onStatusChanged?.invoke("Bluetooth permissions required — BLE mesh paused")
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            scanner.stop()
            advertiser?.stop()
            onStatusChanged?.invoke("Bluetooth is OFF — BLE mesh paused")
            return
        }

        if (advertiser == null) {
            advertiser = BleAdvertiser(bluetoothAdapter) { status -> onStatusChanged?.invoke(status) }
        }
        advertiser?.start(identityId, displayName)
        scanner.start()
    }

    @Synchronized
    fun stop() {
        scanner.stop()
        advertiser?.stop()
        peers.clear()
    }

    fun snapshot(): List<MeshPeer> = peers.values.toList()

    private fun permissions() = if (Build.VERSION.SDK_INT >= 31) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
