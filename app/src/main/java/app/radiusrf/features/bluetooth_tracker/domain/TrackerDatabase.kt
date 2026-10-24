package app.radiusrf.features.bluetooth_tracker.domain

import app.radiusrf.features.ble.domain.BleDevice
import app.radiusrf.features.signatures.domain.DeviceSignatures

// ── Data Models ──────────────────────────────────────────────────────────────

enum class TrackerType(val label: String) {
    FIND_MY("Find My"),
    FIND_HUB("Find Hub"),
    AIRTAG("AirTag"),
    SMARTTAG("SmartTag"),
    TILE("Tile"),
    CHIPOLO("Chipolo"),
    PEBBLEBEE("Pebblebee"),
    GENERIC_TRACKER("Tracker"),
    UNKNOWN("Unknown")
}

enum class TrackingRisk(val label: String) {
    HIGH("HIGH"),
    MEDIUM("MEDIUM"),
    LOW("LOW"),
    NONE("NONE")
}

data class DetectedTracker(
    val address: String,
    val name: String?,
    val type: TrackerType,
    val rssi: Int,
    val firstSeen: Long,
    val lastSeen: Long,
    val seenCount: Int,
    val risk: TrackingRisk,
    val identity: String = address,
    val places: Int = 0,
    val manufacturerData: String? = null
) {
    val displayName: String get() = name ?: type.label
    val signalPercent: Int
        get() = when {
            rssi >= -50 -> 100
            rssi <= -100 -> 0
            else -> 2 * (rssi + 100)
        }
}

data class TrackerScanState(
    val isScanning: Boolean = false,
    val isPaused: Boolean = false,
    val trackers: List<DetectedTracker> = emptyList(),
    val totalDevicesScanned: Int = 0,
    val scanDurationMs: Long = 0,
    val highRiskCount: Int = 0,
    val error: String? = null
)

// ── Tracker Identification ───────────────────────────────────────────────────

data class TrackerMatch(val type: TrackerType, val identity: String)

class TrackerIdentifier {

    companion object {
        private const val APPLE_COMPANY_ID = 0x004C
        private const val FIND_MY_TYPE = 0x12
        private const val CHIPOLO_COMPANY_ID = 0x02E5
        private const val TILE_SERVICE_UUID = "0000feed-0000-1000-8000-00805f9b34fb"
        private const val MIN_PLACES = 3
    }

    /**
     * Find My (Apple type 0x12), Tile's service UUID, or Chipolo's company ID.
     * Other Apple advertisements are left alone. Names are not used.
     */
    fun match(device: BleDevice): TrackerMatch? {
        findMyKey(device.manufacturerData[APPLE_COMPANY_ID])?.let { key ->
            return TrackerMatch(TrackerType.FIND_MY, key)
        }
        val tile = device.serviceUuids.any { it.equals(TILE_SERVICE_UUID, ignoreCase = true) }
        if (tile) {
            val payload = device.serviceData.entries
                .firstOrNull { it.key.contains("feed", ignoreCase = true) }
                ?.value
            val id = payload?.toHex()?.takeIf { it.isNotEmpty() } ?: device.address
            return TrackerMatch(TrackerType.TILE, "tile:$id")
        }
        device.manufacturerData[CHIPOLO_COMPANY_ID]?.let { data ->
            val id = data.toHex().ifEmpty { device.address }
            return TrackerMatch(TrackerType.CHIPOLO, "chipolo:$id")
        }
        if (DeviceSignatures.isFindHub(device)) {
            val id = device.manufacturerData[DeviceSignatures.GOOGLE_COMPANY_ID]?.toHex()?.ifEmpty { device.address }
                ?: device.address
            return TrackerMatch(TrackerType.FIND_HUB, "findhub:$id")
        }
        return null
    }

    fun assessRisk(tracker: DetectedTracker): TrackingRisk {
        if (tracker.type == TrackerType.UNKNOWN) return TrackingRisk.NONE
        return when {
            tracker.places >= MIN_PLACES -> TrackingRisk.HIGH
            tracker.seenCount >= MIN_PLACES -> TrackingRisk.MEDIUM
            else -> TrackingRisk.LOW
        }
    }

    private fun findMyKey(data: ByteArray?): String? {
        if (data == null || data.size < 4) return null
        var index = 0
        while (index + 2 <= data.size) {
            val type = data[index].toInt() and 0xFF
            val length = data[index + 1].toInt() and 0xFF
            if (length == 0 || index + 2 + length > data.size) break
            if (type == FIND_MY_TYPE && length >= 8) {
                val key = data.copyOfRange(index + 3, index + 2 + length)
                if (key.size >= 6) return "findmy:" + key.toHex()
            }
            index += 2 + length
        }
        return null
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
