package app.radiusrf.features.gps.domain

data class GpsState(
    val isTracking: Boolean = false,
    val isPaused: Boolean = false,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0,
    val speed: Float = 0f,
    val bearing: Float = 0f,
    val accuracy: Float = 0f,
    val satelliteCount: Int = 0,
    val satellites: List<SatelliteInfo> = emptyList(),
    val nmeaSentences: List<String> = emptyList(),
    val provider: String = "",
    val lastUpdateTime: Long = 0L,
    val error: String? = null
)

data class SatelliteInfo(
    val svid: Int,
    val constellationType: Int,
    val cn0DbHz: Float,
    val elevationDeg: Float,
    val azimuthDeg: Float,
    val usedInFix: Boolean,
    val carrierFrequencyHz: Float? = null
) {
    val constellationName: String
        get() = when (constellationType) {
            1 -> "GPS"
            2 -> "SBAS"
            3 -> "GLONASS"
            4 -> "QZSS"
            5 -> "Beidou"
            6 -> "Galileo"
            7 -> "IRNSS"
            else -> "Unknown"
        }

    val signalQuality: String
        get() = when {
            cn0DbHz >= 35f -> "Strong"
            cn0DbHz >= 25f -> "Good"
            cn0DbHz >= 15f -> "Weak"
            else -> "Very Weak"
        }

    val commonName: String?
        get() = SatelliteNames.lookup(constellationType, svid)

    // A satellite tracked on multiple carrier frequencies (common on dual-frequency chipsets)
    // reports one GnssStatus entry per band, so the same svid can legitimately appear more than
    // once — this label is what tells those apart instead of looking like a duplicate row.
    val frequencyBand: String?
        get() = carrierFrequencyHz?.let { hz ->
            val mhz = hz / 1_000_000f
            when {
                mhz in 1570f..1580f -> "L1"
                mhz in 1600f..1611f -> "G1"
                mhz in 1237f..1255f -> "G2"
                mhz in 1222f..1233f -> "L2"
                mhz in 1263f..1274f -> "E6"
                mhz in 1202f..1212f -> "E5b"
                mhz in 1171f..1181f -> "L5"
                else -> "%.0fMHz".format(mhz)
            }
        }
}
