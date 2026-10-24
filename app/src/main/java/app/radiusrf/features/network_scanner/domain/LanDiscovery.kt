package app.radiusrf.features.network_scanner.domain

data class LanService(
    val name: String,
    val kind: String,
    val protocol: String,
    val host: String?,
    val ip: String?,
    val port: Int?,
    val detail: String
)

object LanServiceClassifier {
    fun kind(type: String, name: String, detail: String = ""): String {
        val blob = "$type $name $detail".lowercase()
        return when {
            listOf("chromecast", "googlecast", "androidtv", "_androidtvremote").any { it in blob } -> "Cast / TV"
            listOf("airplay", "raop", "apple-tv").any { it in blob } -> "Apple TV"
            listOf("printer", "_ipp", "_pdl", "jetdirect").any { it in blob } -> "Printer"
            listOf("rtsp", "onvif", "ipcamera", "camera", "wyze", "ring").any { it in blob } -> "Camera"
            listOf("homekit", "hap", "hue", "smartthings").any { it in blob } -> "Smart home"
            listOf("spotify", "_spotify").any { it in blob } -> "Speaker"
            listOf("ssh", "_sftp").any { it in blob } -> "Computer"
            listOf("upnp", "ssdp", "internetgateway").any { it in blob } -> "Gateway"
            else -> "Announced"
        }
    }
}

object SsdpParser {
    fun parse(packet: String): LanService? {
        if (packet.isBlank()) return null
        val headers = packet.lineSequence()
            .mapNotNull { line ->
                val split = line.indexOf(':')
                if (split <= 0) null else line.substring(0, split).trim().lowercase() to line.substring(split + 1).trim()
            }
            .toMap()
        val usn = headers["usn"] ?: headers["nt"] ?: headers["st"] ?: return null
        val location = headers["location"]
        val server = headers["server"]
        val host = location?.let { hostOf(it) }
        val name = headers["st"] ?: usn.substringAfterLast(':').ifBlank { usn }
        return LanService(
            name = name,
            kind = LanServiceClassifier.kind(usn, name, server.orEmpty()),
            protocol = "SSDP",
            host = host,
            ip = host?.takeIf { it[0].isDigit() },
            port = location?.let { portOf(it) },
            detail = listOfNotNull(server, location).joinToString(" · ")
        )
    }

    private fun hostOf(location: String): String? {
        val after = location.substringAfter("://", missingDelimiterValue = "")
        if (after.isEmpty()) return null
        return after.substringBefore('/').substringBefore(':')
    }

    private fun portOf(location: String): Int? {
        val after = location.substringAfter("://", missingDelimiterValue = "")
        val hostPort = after.substringBefore('/')
        return hostPort.substringAfter(':', missingDelimiterValue = "").toIntOrNull()
    }
}
