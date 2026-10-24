package app.radiusrf.features.ftm.domain

data class FtmAccessPoint(
    val ssid: String,
    val bssid: String,
    val frequency: Int,
    val rssi: Int,
    val supportsFtm: Boolean,
    val distanceM: Float? = null,
    val stdDevM: Float? = null,
    val status: String = "Not ranged"
)

data class FtmState(
    val isScanning: Boolean = false,
    val isPaused: Boolean = false,
    val available: Boolean = true,
    val accessPoints: List<FtmAccessPoint> = emptyList(),
    val error: String? = null
)
