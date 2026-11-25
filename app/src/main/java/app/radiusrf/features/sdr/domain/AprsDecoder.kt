package app.radiusrf.features.sdr.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class AprsPacket(
    val from: String,
    val to: String,
    val path: String,
    val info: String
)

/**
 * 1200 baud Bell 202 AFSK into AX.25 UI frames.
 * A 1 keeps the tone and a 0 flips it. Flags are 0x7E.
 */
object AprsDecoder {
    fun decodeIq(iq: ByteArray, n: Int, signed: Boolean, sampleRate: Int): List<AprsPacket> {
        if (n < 256 || sampleRate < 8_000) return emptyList()
        val audio = FloatArray(n / 2)
        var prevI = 0f
        var prevQ = 0f
        var i = 0
        var s = 0
        while (i + 1 < n) {
            val iv = iqSample(iq[i], signed)
            val qv = iqSample(iq[i + 1], signed)
            audio[s] = prevI * qv - prevQ * iv
            prevI = iv
            prevQ = qv
            i += 2
            s++
        }
        return decodeAudio(audio, s, sampleRate)
    }

    fun decodeAudio(audio: FloatArray, length: Int, sampleRate: Int): List<AprsPacket> {
        val count = length.coerceAtMost(audio.size)
        if (count < sampleRate / 10 || sampleRate < 8_000) return emptyList()
        val spb = (sampleRate / 1_200).coerceAtLeast(4)
        val raw = ArrayList<Boolean>(count / spb)
        var index = 0
        while (index + spb <= count) {
            val mark = tone(audio, index, spb, 1_200.0, sampleRate)
            val space = tone(audio, index, spb, 2_200.0, sampleRate)
            raw += mark >= space
            index += spb
        }
        if (raw.size < 16) return emptyList()
        val bits = ArrayList<Boolean>(raw.size)
        var prev = raw[0]
        var i = 1
        while (i < raw.size) {
            bits += raw[i] == prev
            prev = raw[i]
            i++
        }
        return frames(bits)
    }

    fun frames(bits: List<Boolean>): List<AprsPacket> {
        val packets = ArrayList<AprsPacket>()
        var i = 0
        while (i + 8 <= bits.size) {
            if (!flagAt(bits, i)) {
                i++
                continue
            }
            i += 8
            val body = ArrayList<Boolean>()
            var ones = 0
            while (i < bits.size) {
                if (flagAt(bits, i)) break
                val bit = bits[i]
                i++
                if (bit) {
                    ones++
                    body += true
                    if (ones == 5 && i < bits.size && !bits[i]) {
                        i++
                        ones = 0
                    }
                } else {
                    ones = 0
                    body += false
                }
            }
            parse(body)?.let { packets += it }
        }
        return packets
    }

    private fun parse(bits: List<Boolean>): AprsPacket? {
        if (bits.size < 18 * 8) return null
        val bytes = ByteArray(bits.size / 8)
        var b = 0
        while (b < bytes.size) {
            var value = 0
            var bit = 0
            while (bit < 8) {
                if (bits[b * 8 + bit]) value = value or (1 shl bit)
                bit++
            }
            bytes[b] = value.toByte()
            b++
        }
        if (bytes.size < 18) return null
        val payload = bytes.copyOf(bytes.size - 2)
        val fcs = (bytes[bytes.size - 2].toInt() and 0xFF) or ((bytes[bytes.size - 1].toInt() and 0xFF) shl 8)
        if (fcs != crc(payload)) return null
        val addresses = ArrayList<String>()
        var index = 0
        while (index + 7 <= payload.size && addresses.size < 8) {
            addresses += call(payload, index)
            val last = payload[index + 6].toInt() and 0x01 != 0
            index += 7
            if (last) break
        }
        if (addresses.size < 2 || index + 2 > payload.size) return null
        if ((payload[index].toInt() and 0xFF) != 0x03) return null
        val info = if (index + 2 < payload.size) {
            payload.copyOfRange(index + 2, payload.size).toString(Charsets.US_ASCII).filter { it.code in 32..126 }
        } else ""
        val path = if (addresses.size > 2) addresses.drop(2).joinToString(",") else ""
        return AprsPacket(addresses[1], addresses[0], path, info)
    }

