package app.radiusrf.features.bluetooth_tracker.domain

import app.radiusrf.features.ble.domain.BleDevice

// ── Data Models ──────────────────────────────────────────────────────────────

enum class TrackerType(val label: String) {
    FIND_MY("Find My"),
    FIND_HUB("Find Hub"),
    AIRTAG("AirTag"),
    SMARTTAG("SmartTag"),
    TILE("Tile"),
    CHIPOLO("Chipolo"),
    AMAZON("Amazon"),
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

enum class OwnerState { WITH_OWNER, SEPARATED, UNKNOWN }

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
    val manufacturerData: String? = null,
    val owner: OwnerState = OwnerState.UNKNOWN
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
    val backgroundWatching: Boolean = false,
    val savedPlaces: List<TrackerTrail> = emptyList(),
    val error: String? = null
)

// ── Tracker Identification ───────────────────────────────────────────────────

data class TrackerMatch(
    val type: TrackerType,
    val identity: String,
    val owner: OwnerState = OwnerState.UNKNOWN
)

class TrackerIdentifier {

    companion object {
        private const val APPLE_COMPANY_ID = 0x004C
        private const val FIND_MY_TYPE = 0x12
        private const val CHIPOLO_COMPANY_ID = 0x02E5
        private const val MIN_PLACES = 3
    }

    /**
     * Known tracker advertisements only. Names are not used, and a Samsung or
     * Fast Pair radio is not a tracker unless the tracker payload is present.
     */
    fun match(device: BleDevice): TrackerMatch? {
        findMy(device.manufacturerData[APPLE_COMPANY_ID])?.let { found ->
            return TrackerMatch(TrackerType.FIND_MY, found.key, found.owner)
        }
        smartTag(device)?.let { return it }
        findHub(device)?.let { return it }
        unwanted(device)?.let { return it }
        tile(device)?.let { return it }
        device.manufacturerData[CHIPOLO_COMPANY_ID]?.let { data ->
            val id = data.toHex().ifEmpty { device.address }
            return TrackerMatch(TrackerType.CHIPOLO, "chipolo:$id")
        }
        return null
    }

    /** Samsung SmartTag service FD5A (registered) or FD59 (not registered). */
    private fun smartTag(device: BleDevice): TrackerMatch? {
        val data = device.servicePayload("fd5a") ?: device.servicePayload("fd59") ?: return null
        if (data.size < 12) return null
        val state = data[0].toInt() and 0x07
        val owner = when (state) {
            1, 2, 3 -> OwnerState.SEPARATED
            4, 5, 6 -> OwnerState.WITH_OWNER
            else -> OwnerState.UNKNOWN
        }
        return TrackerMatch(TrackerType.SMARTTAG, "smarttag:" + data.copyOfRange(4, 12).toHex(), owner)
    }

    /**
     * Google Find Hub location advertisement, service FEAA.
     * 0x41 means unwanted-tracking mode is on. 0x40 means it is off, which is
     * not the same as the owner standing next to the tag. Eddystone also uses
     * FEAA and starts with a different frame type, so those are left alone.
     */
    private fun findHub(device: BleDevice): TrackerMatch? {
        val data = device.servicePayload("feaa") ?: return null
        if (data.size < 21) return null
        val marker = data[0].toInt() and 0xFF
        if (marker != 0x40 && marker != 0x41) return null
        val end = if (data.size == 22 || data.size == 34) data.size - 1 else data.size
        val eid = data.copyOfRange(1, end)
        if (eid.size < 20) return null
        val owner = if (marker == 0x41) OwnerState.SEPARATED else OwnerState.UNKNOWN
        return TrackerMatch(TrackerType.FIND_HUB, "findhub:" + eid.toHex(), owner)
    }

    /**
     * Shared unwanted-tracker advertisement, service FCB2.
     * The first service-data byte is the network. The next byte's low bit is
     * set while the owner is nearby.
     */
    private fun unwanted(device: BleDevice): TrackerMatch? {
        val data = device.servicePayload("fcb2") ?: return null
        if (data.size < 2) return null
        val network = data[0].toInt() and 0xFF
        val type = when (network) {
            0x01 -> TrackerType.FIND_MY
            0x02 -> TrackerType.FIND_HUB
            0x03 -> TrackerType.SMARTTAG
            0x04 -> TrackerType.AMAZON
            else -> TrackerType.GENERIC_TRACKER
        }
        val nearOwner = data[1].toInt() and 0x01 != 0
        val body = if (data.size > 2) data.copyOfRange(2, data.size) else byteArrayOf(network.toByte())
        return TrackerMatch(
            type,
            "dult:%02x:%s".format(network, body.toHex()),
            if (nearOwner) OwnerState.WITH_OWNER else OwnerState.SEPARATED
        )
    }

    private fun tile(device: BleDevice): TrackerMatch? {
        val payload = device.servicePayload("feed") ?: device.servicePayload("feed0001")
        if (payload == null && !device.advertises("feed") && !device.advertises("feed0001")) return null
        val id = payload?.toHex()?.takeIf { it.isNotEmpty() } ?: device.address
        return TrackerMatch(TrackerType.TILE, "tile:$id")
    }

    private fun BleDevice.servicePayload(shortId: String): ByteArray? =
        serviceData.entries.firstOrNull { uuidMatches(it.key, shortId) }?.value

    private fun BleDevice.advertises(shortId: String): Boolean =
        serviceUuids.any { uuidMatches(it, shortId) } || serviceData.keys.any { uuidMatches(it, shortId) }

    private fun uuidMatches(uuid: String, shortId: String): Boolean {
        val text = uuid.lowercase()
        val full = if (shortId.length == 4) {
            "0000$shortId-0000-1000-8000-00805f9b34fb"
        } else {
            "$shortId-0000-1000-8000-00805f9b34fb"
        }
        return text.contains(full)
    }

    fun assessRisk(tracker: DetectedTracker): TrackingRisk {
        if (tracker.type == TrackerType.UNKNOWN) return TrackingRisk.NONE
        if (tracker.owner == OwnerState.WITH_OWNER) return TrackingRisk.LOW
        return when {
            tracker.places >= MIN_PLACES -> TrackingRisk.HIGH
            tracker.seenCount >= MIN_PLACES -> TrackingRisk.MEDIUM
            else -> TrackingRisk.LOW
        }
    }

    private data class FindMy(val key: String, val owner: OwnerState)

    /**
     * Find My status bit 2 is set once the tag has been away from its owner.
     * A clear bit is a tag that is still with someone, so it does not alert.
     */
    private fun findMy(data: ByteArray?): FindMy? {
        if (data == null || data.size < 4) return null
        var index = 0
        while (index + 2 <= data.size) {
            val type = data[index].toInt() and 0xFF
            val length = data[index + 1].toInt() and 0xFF
            if (length == 0 || index + 2 + length > data.size) break
            if (type == FIND_MY_TYPE && length >= 8) {
                val status = data[index + 2].toInt() and 0xFF
                val key = data.copyOfRange(index + 3, index + 2 + length)
                if (key.size >= 6) {
                    val owner = if (status and 0x04 != 0) OwnerState.SEPARATED else OwnerState.WITH_OWNER
                    return FindMy("findmy:" + key.toHex(), owner)
                }
            }
            index += 2 + length
        }
        return null
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
