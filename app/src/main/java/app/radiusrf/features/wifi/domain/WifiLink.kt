package app.radiusrf.features.wifi.domain

/**
 * Reads a client and the access point it is exchanging frames with.
 * Association frames and infrastructure data frames carry that pair.
 * Beacons do not.
 */
object WifiLink {
    data class Heard(val client: String, val accessPoint: String)

    fun parse(raw: ByteArray): Heard? {
        var offset = 0
        if (raw.size >= 8 && raw[0] == 0.toByte() && raw[1] == 0.toByte()) {
            val headerLen = u16(raw, 2)
            if (headerLen in 8 until raw.size) offset = headerLen
        }
        if (raw.size - offset < 24) return null
        val fc0 = raw[offset].toInt() and 0xFF
        val fc1 = raw[offset + 1].toInt() and 0xFF
        val type = (fc0 shr 2) and 0x3
        val subtype = (fc0 shr 4) and 0xF
        val toDs = fc1 and 0x01 != 0
        val fromDs = fc1 and 0x02 != 0
        val addr1 = mac(raw, offset + 4)
        val addr2 = mac(raw, offset + 10)
        val heard = when {
            type == 0 && subtype == 0 -> Heard(addr2, addr1)
            type == 0 && subtype == 1 -> Heard(addr1, addr2)
            type == 2 && toDs && !fromDs -> Heard(addr2, addr1)
            type == 2 && !toDs && fromDs -> Heard(addr1, addr2)
            else -> null
        } ?: return null
        if (!usable(heard.client) || !usable(heard.accessPoint) || heard.client == heard.accessPoint) return null
        return heard
    }

    private fun usable(mac: String): Boolean {
        if (mac == "00:00:00:00:00:00" || mac == "FF:FF:FF:FF:FF:FF") return false
        val first = mac.take(2).toIntOrNull(16) ?: return false
        return first and 0x01 == 0
    }

    private fun u16(raw: ByteArray, index: Int): Int =
        (raw[index].toInt() and 0xFF) or ((raw[index + 1].toInt() and 0xFF) shl 8)

    private fun mac(raw: ByteArray, index: Int): String =
        (0 until 6).joinToString(":") { "%02X".format(raw[index + it].toInt() and 0xFF) }
}
