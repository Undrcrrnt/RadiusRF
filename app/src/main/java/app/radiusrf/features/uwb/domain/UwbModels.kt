package app.radiusrf.features.uwb.domain

data class UwbDeviceInfo(
    val isAvailable: Boolean,
    val chipset: String = "Unknown",
    val capabilities: List<String> = emptyList()
)

enum class UwbRole { NONE, CONTROLLER, CONTROLEE }

/**
 * Session parameters that must be exchanged with the peer device out-of-band (there is
 * no NFC/BLE handshake here - the user reads these off screen and types them into the
 * peer's Controlee inputs) before ranging can start.
 */
data class UwbSessionConfig(
    val localAddressHex: String,
    val sessionId: Int,
    val sessionKeyHex: String,
    val channel: Int,
    val preambleIndex: Int
)

data class UwbRangingMeasurement(
    val distanceMeters: Float? = null,
    val azimuthDegrees: Float? = null,
    val elevationDegrees: Float? = null
)

data class UwbState(
    val isHardwareAvailable: Boolean = false,
    val deviceInfo: UwbDeviceInfo? = null,
    val role: UwbRole = UwbRole.NONE,
    val isRanging: Boolean = false,
    val localSession: UwbSessionConfig? = null,
    val measurement: UwbRangingMeasurement? = null,
    val statusMessage: String? = null,
    val error: String? = null,
    // Manually-entered fields, filled in from the peer device's UwbSessionConfig.
    val peerAddressInput: String = "",
    val sessionIdInput: String = "",
    val sessionKeyInput: String = "",
    val channelInput: String = "",
    val preambleInput: String = ""
)
