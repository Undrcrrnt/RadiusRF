package app.radiusrf.features.meshtastic.domain

import app.radiusrf.features.ble.domain.BleDevice
import app.radiusrf.features.signatures.domain.DeviceSignatures
import kotlin.math.cos
import kotlin.math.sin

enum class MeshSurveyMode { BLUETOOTH, LORA }

data class MeshCompanion(
    val protocol: String,
    val detail: String
)

/** Bluetooth advertisements a mesh node sends so a phone or computer can connect. */
object MeshCompanionId {
    private const val RETICULUM_UUID = "37145b00-442d-4a94-917f-8f42c5da28e3"

    fun identify(device: BleDevice): MeshCompanion? {
        val name = device.name.orEmpty()
        val uuids = device.serviceUuids
        if (uuids.any { it.equals(DeviceSignatures.MESHTASTIC_UUID, ignoreCase = true) } ||
            name.contains("meshtastic", ignoreCase = true) ||
            name.contains("meshnology", ignoreCase = true)
        ) {
            return MeshCompanion("Meshtastic", "Bluetooth companion link")
        }
        if (uuids.any { it.equals(RETICULUM_UUID, ignoreCase = true) }) {
            return MeshCompanion("Reticulum", "Bluetooth mesh link")
        }
        if (name.contains("rnode", ignoreCase = true) || name.contains("reticulum", ignoreCase = true)) {
            return MeshCompanion("Reticulum", "RNode companion link")
        }
        if (name.contains("meshcore", ignoreCase = true)) {
            return MeshCompanion("MeshCore", "Bluetooth companion link")
        }
        return null
    }
}

data class LoraHit(
    val protocol: String,
    val spreading: Int,
    val sync: Int?,
    val strength: Float
)

/**
 * Finds a LoRa preamble in complex samples whose rate matches the chirp bandwidth.
 * A public Meshtastic sync word is 0x2B. 0x12 is the Semtech default shared by
 * many RNode and MeshCore radios, so those two are not split apart.
 */
object LoraMesh {
    private const val PEAK = 12f

    fun detect(i: FloatArray, q: FloatArray): LoraHit? {
        if (i.size < 128 || i.size != q.size) return null
        var best: LoraHit? = null
        for (sf in 7..12) {
            val hit = detectSf(i, q, sf) ?: continue
            if (best == null || hit.strength > best.strength) best = hit
        }
        return best
    }

    fun protocol(sync: Int?): String = when (sync) {
        0x2B -> "Meshtastic"
        0x12 -> "RNode / MeshCore"
        0x34 -> "LoRaWAN"
        null -> "LoRa"
        else -> "LoRa"
    }

    private fun detectSf(i: FloatArray, q: FloatArray, sf: Int): LoraHit? {
        val n = 1 shl sf
        if (i.size < n * 6) return null
        val chirpI = FloatArray(n)
        val chirpQ = FloatArray(n)
        for (k in 0 until n) {
            val phase = Math.PI * k * k / n
            chirpI[k] = cos(phase).toFloat()
            chirpQ[k] = sin(phase).toFloat()
        }
        val count = i.size / n
        val bins = IntArray(count)
        val ratios = FloatArray(count)
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (symbol in 0 until count) {
            val start = symbol * n
            for (k in 0 until n) {
                val s = start + k
                re[k] = i[s] * chirpI[k] + q[s] * chirpQ[k]
                im[k] = q[s] * chirpI[k] - i[s] * chirpQ[k]
            }
            fft(re, im)
            var peak = 0
            var peakPower = 0f
            var sum = 0f
            for (k in 0 until n) {
                val power = re[k] * re[k] + im[k] * im[k]
                sum += power
                if (power > peakPower) {
                    peakPower = power
                    peak = k
                }
            }
            bins[symbol] = peak
            ratios[symbol] = if (sum > 0f) peakPower / (sum / n) else 0f
        }
        var runStart = -1
        var runLen = 0
        var bestStart = -1
        var bestLen = 0
        var symbol = 0
        while (symbol < count) {
            val same = runLen > 0 && ratios[symbol] >= PEAK && bins[symbol] == bins[runStart]
            if (ratios[symbol] >= PEAK && (runLen == 0 || same)) {
                if (runLen == 0) runStart = symbol
                runLen++
            } else {
                if (runLen > bestLen) {
                    bestStart = runStart
                    bestLen = runLen
                }
                runLen = if (ratios[symbol] >= PEAK) 1 else 0
                runStart = if (ratios[symbol] >= PEAK) symbol else -1
            }
            symbol++
        }
        if (runLen > bestLen) {
            bestStart = runStart
            bestLen = runLen
        }
        if (bestLen < 6 || bestStart < 0) return null
        val preamble = bins[bestStart]
        val first = bestStart + bestLen
        val sync = if (first + 1 < count && ratios[first] >= PEAK && ratios[first + 1] >= PEAK) {
            val high = nibble(bins[first], preamble, n, sf)
            val low = nibble(bins[first + 1], preamble, n, sf)
            (high shl 4) or low
        } else {
            null
        }
        return LoraHit(protocol(sync), sf, sync, ratios[bestStart])
    }

    private fun nibble(bin: Int, preamble: Int, n: Int, sf: Int): Int {
        val shift = sf - 4
        val raw = (bin - preamble + n) % n
        return ((raw + (1 shl (shift - 1))) ushr shift) and 0x0F
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wlenRe = cos(ang).toFloat()
            val wlenIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var wRe = 1f
                var wIm = 0f
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vr = re[i + k + len / 2]
                    val vi = im[i + k + len / 2]
                    val vRe = vr * wRe - vi * wIm
                    val vIm = vr * wIm + vi * wRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val next = wRe * wlenRe - wIm * wlenIm
                    wIm = wRe * wlenIm + wIm * wlenRe
                    wRe = next
                }
                i += len
            }
            len = len shl 1
        }
    }
}
