package app.radiusrf.features.sdr.domain

data class AisVessel(
    val mmsi: String,
    val type: Int,
    val name: String?,
    val lat: Double?,
    val lon: Double?,
    val sog: Double?,
    val cog: Double?,
    val raw: String
)

/** AIS A/B position and static-name parser after NRZI + HDLC destuff. */
object AisDecoder {
    const val CHANNEL_A = 161_975_000L
    const val CHANNEL_B = 162_025_000L
    private const val CRC_POLY = 0x1021

    fun demod(iq: ByteArray, n: Int, signed: Boolean, sampleRate: Int): List<BooleanArray> {
        if (n < 128 || sampleRate < 19_200) return emptyList()
        val samples = n / 2
        val disc = FloatArray(samples)
        var prevI = 0f
        var prevQ = 0f
        var i = 0
        var s = 0
        while (i + 1 < n) {
            val iv = sample(iq[i], signed)
            val qv = sample(iq[i + 1], signed)
            disc[s] = prevI * qv - prevQ * iv
            prevI = iv
            prevQ = qv
            i += 2
            s++
        }
        val spb = (sampleRate / 9600).coerceAtLeast(1)
        val bits = BooleanArray(samples / spb)
        var b = 0
        var index = 0
        while (index + spb <= samples && b < bits.size) {
            var acc = 0f
            var k = 0
            while (k < spb) {
                acc += disc[index + k]
                k++
            }
            bits[b] = acc >= 0f
            b++
            index += spb
        }
        return hdlcFrames(nrzi(bits.copyOf(b)))
    }

    fun parse(bits: BooleanArray): AisVessel? {
        if (bits.size < 168) return null
        val type = field(bits, 0, 6)
        val mmsi = field(bits, 8, 30).toString().padStart(9, '0')
        return when (type) {
            1, 2, 3, 18, 19 -> AisVessel(
                mmsi = mmsi,
                type = type,
                name = if (type == 19) text(bits, 143, 20) else null,
                lat = lat(field(bits, 89, 27)),
                lon = lon(field(bits, 61, 28)),
                sog = field(bits, 50, 10) / 10.0,
                cog = field(bits, 116, 12) / 10.0,
                raw = "AIVDM type $type"
            )
            5, 24 -> AisVessel(
                mmsi = mmsi,
                type = type,
                name = text(bits, if (type == 5) 112 else 40, 20),
                lat = null,
                lon = null,
                sog = null,
                cog = null,
                raw = "AIVDM type $type"
            )
            else -> AisVessel(mmsi, type, null, null, null, null, null, "AIVDM type $type")
        }
    }

    fun crc16(bytes: ByteArray): Int {
        var crc = 0xFFFF
        for (byte in bytes) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor CRC_POLY else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    fun hdlcFrames(bits: BooleanArray): List<BooleanArray> {
        val frames = ArrayList<BooleanArray>()
        val buf = ArrayList<Boolean>()
        var ones = 0
        var inFrame = false
        for (bit in bits) {
            if (bit) {
                ones++
                if (inFrame) buf += true
                if (ones == 6) {
                    if (inFrame && buf.size >= 168) frames += buf.dropLast(6).toBooleanArray()
                    buf.clear()
                    inFrame = true
                    ones = 0
                }
            } else {
                if (ones == 5 && inFrame) {
                    ones = 0
                    continue
                }
                if (inFrame) buf += false
                ones = 0
            }
        }
        return frames
    }

    fun nrzi(bits: BooleanArray): BooleanArray {
        val out = BooleanArray(bits.size)
        var prev = false
        for (i in bits.indices) {
            out[i] = bits[i] == prev
            prev = bits[i]
        }
        return out
    }

    private fun lat(raw: Int): Double {
        val signed = if (raw and (1 shl 26) != 0) raw - (1 shl 27) else raw
        return signed / 600000.0
    }

    private fun lon(raw: Int): Double {
        val signed = if (raw and (1 shl 27) != 0) raw - (1 shl 28) else raw
        return signed / 600000.0
    }

    private fun text(bits: BooleanArray, start: Int, chars: Int): String {
        val out = CharArray(chars)
        var i = 0
        while (i < chars && start + (i + 1) * 6 <= bits.size) {
            var value = field(bits, start + i * 6, 6)
            if (value < 32) value += 64
            out[i] = value.toChar()
            i++
        }
        return out.concatToString().trim()
    }

    private fun field(bits: BooleanArray, start: Int, width: Int): Int {
        var value = 0
        var i = 0
        while (i < width && start + i < bits.size) {
            value = (value shl 1) or if (bits[start + i]) 1 else 0
            i++
        }
        return value
    }

    private fun sample(byte: Byte, signed: Boolean): Float =
        if (signed) byte.toInt().toFloat() else (byte.toInt() and 0xFF) - 127.5f
}