    private fun call(bytes: ByteArray, offset: Int): String {
        val name = StringBuilder()
        var i = 0
        while (i < 6) {
            val ch = (bytes[offset + i].toInt() and 0xFF) ushr 1
            if (ch > 32) name.append(ch.toChar())
            i++
        }
        val ssid = (bytes[offset + 6].toInt() and 0x1E) ushr 1
        return if (ssid == 0) name.toString().trim() else "${name.toString().trim()}-$ssid"
    }

    private fun flagAt(bits: List<Boolean>, index: Int): Boolean {
        if (index + 8 > bits.size) return false
        val pattern = intArrayOf(0, 1, 1, 1, 1, 1, 1, 0)
        var i = 0
        while (i < 8) {
            if (bits[index + i] != (pattern[i] == 1)) return false
            i++
        }
        return true
    }

    fun crc(data: ByteArray): Int {
        var crc = 0xFFFF
        for (byte in data) {
            crc = crc xor (byte.toInt() and 0xFF)
            var bit = 0
            while (bit < 8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8408 else crc ushr 1
                bit++
            }
        }
        return crc.inv() and 0xFFFF
    }

    private fun tone(audio: FloatArray, start: Int, length: Int, freq: Double, rate: Int): Float {
        val coeff = (2.0 * cos(2.0 * PI * freq / rate)).toFloat()
        var s0 = 0f
        var s1 = 0f
        var s2 = 0f
        var i = 0
        while (i < length) {
            s0 = audio[start + i] + coeff * s1 - s2
            s2 = s1
            s1 = s0
            i++
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2
    }

    private fun iqSample(raw: Byte, signed: Boolean): Float =
        if (signed) raw.toInt() / 128f else ((raw.toInt() and 0xFF) - 127.5f) / 128f

    /** Test helper: one AX.25 UI frame as 48 kHz audio. */
    fun synthesize(from: String, to: String, info: String, sampleRate: Int = 48_000): FloatArray {
        val body = ArrayList<Byte>()
        body += address(to, last = false)
        body += address(from, last = true)
        body += 0x03.toByte()
        body += 0xF0.toByte()
        info.encodeToByteArray().forEach { body += it }
        val bytes = body.toByteArray()
        val fcs = crc(bytes)
        val framed = bytes + byteArrayOf((fcs and 0xFF).toByte(), ((fcs ushr 8) and 0xFF).toByte())
        val bits = ArrayList<Boolean>()
        bits += true
        repeat(8) { bits += flagBits() }
        var ones = 0
        for (byte in framed) {
            var bit = 0
            while (bit < 8) {
                val one = (byte.toInt() ushr bit) and 1 == 1
                bits += one
                if (one) {
                    ones++
                    if (ones == 5) {
                        bits += false
                        ones = 0
                    }
                } else ones = 0
                bit++
            }
        }
        bits += flagBits()
        var level = true
        val spb = sampleRate / 1_200
        val audio = FloatArray(bits.size * spb)
        var phase = 0.0
        val step = 2.0 * PI / sampleRate
        var n = 0
        for (one in bits) {
            if (!one) level = !level
            val freq = if (level) 1_200.0 else 2_200.0
            var s = 0
            while (s < spb) {
                audio[n++] = sin(phase).toFloat()
                phase += step * freq
                s++
            }
        }
        return audio
    }

    private fun flagBits(): List<Boolean> = listOf(false, true, true, true, true, true, true, false)

    private fun address(call: String, last: Boolean): List<Byte> {
        val name = call.substringBefore('-').uppercase().padEnd(6, ' ').take(6)
        val ssid = call.substringAfter('-', "0").toIntOrNull() ?: 0
        val out = ArrayList<Byte>(7)
        name.forEach { out += (it.code shl 1).toByte() }
        var ssidByte = 0x60 or ((ssid and 0x0F) shl 1)
        if (last) ssidByte = ssidByte or 0x01
        out += ssidByte.toByte()
        return out
    }
}
