package com.meshlink.app.core.bluetooth

import java.util.UUID

object MeshBleConstants {

    const val DEVICE_NAME = "MeshLink"

    val SERVICE_UUID: UUID =
        UUID.fromString("7D3A1000-6A5B-4A21-9C8E-111111111111")

    val IDENTITY_UUID: UUID =
        UUID.fromString("7D3A1001-6A5B-4A21-9C8E-111111111111")

    val RX_UUID: UUID =
        UUID.fromString("7D3A1002-6A5B-4A21-9C8E-111111111111")

    val TX_UUID: UUID =
        UUID.fromString("7D3A1003-6A5B-4A21-9C8E-111111111111")

    val CCCD_UUID: UUID =
        UUID.fromString(
            "00002902-0000-1000-8000-00805F9B34FB"
        )

    const val MANUFACTURER_ID = 0x1234
    const val DISCOVERY_MAGIC = 0x4D4C4E4B // "MLNK"
    const val DISCOVERY_VERSION: Byte = 1

    const val SCAN_PERIOD_MS = 10_000L

    const val MAX_CONNECTIONS = 7

    const val DISCOVERY_INTERVAL_MS = 5_000L
}