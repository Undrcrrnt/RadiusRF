package app.radiusrf.features.wardriving.domain

/**
 * Latest sighting of each address for the screen, plus counters that only grow
 * when a new address appears. The saved route stays in the database.
 */
class SurveyBoard {

    private val lock = Any()
    private val seenWifi = HashMap<String, Boolean>()
    private val seenSsids = HashSet<String>()
    private val seenBle = HashSet<String>()
    private val seenClassic = HashSet<String>()
    private val seenClients = HashSet<String>()
    private val seenCells = HashSet<String>()
    private val latestNetworks = accessOrderMap<WardrivingRecord>(NETWORK_CAP)
    private val latestClients = accessOrderMap<WardrivingRecord>(CLIENT_CAP)
    private var written = 0

    fun clear() {
        synchronized(lock) {
            seenWifi.clear()
            seenSsids.clear()
            seenBle.clear()
            seenClassic.clear()
            seenClients.clear()
            seenCells.clear()
            latestNetworks.clear()
            latestClients.clear()
            written = 0
        }
    }

    fun note(record: WardrivingRecord) {
        synchronized(lock) { noteLocked(record) }
    }

    fun addWritten(count: Int) {
        if (count <= 0) return
        synchronized(lock) { written += count }
    }

    fun snapshot(startTime: Long, now: Long = System.currentTimeMillis()): SurveyLive {
        synchronized(lock) {
            val networks = latestNetworks.values
                .sortedWith(compareByDescending<WardrivingRecord> { it.timestamp }.thenByDescending { it.rssi })
                .take(DISPLAY_NETWORKS)
            val clients = latestClients.values
                .sortedByDescending { it.rssi }
                .take(DISPLAY_CLIENTS)
            var open = 0
            var secured = 0
            seenWifi.values.forEach { isOpen -> if (isOpen) open++ else secured++ }
            return SurveyLive(
                stats = WardrivingStats(
                    totalRecords = written,
                    uniqueSsids = seenSsids.size,
                    uniqueBssids = seenWifi.size + seenBle.size + seenClassic.size + seenClients.size + seenCells.size,
                    openCount = open,
                    securedCount = secured,
                    sessionDurationMs = if (startTime > 0L) (now - startTime).coerceAtLeast(0L) else 0L
                ),
                networks = networks,
                clients = clients,
                wifiNetworks = seenWifi.size,
                bluetoothDevices = seenBle.size + seenClassic.size,
                cells = seenCells.size,
                clientDevices = seenClients.size
            )
        }
    }

    private fun noteLocked(record: WardrivingRecord) {
        val mac = record.bssid.lowercase()
        when {
            record.isClient -> {
                val key = "$mac|${record.associatedBssid.orEmpty().lowercase()}"
                seenClients.add(key)
                latestClients[key] = record
            }
            record.isWifi -> {
                seenWifi[mac] = isOpenNetwork(record.capabilities)
                record.ssid?.takeIf { it.isNotBlank() }?.let { seenSsids.add(it) }
                latestNetworks["${record.radio}:$mac"] = record
            }
            record.isBluetooth -> {
                if (record.radio == SurveyRadio.BLE) seenBle.add(mac) else seenClassic.add(mac)
                latestNetworks["${record.radio}:$mac"] = record
            }
            record.isCell -> {
                seenCells.add(mac)
                latestNetworks["${record.radio}:$mac"] = record
            }
        }
    }

    companion object {
        const val DISPLAY_NETWORKS = 50
        const val DISPLAY_CLIENTS = 40
        private const val NETWORK_CAP = 300
        private const val CLIENT_CAP = 200

        fun isOpenNetwork(capabilities: String?): Boolean {
            val value = capabilities ?: ""
            return value.isEmpty() || value == "[ESS]"
        }
    }
}

data class SurveyLive(
    val stats: WardrivingStats = WardrivingStats(),
    val networks: List<WardrivingRecord> = emptyList(),
    val clients: List<WardrivingRecord> = emptyList(),
    val wifiNetworks: Int = 0,
    val bluetoothDevices: Int = 0,
    val cells: Int = 0,
    val clientDevices: Int = 0
) {
    val isEmpty: Boolean
        get() = stats.totalRecords == 0 &&
            networks.isEmpty() &&
            clients.isEmpty() &&
            wifiNetworks == 0 &&
            bluetoothDevices == 0 &&
            cells == 0 &&
            clientDevices == 0
}

private fun <T> accessOrderMap(cap: Int): LinkedHashMap<String, T> =
    object : LinkedHashMap<String, T>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?) = size > cap
    }
