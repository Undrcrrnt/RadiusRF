package app.radiusrf.features.wardriving.domain

import app.radiusrf.core.util.FrequencyUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object KismetExporter {

    fun csv(records: List<WardrivingRecord>): String {
        val sb = StringBuilder()
        sb.appendLine("BSSID,NetType,ESSID,Channel,Encryption,MaxRate,BestSignal,GPSBestLat,GPSBestLon,GPSBestAlt,FirstTime,LastTime")
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        grouped(records).forEach { group ->
            val best = group.maxBy { it.rssi }
            val channel = displayChannel(best)
            sb.appendLine(
                listOf(
                    best.bssid,
                    netType(best),
                    best.ssid.orEmpty(),
                    channel,
                    encryptionLabel(best),
                    "0",
                    best.rssi.toString(),
                    best.lat.toString(),
                    best.lng.toString(),
                    plainNumber(best.altitude),
                    dateFormat.format(Date(group.minOf { it.timestamp })),
                    dateFormat.format(Date(group.maxOf { it.timestamp }))
                ).joinToString(",") { csvField(it) }
            )
        }
        return sb.toString()
    }

    fun netxml(records: List<WardrivingRecord>): String {
        val dateFormat = SimpleDateFormat("EEE MMM dd HH:mm:ss yyyy", Locale.US)
        val start = records.minOfOrNull { it.timestamp } ?: System.currentTimeMillis()
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<!DOCTYPE detection-run SYSTEM "http://kismetwireless.net/kismet-3.1.0.dtd">""")
        sb.appendLine("""<detection-run kismet-version="RadiusRf" start-time="${dateFormat.format(Date(start))}">""")
        grouped(records).forEachIndexed { index, group ->
            val best = group.maxBy { it.rssi }
            val wifi = best.isWifi
            val type = if (wifi) "infrastructure" else "bt"
            val cloaked = best.ssid.isNullOrBlank()
            val wep = best.capabilities.orEmpty().contains("WEP") && !best.capabilities.orEmpty().contains("WPA")
            sb.appendLine("""  <wireless-network number="${index + 1}" type="$type" wep="$wep" cloaked="$cloaked">""")
            sb.appendLine("    <SSID>")
            if (wifi) sb.appendLine("      <type>Beacon</type>")
            encryptionTags(best).forEach { sb.appendLine("      <encryption>${xmlText(it)}</encryption>") }
            sb.appendLine("""      <essid cloaked="$cloaked">${xmlText(best.ssid.orEmpty())}</essid>""")
            sb.appendLine("    </SSID>")
            sb.appendLine("    <BSSID>${xmlText(best.bssid)}</BSSID>")
            val channel = displayChannel(best)
            if (channel.isNotEmpty()) sb.appendLine("    <channel>$channel</channel>")
            if (wifi && best.frequency > 0) sb.appendLine("    <freqmhz>${best.frequency}</freqmhz>")
            sb.appendLine("    <snr-info>")
            sb.appendLine("      <last_signal_dbm>${best.rssi}</last_signal_dbm>")
            sb.appendLine("      <min_signal_dbm>${group.minOf { it.rssi }}</min_signal_dbm>")
            sb.appendLine("      <max_signal_dbm>${group.maxOf { it.rssi }}</max_signal_dbm>")
            sb.appendLine("    </snr-info>")
            val lats = group.map { it.lat }
            val lngs = group.map { it.lng }
            sb.appendLine("    <gps-info>")
            sb.appendLine("      <min-lat>${lats.min()}</min-lat>")
            sb.appendLine("      <min-lon>${lngs.min()}</min-lon>")
            sb.appendLine("      <max-lat>${lats.max()}</max-lat>")
            sb.appendLine("      <max-lon>${lngs.max()}</max-lon>")
            sb.appendLine("      <peak-lat>${best.lat}</peak-lat>")
            sb.appendLine("      <peak-lon>${best.lng}</peak-lon>")
            sb.appendLine("      <avg-lat>${lats.average()}</avg-lat>")
            sb.appendLine("      <avg-lon>${lngs.average()}</avg-lon>")
            sb.appendLine("      <avg-alt>${group.map { it.altitude }.average()}</avg-alt>")
            sb.appendLine("    </gps-info>")
            if (wifi) clientsOf(records, best.bssid).forEachIndexed { clientIndex, clientGroup ->
                val client = clientGroup.maxBy { it.rssi }
                sb.appendLine("""    <wireless-client number="${clientIndex + 1}" type="established">""")
                sb.appendLine("      <client-mac>${xmlText(client.bssid)}</client-mac>")
                sb.appendLine("      <snr-info>")
                sb.appendLine("        <last_signal_dbm>${client.rssi}</last_signal_dbm>")
                sb.appendLine("        <min_signal_dbm>${clientGroup.minOf { it.rssi }}</min_signal_dbm>")
                sb.appendLine("        <max_signal_dbm>${clientGroup.maxOf { it.rssi }}</max_signal_dbm>")
                sb.appendLine("      </snr-info>")
                sb.appendLine("    </wireless-client>")
            }
            sb.appendLine("  </wireless-network>")
        }
        val known = records.filter { it.isWifi }.map { it.bssid.lowercase() }.toSet()
        clients(records).filter { it.associatedBssid.orEmpty().lowercase() !in known }
            .groupBy { it.associatedBssid.orEmpty().lowercase() }
            .values
            .forEachIndexed { index, group ->
                val number = grouped(records).size + index + 1
                val ap = group.first().associatedBssid.orEmpty()
                sb.appendLine("""  <wireless-network number="$number" type="infrastructure" wep="false" cloaked="true">""")
                sb.appendLine("    <SSID>")
                sb.appendLine("""      <essid cloaked="true">${xmlText(group.first().ssid.orEmpty())}</essid>""")
                sb.appendLine("    </SSID>")
                sb.appendLine("    <BSSID>${xmlText(ap)}</BSSID>")
                group.groupBy { it.bssid.lowercase() }.values.forEachIndexed { clientIndex, clientGroup ->
                    val client = clientGroup.maxBy { it.rssi }
                    sb.appendLine("""    <wireless-client number="${clientIndex + 1}" type="established">""")
                    sb.appendLine("      <client-mac>${xmlText(client.bssid)}</client-mac>")
                    sb.appendLine("      <snr-info>")
                    sb.appendLine("        <last_signal_dbm>${client.rssi}</last_signal_dbm>")
                    sb.appendLine("      </snr-info>")
                    sb.appendLine("    </wireless-client>")
                }
                sb.appendLine("  </wireless-network>")
            }
        sb.appendLine("</detection-run>")
        return sb.toString()
    }

    private fun grouped(records: List<WardrivingRecord>): List<List<WardrivingRecord>> =
        records.filter { !it.isClient }.groupBy { it.radio to it.bssid }.values.toList()

    private fun clients(records: List<WardrivingRecord>): List<WardrivingRecord> = records.filter { it.isClient }

    private fun clientsOf(records: List<WardrivingRecord>, bssid: String): List<List<WardrivingRecord>> =
        clients(records).filter { it.associatedBssid.equals(bssid, ignoreCase = true) }.groupBy { it.bssid.lowercase() }.values.toList()

    private fun netType(record: WardrivingRecord): String = when {
        record.isWifi -> "infrastructure"
        record.isBluetooth -> "bt"
        else -> record.radio
    }

    private fun displayChannel(record: WardrivingRecord): String = when {
        record.isWifi && record.frequency > 0 -> {
            val parsed = FrequencyUtils.frequencyToChannel(record.frequency)
            if (parsed < 0) "0" else parsed.toString()
        }
        record.isCell && record.frequency > 0 -> record.frequency.toString()
        else -> ""
    }

    private fun encryptionLabel(record: WardrivingRecord): String = when {
        record.isWifi -> encryptionTags(record).joinToString("+").ifBlank { "None" }
        else -> record.capabilities.orEmpty()
    }

    private fun encryptionTags(record: WardrivingRecord): List<String> {
        if (!record.isWifi) return listOf("None")
        val cap = record.capabilities.orEmpty()
        if (cap.isBlank() || cap == "[ESS]") return listOf("None")
        return buildList {
            if (cap.contains("WEP") && !cap.contains("WPA")) add("WEP")
            if (cap.contains("PSK")) add("WPA+PSK")
            if (cap.contains("SAE")) add("WPA+SAE")
            if (cap.contains("EAP") || cap.contains("802.1X")) add("WPA+802.1X")
            if (cap.contains("CCMP") || cap.contains("AES")) add("WPA+AES-CCM")
            if (cap.contains("TKIP")) add("WPA+TKIP")
            if (isEmpty() && cap.contains("WPA")) add("WPA")
            if (isEmpty()) add("None")
        }
    }
}
