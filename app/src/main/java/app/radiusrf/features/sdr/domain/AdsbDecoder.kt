package app.radiusrf.features.sdr.domain

data class AdsbFrame(
    val icao: String,
    val df: Int,
    val typeCode: Int,
    val callsign: String?,
    val altitudeFt: Int?,
    val lat: Double?,
    val lon: Double?,
    val rawHex: String
)

/** Mode S DF17 parser. Needs ~2 MS/s IQ on 1090 MHz. */
object AdsbDecoder {
    private const val POLY = 0xFFF409
    private val charset = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ#####_###############0123456789######".toCharArray()
    private var mag = FloatArray(0)

    fun decode(iq: ByteArray, n: Int, signed: Boolean, sampleRate: Int, evenCpr: MutableMap<String, Pair<Int, Int>>, oddCpr: MutableMap<String, Pair<Int, Int>>): List<AdsbFrame> {
        if (sampleRate < 1_800_000 || n < 256) return emptyList()
        val samples = n / 2
        if (mag.size < samples) mag = FloatArray(samples)
        var i = 0
        var s = 0
        while (i + 1 < n) {
            val iv = sample(iq[i], signed)
            val qv = sample(iq[i + 1], signed)
            mag[s] = iv * iv + qv * qv
            i += 2
            s++
        }
        val spb = sampleRate / 1_000_000
        if (spb < 1) return emptyList()
        val out = ArrayList<AdsbFrame>()
        var index = 0
        val last = samples - 120 * spb
        while (index < last) {
            if (!preamble(mag, index, spb)) {
                index++
                continue
            }
            val bits = bits(mag, index + 8 * spb, spb) ?: run {
                index += spb
                continue
            }
            parse(bits, evenCpr, oddCpr)?.let { out += it }
            index += 120 * spb
        }
        return out
    }

    fun crcOk(bits: BooleanArray): Boolean {
        if (bits.size < 112) return false
        return field(bits, 88, 24) == crc88(bits)
    }

    fun crc88(bits: BooleanArray): Int {
        val bytes = ByteArray(11)
        var i = 0
        while (i < 88) {
            if (bits[i]) bytes[i / 8] = (bytes[i / 8].toInt() or (1 shl (7 - i % 8))).toByte()
            i++
        }
        var rem = 0
        for (byte in bytes) {
            rem = rem xor ((byte.toInt() and 0xFF) shl 16)
            repeat(8) {
                rem = if (rem and 0x800000 != 0) (rem shl 1) xor POLY else rem shl 1
                rem = rem and 0xFFFFFF
            }
        }
        return rem
    }

    fun parse(bits: BooleanArray, evenCpr: MutableMap<String, Pair<Int, Int>> = mutableMapOf(), oddCpr: MutableMap<String, Pair<Int, Int>> = mutableMapOf()): AdsbFrame? {
        if (bits.size < 112 || !crcOk(bits)) return null
        val df = field(bits, 0, 5)
        if (df != 17 && df != 18) return null
        val icao = "%06X".format(field(bits, 8, 24))
        val type = field(bits, 32, 5)
        var callsign: String? = null
        var altitude: Int? = null
        var lat: Double? = null
        var lon: Double? = null
        when (type) {
            in 1..4 -> callsign = callsign(bits)
            in 9..18, in 20..22 -> {
                altitude = altitude(bits)
                val odd = bits[53]
                val latCpr = field(bits, 54, 17)
                val lonCpr = field(bits, 71, 17)
                val store = if (odd) oddCpr else evenCpr
                store[icao] = latCpr to lonCpr
                val other = (if (odd) evenCpr else oddCpr)[icao]
                if (other != null) {
                    val decoded = cpr(if (odd) other.first else latCpr, if (odd) other.second else lonCpr, if (odd) latCpr else other.first, if (odd) lonCpr else other.second)
                    lat = decoded?.first
                    lon = decoded?.second
                }
            }
        }
        return AdsbFrame(icao, df, type, callsign, altitude, lat, lon, hex(bits))
    }

    fun frame(df: Int, icao: Int, payload: BooleanArray): BooleanArray {
        val bits = BooleanArray(112)
        write(bits, 0, 5, df)
        write(bits, 5, 3, 5)
        write(bits, 8, 24, icao)
        payload.forEachIndexed { index, bit -> if (32 + index < 88) bits[32 + index] = bit }
        write(bits, 88, 24, crc88(bits))
        return bits
    }

