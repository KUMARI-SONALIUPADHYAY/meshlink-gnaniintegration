package com.meshlink.app.core.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque

/** Reliable single-connection GATT client with a serialized write queue. */
class GattClient(private val context: Context) {
    @Volatile private var gatt: BluetoothGatt? = null
    private val queue = ArrayDeque<ByteArray>()
    private var writing = false
    private var serviceDiscoveryStarted = false
    private var writeRetries = 0
    @Volatile private var ready = false
    @Volatile private var maxPayload = BleFrameCodec.MAX_PAYLOAD
    private val handler = Handler(Looper.getMainLooper())
    private var connectionToken = 0

    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onDataReceived: ((ByteArray) -> Unit)? = null
    var onStatusChanged: ((String) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        disconnect()
        ready = false
        serviceDiscoveryStarted = false
        maxPayload = BleFrameCodec.MAX_PAYLOAD
        val token = ++connectionToken
        onStatusChanged?.invoke("Connecting…")
        val created = if (Build.VERSION.SDK_INT >= 23) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, callback)
        }
        gatt = created
        if (created == null) {
            onStatusChanged?.invoke("GATT connection could not start")
            onDisconnected?.invoke()
            return
        }
        handler.postDelayed({
            if (token == connectionToken && !ready && gatt === created) {
                onStatusChanged?.invoke("GATT connection timeout; retrying")
                disconnect()
                onDisconnected?.invoke()
            }
        }, 12_000L)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        connectionToken++
        ready = false
        serviceDiscoveryStarted = false
        maxPayload = BleFrameCodec.MAX_PAYLOAD
        queue.clear()
        writing = false
        writeRetries = 0
        try { gatt?.disconnect() } catch (_: Throwable) { }
        try { gatt?.close() } catch (_: Throwable) { }
        gatt = null
    }

    @SuppressLint("MissingPermission")
    fun send(data: ByteArray): Boolean {
        // Report the packet as accepted only when the notification/write channel
        // is actually ready. The queue is still used internally, but callers must
        // not label a packet SENT merely because a GATT object exists.
        if (gatt == null || !ready) return false
        val frames = BleFrameCodec.split(data, maxPayload)
        synchronized(queue) { frames.forEach { queue.addLast(it) } }
        handler.post { writeNext() }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun writeNext() {
        if (writing) return
        val g = gatt ?: return
        val characteristic = g.getService(MeshBleConstants.SERVICE_UUID)
            ?.getCharacteristic(MeshBleConstants.RX_UUID)
            ?: run {
                onStatusChanged?.invoke("RX characteristic unavailable")
                return
            }
        val frame = synchronized(queue) { queue.peekFirst() } ?: return

        writing = true
        try {
            val started = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(
                    characteristic,
                    frame,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                characteristic.value = frame
                g.writeCharacteristic(characteristic)
            }
            if (!started) {
                writing = false
                retryWrite()
            }
        } catch (t: Throwable) {
            writing = false
            onStatusChanged?.invoke("GATT write error: ${t.message ?: "unknown"}")
            retryWrite()
        }
    }

    private fun retryWrite() {
        writeRetries++
        if (writeRetries > 3) {
            writeRetries = 0
            synchronized(queue) { if (queue.isNotEmpty()) queue.removeFirst() }
            onStatusChanged?.invoke("GATT write failed; retry limit reached")
            handler.post { writeNext() }
        } else {
            handler.postDelayed({ writeNext() }, 150L * writeRetries)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(g: BluetoothGatt): Boolean {
        val characteristic = g.getService(MeshBleConstants.SERVICE_UUID)
            ?.getCharacteristic(MeshBleConstants.TX_UUID)
            ?: return false
        if (!g.setCharacteristicNotification(characteristic, true)) return false
        val descriptor = characteristic.getDescriptor(MeshBleConstants.CCCD_UUID) ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(
                    descriptor,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(descriptor)
            }
        } catch (_: Throwable) {
            false
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            // Ignore late callbacks from a timed-out/closed connection. Without this guard,
            // an old CONNECTED callback can resurrect a GATT handle after reconnect started.
            if (gatt !== g) {
                try { g.close() } catch (_: Throwable) { }
                return
            }
            if (state == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                onStatusChanged?.invoke("GATT connected; refreshing services…")
                // Some Android devices retain a stale GATT service cache after
                // replacing an app build. Refresh it before discovery when the
                // platform exposes the hidden refresh() hook.
                runCatching {
                    val refresh = g.javaClass.getMethod("refresh")
                    refresh.invoke(g)
                }
                // Request a larger ATT MTU when the phone pair supports it. The
                // frame codec still falls back to the 20-byte minimum, so legacy
                // devices keep the known-good transport path.
                val mtuRequested = runCatching { g.requestMtu(247) }.getOrDefault(false)
                // GATT operations must be serialized. Prefer the MTU callback, but
                // fall back after a bounded wait for devices that never report it.
                handler.postDelayed({
                    if (gatt === g && !serviceDiscoveryStarted) beginServiceDiscovery(g)
                }, if (mtuRequested) 650L else 120L)
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                gatt = null
                ready = false
                writing = false
                try { g.close() } catch (_: Throwable) { }
                onStatusChanged?.invoke("Disconnected (status $status)")
                onDisconnected?.invoke()
            } else if (state == BluetoothProfile.STATE_CONNECTED) {
                onStatusChanged?.invoke("GATT connection failed ($status)")
                try { g.disconnect() } catch (_: Throwable) { }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (gatt !== g) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // ATT characteristic value = MTU - 3; reserve our 9-byte frame header.
                maxPayload = (mtu - 12).coerceIn(BleFrameCodec.MAX_PAYLOAD, 235)
                onStatusChanged?.invoke("BLE MTU $mtu")
            }
            beginServiceDiscovery(g)
        }

        @SuppressLint("MissingPermission")
        private fun beginServiceDiscovery(g: BluetoothGatt) {
            if (gatt !== g || serviceDiscoveryStarted) return
            serviceDiscoveryStarted = true
            handler.post {
                if (gatt !== g) return@post
                val started = runCatching { g.discoverServices() }.getOrDefault(false)
                if (!started) {
                    serviceDiscoveryStarted = false
                    onStatusChanged?.invoke("Service discovery could not start; reconnecting")
                    try { g.disconnect() } catch (_: Throwable) { }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (gatt !== g) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onStatusChanged?.invoke("Service discovery failed ($status)")
                try { g.close() } catch (_: Throwable) { }
                if (gatt === g) gatt = null
                onDisconnected?.invoke()
                return
            }
            val service = g.getService(MeshBleConstants.SERVICE_UUID)
            val rx = service?.getCharacteristic(MeshBleConstants.RX_UUID)
            val tx = service?.getCharacteristic(MeshBleConstants.TX_UUID)
            if (service == null || rx == null || tx == null) {
                onStatusChanged?.invoke("MeshLink GATT service/characteristics missing")
                try { g.disconnect() } catch (_: Throwable) { }
                return
            }
            if (enableNotifications(g)) {
                onStatusChanged?.invoke("GATT connected; enabling notifications…")
            } else {
                // A mesh connection is bidirectional. Do not mark a peer
                // authenticated if the notification channel is unavailable.
                onStatusChanged?.invoke("Notification setup unavailable; reconnecting")
                try { g.disconnect() } catch (_: Throwable) { }
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (gatt !== g || descriptor.uuid != MeshBleConstants.CCCD_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                ready = true
                onStatusChanged?.invoke("Connected and ready")
                onConnected?.invoke()
                handler.post { writeNext() }
            } else {
                ready = false
                onStatusChanged?.invoke("Notification setup failed ($status); reconnecting")
                try { g.disconnect() } catch (_: Throwable) { }
            }
        }

        @Deprecated("Deprecated in Android 13")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (gatt !== g) return
            if (characteristic.uuid == MeshBleConstants.TX_UUID) {
                characteristic.value?.let { onDataReceived?.invoke(it.copyOf()) }
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (gatt !== g) return
            if (characteristic.uuid == MeshBleConstants.TX_UUID) {
                onDataReceived?.invoke(value.copyOf())
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (gatt !== g || characteristic.uuid != MeshBleConstants.RX_UUID) return
            writing = false
            if (status == BluetoothGatt.GATT_SUCCESS) {
                synchronized(queue) { if (queue.isNotEmpty()) queue.removeFirst() }
                writeRetries = 0
            } else {
                onStatusChanged?.invoke("GATT write failed ($status)")
            }
            handler.post { if (status == BluetoothGatt.GATT_SUCCESS) writeNext() else retryWrite() }
        }
    }
}
