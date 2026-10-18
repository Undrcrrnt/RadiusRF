package app.radiusrf.features.wifi.domain

import app.radiusrf.core.util.FrequencyUtils
import app.radiusrf.core.util.SecurityType
import app.radiusrf.core.util.WifiBand

data class WifiAccessPoint(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val frequency: Int,
    val capabilities: String,
    val channelWidth: Int = 0,
    /** When this reading was heard. Zero means the caller did not record a time. */
    val heardAt: Long = 0L,
    val clients: List<WifiClient> = emptyList()
) {
    val channel: Int get() = FrequencyUtils.frequencyToChannel(frequency)
    val band: WifiBand get() = FrequencyUtils.frequencyToBand(frequency)
    val security: SecurityType get() = SecurityType.fromCapabilities(capabilities)
    val signalPercent: Int get() = FrequencyUtils.signalToPercent(rssi)
}

data class WifiClient(
    val mac: String,
    val rssi: Int,
    val role: String
)

data class ChannelScore(
    val channel: Int,
    val band: WifiBand,
    val apCount: Int,
    val avgRssi: Int
)
