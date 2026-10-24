package app.radiusrf.features.wardriving.domain

import app.radiusrf.core.util.FrequencyUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AirodumpExporter {

    fun export(records: List<WardrivingRecord>): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder()
        sb.appendLine("BSSID, First time seen, Last time seen, channel, Speed, Privacy, Cipher, Authentication, Power, # beacons, # IV, LAN IP, ID-length, ESSID, Key")
        records.filter { it.isWifi }.groupBy { it.bssid }.values.forEach { group ->
            val best = group.maxBy { it.rssi }
            val cap = best.capabilities.orEmpty()
            val privacy = when {
                cap.contains("WPA3") -> "WPA3"
                cap.contains("WPA2") -> "WPA2"
                cap.contains("WPA") -> "WPA"
                cap.contains("WEP") -> "WEP"
                else -> "OPN"
            }
            val cipher = when {
                cap.contains("CCMP") || cap.contains("AES") -> "CCMP"
                cap.contains("TKIP") -> "TKIP"
                cap.contains("WEP") -> "WEP"
                else -> ""
            }
            val auth = when {
                cap.contains("SAE") -> "SAE"
                cap.contains("EAP") || cap.contains("802.1X") -> "MGT"
                cap.contains("PSK") -> "PSK"
                privacy == "OPN" -> "OPN"
                else -> ""
            }
            val channel = if (best.frequency > 0) {
                val parsed = FrequencyUtils.frequencyToChannel(best.frequency)
                if (parsed < 0) 0 else parsed
            } else 0
            val essid = best.ssid.orEmpty().replace(",", " ")
            sb.appendLine(
                listOf(
                    best.bssid,
                    dateFormat.format(Date(group.minOf { it.timestamp })),
                    dateFormat.format(Date(group.maxOf { it.timestamp })),
                    channel.toString(),
                    "0",
                    privacy,
                    cipher,
                    auth,
                    best.rssi.toString(),
                    group.size.toString(),
                    "0",
                    "0.0.0.0",
                    essid.length.toString(),
                    essid,
                    ""
                ).joinToString(", ")
            )
        }
        sb.appendLine()
        sb.appendLine("Station MAC, First time seen, Last time seen, Power, # packets, BSSID, Probed ESSIDs")
        records.filter { it.isClient }
            .groupBy { it.bssid.lowercase() to it.associatedBssid.orEmpty().lowercase() }
            .values
            .forEach { group ->
                val best = group.maxBy { it.rssi }
                sb.appendLine(
                    listOf(
                        best.bssid,
                        dateFormat.format(Date(group.minOf { it.timestamp })),
                        dateFormat.format(Date(group.maxOf { it.timestamp })),
                        best.rssi.toString(),
                        group.size.toString(),
                        best.associatedBssid.orEmpty(),
                        best.ssid.orEmpty().replace(",", " ")
                    ).joinToString(", ")
                )
            }
        return sb.toString()
    }
}
