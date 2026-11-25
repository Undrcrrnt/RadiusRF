package app.radiusrf.features.sdr.domain

data class AcarsMessage(val text: String)

/**
 * VHF ACARS text after MSK demodulation.
 * Bytes are 7-bit characters with odd parity. Blocks are printable runs.
 */
object AcarsDecoder {
    const val MHZ = 131_550_000L

    fun decodeIq(iq: ByteArray, n: Int, signed: Boolean, sampleRate: Int): List<AcarsMessage> {
        if (n < 256 || sampleRate < 9_600) return emptyList()
        val samples = n / 2
        val mag = FloatArray(samples)
        var mean = 0f
        var i = 0
        var s = 0
        while (i + 1 < n) {
            val iv = if (signed) iq[i].toInt() / 128f else ((iq[i].toInt() and 0xFF) - 127.5f) / 128f
            val qv = if (signed) iq[i + 1].toInt() / 128f else ((iq[i + 1].toInt() and 0xFF) - 127.5f) / 128f
            val m = kotlin.math.sqrt(iv * iv + qv * qv)
            mag[s] = m
            mean += m
            i += 2
            s++
        }
        mean /= samples.coerceAtLeast(1)
        val audio = FloatArray(samples)
        var dc = mean
        s = 0
        while (s < samples) {
            dc = dc * 0.995f + mag[s] * 0.005f
            audio[s] = mag[s] - dc
            s++
        }
        return messages(bitsFromAudio(audio, sampleRate))
    }

    fun messages(bits: List<Boolean>): List<AcarsMessage> {
        if (bits.size < 16) return emptyList()
        val text = StringBuilder()
        val found = ArrayList<AcarsMessage>()
        var i = 0
        while (i + 8 <= bits.size) {
            var value = 0
            var bit = 0
            while (bit < 8) {
                if (bits[i + bit]) value = value or (1 shl bit)
                bit++
            }
            val parity = Integer.bitCount(value)
            val char = value and 0x7F
            if (parity % 2 == 1 && char in 32..126) {
                text.append(char.toChar())
            } else if (text.length >= 6) {
                found += AcarsMessage(text.toString().trim())
                text.clear()
            } else text.clear()
            i += 8
        }
        if (text.length >= 6) found += AcarsMessage(text.toString().trim())
        return found
    }

    private fun bitsFromAudio(audio: FloatArray, sampleRate: Int): List<Boolean> {
        val spb = (sampleRate / 2_400).coerceAtLeast(2)
        val bits = ArrayList<Boolean>(audio.size / spb)
        var index = 0
        var prev = 0f
        while (index + spb <= audio.size) {
            var sum = 0f
            var k = 0
            while (k < spb) {
                sum += audio[index + k]
                k++
            }
            val now = sum
            bits += now * prev < 0f
            prev = if (now == 0f) prev else now
            index += spb
        }
        return bits
    }
}
