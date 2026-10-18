package com.emptyset.detector.detect

/**
 * 802.11 management-frame parser used only to recognize deauthentication
 * and disassociation. Receive-only: this module never builds or transmits frames.
 */
object Ieee80211 {
    const val TYPE_MGMT = 0
    const val SUBTYPE_DISASSOC = 10
    const val SUBTYPE_DEAUTH = 12

    data class MgmtEvent(
        val subtype: Int,
        val channelHint: Int?,
        val rssiDbm: Int?,
        val addr1: String,
        val addr2: String,
        val addr3: String,
        val reasonCode: Int?,
        val rawHex: String
    ) {
        val kind: String
            get() = if (subtype == SUBTYPE_DEAUTH) "deauth" else "disassoc"

        val reasonName: String
            get() = reasonLabel(reasonCode)
    }

    fun parse(raw: ByteArray, channelHint: Int? = null, rssiDbm: Int? = null): MgmtEvent? {
        if (raw.size < 24) return null
        var offset = 0
        if (looksLikeRadiotap(raw)) {
            val headerLen = u16(raw, 2)
            if (headerLen < 8 || headerLen >= raw.size) return null
            offset = headerLen
        }
        if (raw.size - offset < 24) return null
        val fc = raw[offset].toInt() and 0xFF
        val type = (fc shr 2) and 0x3
        val subtype = (fc shr 4) and 0xF
        if (type != TYPE_MGMT) return null
        if (subtype != SUBTYPE_DEAUTH && subtype != SUBTYPE_DISASSOC) return null
        if ((raw[offset].toInt() and 0x03) != 0) return null
        val reason = if (raw.size - offset >= 26) u16(raw, offset + 24) else null
        if (reason == null || reason == 0 || reason > 64) return null
        return MgmtEvent(
            subtype = subtype,
            channelHint = channelHint,
            rssiDbm = rssiDbm,
            addr1 = mac(raw, offset + 4),
            addr2 = mac(raw, offset + 10),
            addr3 = mac(raw, offset + 16),
            reasonCode = reason,
            rawHex = raw.joinToString("") { "%02x".format(it) }.take(512)
        )
    }

    fun reasonLabel(code: Int?): String = when (code) {
        null -> "unknown"
        1 -> "unspecified"
        2 -> "previous authentication invalid"
        3 -> "leaving BSS/ESS"
        4 -> "inactivity"
        5 -> "AP cannot handle STA"
        6 -> "class 2 from nonauthenticated STA"
        7 -> "class 3 from nonassociated STA"
        8 -> "STA leaving BSS"
        9 -> "not authenticated"
        14 -> "MIC failure"
        15 -> "4-way handshake timeout"
        else -> "reason $code"
    }

    fun bandOf(channel: Int?, band: WifiBand? = null): String =
        band?.label ?: WifiBand.infer(channel).label

    private fun looksLikeRadiotap(raw: ByteArray): Boolean =
        raw.size >= 8 && raw[0] == 0.toByte() && raw[1] == 0.toByte()

    private fun u16(raw: ByteArray, index: Int): Int =
        (raw[index].toInt() and 0xFF) or ((raw[index + 1].toInt() and 0xFF) shl 8)

    private fun mac(raw: ByteArray, index: Int): String =
        (0 until 6).joinToString(":") { i ->
            "%02x".format(raw[index + i].toInt() and 0xFF)
        }
}
