package app.radiusrf.features.signatures.domain

import app.radiusrf.features.ble.domain.BleDevice

enum class SignatureKind(val label: String) {
    FIND_HUB("Find Hub"),
    GLASSES("Camera glasses"),
    ACTION_CAM("Action camera"),
    SKIMMER_SERIAL("Serial module"),
    CAMERA_SSID("Camera hotspot")
}

data class SignatureHit(
    val id: String,
    val label: String,
    val kind: SignatureKind,
    val source: String,
    val address: String,
    val rssi: Int,
    val detail: String,
    val lastSeen: Long = System.currentTimeMillis()
)

/**
 * Receive-only names, UUIDs, and company IDs for radios that often sit near
 * a person or a payment terminal. Matches advertisements the phone already hears.
 */
object DeviceSignatures {
    const val GOOGLE_COMPANY_ID = 0x00E0
    const val FAST_PAIR_UUID = "fe2c"
    const val MESHTASTIC_UUID = "6ba1b218-15a8-461f-9fa8-5dcae273eafd"

    private val findHubNames = Regex("(?i)(find\\s*hub|find\\s*my\\s*device)")
    private val glassesNames = Regex("(?i)(ray-?ban|meta\\s*(view|glasses)|spectacles|vuzix|envision\\s*glasses)")
    private val actionCamNames = Regex("(?i)(gopro|hero\\s*\\d|insta360|osmo\\s*action|v380|ipc[-_]?\\d)")
    private val serialNames = Regex("(?i)(hc-0[5689]|jdy-\\d|hm-10|bt04|spp-ca|barrot|ml-07)")
    private val cameraSsids = Regex("(?i)^(gopro|gp\\d|hero|v380|ipc[-_]|cam[-_]|dgi-|insta360)")
    private val glassesSsids = Regex("(?i)(ray-?ban|meta[-_ ]glasses|spectacles)")

    fun matchBle(device: BleDevice): List<SignatureHit> = buildList {
        if (isFindHub(device)) {
            add(hit(device, SignatureKind.FIND_HUB, device.name ?: "Find Hub", "Google Find Hub / Find My Device advertisement"))
        }
        val name = device.name.orEmpty()
        if (glassesNames.containsMatchIn(name)) {
            add(hit(device, SignatureKind.GLASSES, name, "Wearable camera / smart-glasses name"))
        }
        if (actionCamNames.containsMatchIn(name)) {
            add(hit(device, SignatureKind.ACTION_CAM, name, "Action-camera or cheap IP-cam setup name"))
        }
        if (serialNames.containsMatchIn(name)) {
            add(hit(device, SignatureKind.SKIMMER_SERIAL, name, "Common BLE/classic serial module name"))
        }
    }

    fun matchWifi(ssid: String, bssid: String, rssi: Int): SignatureHit? {
        val trimmed = ssid.trim()
        if (trimmed.isEmpty()) return null
        val kind = when {
            glassesSsids.containsMatchIn(trimmed) -> SignatureKind.GLASSES
            cameraSsids.containsMatchIn(trimmed) -> SignatureKind.CAMERA_SSID
            actionCamNames.containsMatchIn(trimmed) -> SignatureKind.ACTION_CAM
            else -> return null
        }
        val label = when (kind) {
            SignatureKind.GLASSES -> "Glasses hotspot"
            SignatureKind.CAMERA_SSID, SignatureKind.ACTION_CAM -> "Camera hotspot"
            else -> kind.label
        }
        return SignatureHit(
            id = "wifi:$bssid",
            label = trimmed,
            kind = kind,
            source = "WIFI",
            address = bssid,
            rssi = rssi,
            detail = "$label · $trimmed"
        )
    }

    fun isFindHub(device: BleDevice): Boolean {
        val name = device.name.orEmpty()
        if (findHubNames.containsMatchIn(name)) return true
        val unnamed = device.name.isNullOrBlank()
        val fastPair = device.serviceUuids.any { it.contains(FAST_PAIR_UUID, ignoreCase = true) } ||
            device.serviceData.keys.any { it.contains(FAST_PAIR_UUID, ignoreCase = true) }
        val google = device.manufacturerData[GOOGLE_COMPANY_ID]
        return unnamed && (fastPair || (google != null && google.size >= 8))
    }

    fun isMeshtastic(device: BleDevice): Boolean {
        val name = device.name.orEmpty()
        if (name.contains("meshtastic", ignoreCase = true)) return true
        return device.serviceUuids.any { it.equals(MESHTASTIC_UUID, ignoreCase = true) }
    }

    private fun hit(device: BleDevice, kind: SignatureKind, label: String, detail: String) = SignatureHit(
        id = "${kind.name}:${device.address}",
        label = label,
        kind = kind,
        source = "BLE",
        address = device.address,
        rssi = device.rssi,
        detail = detail,
        lastSeen = device.lastSeen
    )
}
