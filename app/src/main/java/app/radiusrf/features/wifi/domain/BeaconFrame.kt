package app.radiusrf.features.wifi.domain

/**
 * Turns a beacon or probe response into the same network record the phone scan uses.
 */
object BeaconFrame {
    fun parse(raw: ByteArray, channelHint: Int?, rssi: Int?): WifiAccessPoint? {
        var offset = 0
        if (raw.size >= 8 && raw[0] == 0.toByte() && raw[1] == 0.toByte()) {
            val headerLen = u16(raw, 2)
            if (headerLen in 8 until raw.size) offset = headerLen
        }
        if (raw.size - offset < 36) return null
        val fc = raw[offset].toInt() and 0xFF
        val type = (fc shr 2) and 0x3
        val subtype = (fc shr 4) and 0xF
        if (type != 0 || (subtype != 8 && subtype != 5)) return null

        val bssid = mac(raw, offset + 16)
        val privacy = u16(raw, offset + 34) and 0x0010 != 0
        var ssid = ""
        var channel = channelHint
        var rsn = false
        var sae = false
        var wpa = false
        var index = offset + 36
        while (index + 2 <= raw.size) {
            val id = raw[index].toInt() and 0xFF
            val length = raw[index + 1].toInt() and 0xFF
            if (index + 2 + length > raw.size) break
            val start = index + 2
            when (id) {
                0 -> ssid = runCatching { String(raw, start, length, Charsets.UTF_8) }.getOrDefault("")
                3 -> if (length >= 1) channel = raw[start].toInt() and 0xFF
                48 -> {
                    rsn = true
                    if (rsnHasSae(raw, start, length)) sae = true
                }
                221 -> if (isWpa1(raw, start, length)) wpa = true
            }
            index += 2 + length
        }
        val freq = channelToFrequency(channel ?: return null)
        if (freq == 0) return null
        val capabilities = buildString {
            if (sae) append("[WPA3-SAE-CCMP]")
            if (rsn) append("[WPA2-PSK-CCMP][RSN]")
            if (wpa) append("[WPA-PSK-TKIP]")
            if (!sae && !rsn && !wpa && privacy) append("[WEP]")
            append("[ESS]")
        }
        return WifiAccessPoint(
            ssid = ssid.ifBlank { "<Hidden>" },
            bssid = bssid,
            rssi = rssi ?: -100,
            frequency = freq,
            capabilities = capabilities
        )
    }

    private fun rsnHasSae(raw: ByteArray, start: Int, length: Int): Boolean {
        val end = start + length
        var i = start
        while (i + 4 <= end) {
            if (raw[i] == 0.toByte() && raw[i + 1] == 0x0F.toByte() && raw[i + 2] == 0xAC.toByte() && raw[i + 3] == 8.toByte()) {
                return true
            }
            i++
        }
        return false
    }

    private fun isWpa1(raw: ByteArray, start: Int, length: Int): Boolean =
        length >= 4 &&
            raw[start] == 0.toByte() &&
            raw[start + 1] == 0x50.toByte() &&
            raw[start + 2] == 0xF2.toByte() &&
            raw[start + 3] == 1.toByte()

    private fun channelToFrequency(channel: Int): Int = when (channel) {
        14 -> 2484
        in 1..13 -> 2407 + channel * 5
        in 32..196 -> 5000 + channel * 5
        else -> 0
    }

    private fun u16(raw: ByteArray, index: Int): Int =
        (raw[index].toInt() and 0xFF) or ((raw[index + 1].toInt() and 0xFF) shl 8)

    private fun mac(raw: ByteArray, index: Int): String =
        (0 until 6).joinToString(":") { i -> "%02x".format(raw[index + i].toInt() and 0xFF) }
}
