package app.radiusrf.features.sdr.domain

data class PocsagMessage(
    val address: Int,
    val function: Int,
    val text: String,
    val baud: Int
)

/** POCSAG 512/1200/2400 address and numeric or 7-bit text. Receive only. */
object PocsagDecoder {
    private const val SYNC = 0x7CD215D8.toInt()

    fun decodeIq(iq: ByteArray, n: Int, signed: Boolean, sampleRate: Int, baud: Int): List<PocsagMessage> {
        if (n < 256 || sampleRate < baud * 4) return emptyList()
        val samples = n / 2
        val disc = FloatArray(samples)
        var prevI = 0f
        var prevQ = 0f
        var i = 0
        var s = 0
        while (i + 1 < n) {
            val iv = if (signed) iq[i].toInt() / 128f else ((iq[i].toInt() and 0xFF) - 127.5f) / 128f
            val qv = if (signed) iq[i + 1].toInt() / 128f else ((iq[i + 1].toInt() and 0xFF) - 127.5f) / 128f
            disc[s] = prevI * qv - prevQ * iv
            prevI = iv
            prevQ = qv
            i += 2
            s++
        }
        val spb = (sampleRate / baud).coerceAtLeast(2)
        val bits = ArrayList<Boolean>(samples / spb)
        var index = spb / 2
        while (index < samples) {
            var sum = 0f
            var k = 0
            while (k < spb && index + k < samples) {
                sum += disc[index + k]
                k++
            }
            bits += sum >= 0f
            index += spb
        }
        val upright = decodeBits(bits, baud)
        if (upright.isNotEmpty()) return upright
        return decodeBits(bits.map { !it }, baud)
    }

    fun decodeBits(bits: List<Boolean>, baud: Int): List<PocsagMessage> {
        val messages = ArrayList<PocsagMessage>()
        var i = 0
        while (i + 32 <= bits.size) {
            val word = wordAt(bits, i)
            if (word == null || Integer.bitCount(word xor SYNC) > 2) {
                i++
                continue
            }
            i += 32
            var address = -1
            var function = 0
            val text = StringBuilder()
            var frame = 0
            while (frame < 8 && i + 64 <= bits.size) {
                val first = wordAt(bits, i)
                val second = wordAt(bits, i + 32)
                i += 64
                if (first != null && valid(first) && (first ushr 31) == 0) {
                    val field = (first ushr 13) and 0x3FFFF
                    function = (first ushr 11) and 0x3
                    address = (field shl 3) or frame
                }
                if (second != null && valid(second) && (second ushr 31) != 0) {
                    text.append(textOf((second ushr 11) and 0xFFFFF, function))
                }
                frame++
            }
            if (address >= 0 && text.isNotBlank()) {
                messages += PocsagMessage(address, function, text.toString().trim(), baud)
            }
        }
        return messages
    }

    fun encodeBatch(address: Int, function: Int, digits: String): List<Boolean> {
        val bits = ArrayList<Boolean>()
        append(bits, SYNC)
        val frame = address and 7
        val field = (address ushr 3) and 0x3FFFF
        val info = (field shl 2) or (function and 3)
        var slot = 0
        while (slot < 8) {
            val addressWord = if (slot == frame) codeword(info) else 0x7A89C197.toInt()
            val message = if (slot == frame) codeword(0x100000 or packNumeric(digits)) else 0x7A89C197.toInt()
            append(bits, addressWord)
            append(bits, message)
            slot++
        }
        return bits
    }

    private fun textOf(data20: Int, function: Int): String {
        if (function == 0 || function == 3) {
            val chars = CharArray(5)
            var i = 0
            while (i < 5) {
                val nibble = (data20 ushr (16 - 4 * i)) and 0xF
                chars[i] = when (nibble) {
                    in 0..9 -> ('0'.code + nibble).toChar()
                    0xA -> ' '
                    0xB -> 'U'
                    0xC -> '-'
                    0xD -> ')'
                    0xE -> '('
                    else -> ' '
                }
                i++
            }
            return chars.concatToString()
        }
        val chars = StringBuilder()
        var i = 0
        while (i < 2) {
            val value = (data20 ushr (13 - 7 * i)) and 0x7F
            if (value in 32..126) chars.append(value.toChar())
            i++
        }
        return chars.toString()
    }

    private fun packNumeric(digits: String): Int {
        var data = 0
        val padded = digits.padEnd(5, ' ').take(5)
        padded.forEach { ch ->
            val nibble = when (ch) {
                in '0'..'9' -> ch - '0'
                ' ' -> 0xA
                else -> 0xC
            }
            data = (data shl 4) or nibble
        }
        return data and 0xFFFFF
    }

    private fun codeword(info21: Int): Int {
        val info = info21 and 0x1FFFFF
        val check = bch(info)
        var word = (info shl 11) or (check shl 1)
        var parity = 0
        var value = word ushr 1
        var bit = 0
        while (bit < 31) {
            parity = parity xor (value and 1)
            value = value ushr 1
            bit++
        }
        if (parity != 0) word = word or 1
        return word
    }

    private fun valid(word: Int): Boolean {
        val info = (word ushr 11) and 0x1FFFFF
        if (bch(info) != ((word ushr 1) and 0x3FF)) return false
        return Integer.bitCount(word) % 2 == 0
    }

    private fun bch(info21: Int): Int {
        var reg = 0
        var i = 20
        while (i >= 0) {
            val bit = (info21 shr i) and 1
            val msb = (reg shr 9) and 1
            reg = ((reg shl 1) and 0x3FF) or bit
            if (msb == 1) reg = reg xor 0x369
            i--
        }
        return reg and 0x3FF
    }

    private fun wordAt(bits: List<Boolean>, index: Int): Int? {
        if (index + 32 > bits.size) return null
        var word = 0
        var i = 0
        while (i < 32) {
            word = (word shl 1) or if (bits[index + i]) 1 else 0
            i++
        }
        return word
    }

    private fun append(bits: MutableList<Boolean>, word: Int) {
        var i = 31
        while (i >= 0) {
            bits += ((word ushr i) and 1) != 0
            i--
        }
    }
}
