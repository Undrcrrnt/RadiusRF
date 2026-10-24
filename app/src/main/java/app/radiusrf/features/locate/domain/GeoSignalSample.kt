package app.radiusrf.features.locate.domain

/** Signal strength measured where this phone was standing. This is not the radio's position. */
data class GeoSignalSample(
    val timeMs: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val rssi: Int
)