    private fun preamble(mag: FloatArray, start: Int, spb: Int): Boolean {
        val p0 = mag.getOrElse(start) { 0f }
        val p1 = mag.getOrElse(start + spb) { 0f }
        val p2 = mag.getOrElse(start + (7 * spb) / 2) { 0f }
        val p3 = mag.getOrElse(start + (9 * spb) / 2) { 0f }
        val low = mag.getOrElse(start + 2 * spb) { 0f } + mag.getOrElse(start + 3 * spb) { 0f }
        val high = p0 + p1 + p2 + p3
        return high > low * 2.2f && p0 > 0f
    }

    private fun bits(mag: FloatArray, start: Int, spb: Int): BooleanArray? {
        val bits = BooleanArray(112)
        var i = 0
        while (i < 112) {
            val a = mag.getOrElse(start + i * spb) { return null }
            val b = mag.getOrElse(start + i * spb + spb / 2) { return null }
            bits[i] = a > b
            i++
        }
        return bits
    }

    private fun callsign(bits: BooleanArray): String {
        val chars = CharArray(8)
        var i = 0
        while (i < 8) {
            val value = field(bits, 40 + i * 6, 6).coerceIn(0, charset.lastIndex)
            chars[i] = charset[value]
            i++
        }
        return chars.concatToString().replace('#', ' ').replace('_', ' ').trim()
    }

    private fun altitude(bits: BooleanArray): Int? {
        val n = field(bits, 40, 12)
        if (n == 0) return null
        val q = if (bits[47]) 25 else 100
        val raw = ((n shr 1) and 0x7F0) or (n and 0x0F)
        return raw * q - 1000
    }

    private fun cpr(evenLat: Int, evenLon: Int, oddLat: Int, oddLon: Int): Pair<Double, Double>? {
        val dLat0 = 360.0 / 60.0
        val dLat1 = 360.0 / 59.0
        val j = kotlin.math.floor((59.0 * evenLat - 60.0 * oddLat) / 131072.0 + 0.5)
        val lat0 = dLat0 * ((j % 60 + 60) % 60 + evenLat / 131072.0)
        val lat1 = dLat1 * ((j % 59 + 59) % 59 + oddLat / 131072.0)
        val lat = if (lat0 >= 270) lat0 - 360 else lat0
        val nl = cprNl(lat).coerceAtLeast(1)
        val ni = (nl - 1).coerceAtLeast(1)
        val m = kotlin.math.floor((evenLon * (nl - 1.0) - oddLon * nl.toDouble()) / 131072.0 + 0.5)
        val lon = (360.0 / nl) * ((m % nl + nl) % nl + evenLon / 131072.0)
        val wrapped = if (lon >= 180) lon - 360 else lon
        if (lat !in -90.0..90.0) return null
        return lat to wrapped
    }

    private fun cprNl(lat: Double): Int {
        val a = 1 - kotlin.math.cos(Math.PI * 2 / 15.0)
        val b = kotlin.math.cos(Math.PI / 180.0 * kotlin.math.abs(lat).coerceAtMost(87.0))
        val nl = 2 * Math.PI / kotlin.math.acos((1 - a / (b * b)).coerceIn(-1.0, 1.0))
        return kotlin.math.floor(nl).toInt().coerceIn(1, 59)
    }

    private fun field(bits: BooleanArray, start: Int, width: Int): Int {
        var value = 0
        var i = 0
        while (i < width) {
            value = (value shl 1) or if (bits[start + i]) 1 else 0
            i++
        }
        return value
    }

    private fun write(bits: BooleanArray, start: Int, width: Int, value: Int) {
        var i = 0
        while (i < width) {
            bits[start + i] = ((value ushr (width - 1 - i)) and 1) != 0
            i++
        }
    }

    private fun hex(bits: BooleanArray): String {
        val out = StringBuilder(bits.size / 4)
        var i = 0
        while (i + 3 < bits.size) {
            out.append("%X".format(field(bits, i, 4)))
            i += 4
        }
        return out.toString()
    }

    private fun sample(byte: Byte, signed: Boolean): Float =
        if (signed) byte.toInt().toFloat() else (byte.toInt() and 0xFF) - 127.5f
}
