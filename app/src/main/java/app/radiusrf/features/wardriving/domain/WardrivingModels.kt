package app.radiusrf.features.wardriving.domain

data class WardrivingRecord(
    val bssid: String,
    val ssid: String?,
    val rssi: Int,
    val frequency: Int = 0,
    val capabilities: String? = null,
    val lat: Double,
    val lng: Double,
    val timestamp: Long = System.currentTimeMillis(),
    /** WiGLE type: WIFI, BLE, BT, LTE, NR, GSM, WCDMA, CDMA, TDSCDMA. */
    val radio: String = SurveyRadio.WIFI,
    val altitude: Double = 0.0,
    val accuracy: Double = 0.0
) {
    val isWifi: Boolean get() = radio == SurveyRadio.WIFI
    val isBluetooth: Boolean get() = radio == SurveyRadio.BLE || radio == SurveyRadio.BT
    val isClient: Boolean get() = radio == SurveyRadio.CLIENT
    /** Access point this client was heard with. Set only for [SurveyRadio.CLIENT]. */
    val associatedBssid: String? get() = capabilities?.takeIf { isClient && it.isNotBlank() }
    val isCell: Boolean get() = !isWifi && !isBluetooth && !isClient
}

data class WardrivingSession(
    val id: String,
    val startTime: Long = System.currentTimeMillis(),
    val recordCount: Int = 0,
    val uniqueBssids: Int = 0,
    val isActive: Boolean = false
)

data class WardrivingStats(
    val totalRecords: Int = 0,
    val uniqueSsids: Int = 0,
    val uniqueBssids: Int = 0,
    val openCount: Int = 0,
    val securedCount: Int = 0,
    val sessionDurationMs: Long = 0L
) {
    val openPercent: Float get() = if (openCount + securedCount > 0) (openCount.toFloat() / (openCount + securedCount)) * 100f else 0f
    val formattedDuration: String get() {
        val totalSeconds = sessionDurationMs / 1000
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return "${minutes}m ${seconds}s"
    }
}

data class WardrivingState(
    val session: WardrivingSession? = null,
    val records: List<WardrivingRecord> = emptyList(),
    val isScanning: Boolean = false,
    val isPaused: Boolean = false,
    val exportStatus: String? = null,
    val error: String? = null,
    val otherSurvey: String? = null,
    val stats: WardrivingStats? = null
)

object SurveyRadio {
    const val WIFI = "WIFI"
    const val BLE = "BLE"
    const val BT = "BT"
    const val CLIENT = "CLIENT"
}

/** Which radios a wardriving tool logs. Every mode also stores a GPS fix with each sighting. */
enum class SurveyMode(
    val title: String,
    val wifi: Boolean,
    val bluetooth: Boolean,
    val cellular: Boolean
) {
    WIFI("Wardriving", wifi = true, bluetooth = false, cellular = false),
    PLUS("Wardriving +", wifi = true, bluetooth = true, cellular = false),
    PLUS_PLUS("Wardriving ++", wifi = true, bluetooth = true, cellular = true);

    val radiosLabel: String
        get() = when (this) {
            WIFI -> "GPS + Wi-Fi"
            PLUS -> "GPS + Wi-Fi + Bluetooth"
            PLUS_PLUS -> "GPS + Wi-Fi + Bluetooth + cellular"
        }

    val fileSlug: String
        get() = when (this) {
            WIFI -> "wardrive"
            PLUS -> "wardrive_plus"
            PLUS_PLUS -> "wardrive_plus_plus"
        }

    companion object {
        fun fromName(name: String?): SurveyMode = entries.firstOrNull { it.name == name } ?: WIFI
    }
}

enum class ExportFormat(val label: String, val fileSuffix: String, val mime: String) {
    WIGLE("WiGLE CSV", "wigle.csv", "text/csv"),
    KISMET_CSV("Kismet CSV", "kismet.csv", "text/csv"),
    KISMET_NETXML("Kismet netxml", "kismet.netxml", "text/xml"),
    AIRODUMP("airodump-ng CSV", "airodump.csv", "text/csv"),
    PCAPNG("PCAPNG", "pcapng", "application/octet-stream"),
    OBSERVATIONS("Observations CSV", "observations.csv", "text/csv")
}
