package app.radiusrf.features.ble.domain

enum class BleDeviceSource { BLE, CLASSIC }

data class BleDevice(
    val name: String?,
    val address: String,
    val rssi: Int,
    val serviceUuids: List<String> = emptyList(),
    val manufacturerData: Map<Int, ByteArray> = emptyMap(),
    val serviceData: Map<String, ByteArray> = emptyMap(),
    val isBookmarked: Boolean = false,
    val lastSeen: Long = System.currentTimeMillis(),
    val source: BleDeviceSource = BleDeviceSource.BLE
) {
    val displayName: String get() = name ?: "Unknown Device"
    val signalPercent: Int
        get() = when {
            rssi >= -50 -> 100
            rssi <= -100 -> 0
            else -> 2 * (rssi + 100)
        }
}

data class BleScanState(
    val isScanning: Boolean = false,
    val isPaused: Boolean = false,
    val devices: List<BleDevice> = emptyList(),
    val error: String? = null,
    val isBluetoothEnabled: Boolean = true
)
