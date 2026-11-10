package com.meshlink.app.core.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/** Local GATT server used by every MeshLink phone for bidirectional BLE transport. */
class GattServer(
    private val context: Context,
    private val onMessageReceived: (BluetoothDevice, ByteArray) -> Unit,
    private val onStatusChanged: (String) -> Unit = {}
) {
    private var server: BluetoothGattServer? = null
    private var tx: BluetoothGattCharacteristic? = null
    private val notifiers = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private val connectedAddresses = ConcurrentHashMap.newKeySet<String>()
    private val mtuByAddress = ConcurrentHashMap<String, Int>()
    private val reassemblers = ConcurrentHashMap<String, FrameReassembler>()

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasConnectPermission()) {
            stop()
            onStatusChanged("Bluetooth connect permission missing — GATT server paused")
            return
        }
        val bluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        if (bluetoothManager.adapter?.isEnabled != true) {
            // A server handle created before Bluetooth was switched off is no longer
            // trustworthy. Close it so maintenance can create a fresh server later.
            stop()
            onStatusChanged("Bluetooth is OFF — GATT server paused")
            return
        }
        if (server != null) return

        val rx = BluetoothGattCharacteristic(
            MeshBleConstants.RX_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val txCharacteristic = BluetoothGattCharacteristic(
            MeshBleConstants.TX_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        txCharacteristic.addDescriptor(
            BluetoothGattDescriptor(
                MeshBleConstants.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or
                    BluetoothGattDescriptor.PERMISSION_WRITE
            ).apply {
                value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
        )

        val service = BluetoothGattService(
            MeshBleConstants.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        ).apply {
            addCharacteristic(rx)
            addCharacteristic(txCharacteristic)
        }

        val opened = bluetoothManager.openGattServer(context, callback)
        if (opened == null) {
            onStatusChanged("GATT server unavailable")
            return
        }
        server = opened
        tx = txCharacteristic

        val added = opened.addService(service)
        if (!added) {
            onStatusChanged("GATT service registration failed")
            stop()
        } else {
            // addService() is asynchronous; the server is not ready until onServiceAdded.
            onStatusChanged("GATT service registering")
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        try { server?.close() } catch (_: Throwable) { }
        server = null
        tx = null
        notifiers.clear()
        connectedAddresses.clear()
        mtuByAddress.clear()
        reassemblers.clear()
    }

    @SuppressLint("MissingPermission")
    fun sendToDevice(device: BluetoothDevice, data: ByteArray): Boolean {
        val gattServer = server ?: return false
        val characteristic = tx ?: return false
        if (!notifiers.contains(device)) return false

        return try {
            var ok = true
            val mtu = mtuByAddress[device.address] ?: 23
            val maxPayload = (mtu - 12).coerceIn(BleFrameCodec.MAX_PAYLOAD, 235)
            for (frame in BleFrameCodec.split(data, maxPayload)) {
                val result = if (Build.VERSION.SDK_INT >= 33) {
                    gattServer.notifyCharacteristicChanged(device, characteristic, false, frame)
                } else {
                    characteristic.value = frame
                    gattServer.notifyCharacteristicChanged(device, characteristic, false)
                }
                if (result != BluetoothGatt.GATT_SUCCESS) {
                    ok = false
                    break
                }
                // Give the remote stack a small gap between notifications. This is
                // intentionally bounded; the real packet remains offline/local.
                Thread.sleep(2L)
            }
            ok
        } catch (_: Throwable) {
            false
        }
    }

    fun connectedDevices(): Set<BluetoothDevice> = notifiers.toSet()

    fun hasConnection(address: String): Boolean = connectedAddresses.contains(address)

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (service.uuid != MeshBleConstants.SERVICE_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onStatusChanged("GATT server active")
            } else {
                onStatusChanged("GATT service registration failed ($status)")
                stop()
            }
        }

        override fun onConnectionStateChange(
            device: BluetoothDevice,
            status: Int,
            newState: Int
        ) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connectedAddresses.add(device.address)
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedAddresses.remove(device.address)
                notifiers.remove(device)
                reassemblers.remove(device.address)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            mtuByAddress[device.address] = mtu.coerceAtLeast(23)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid == MeshBleConstants.CCCD_UUID) {
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    notifiers.add(device)
                } else {
                    notifiers.remove(device)
                }
            }
            if (responseNeeded) {
                server?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    null
                )
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid == MeshBleConstants.RX_UUID) {
                val complete = reassemblers
                    .getOrPut(device.address) { FrameReassembler() }
                    .accept(value)
                if (complete != null) onMessageReceived(device, complete)
            }
            if (responseNeeded) {
                server?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    null
                )
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            server?.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                0,
                characteristic.value ?: ByteArray(0)
            )
        }
    }
}
