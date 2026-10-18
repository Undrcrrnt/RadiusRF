package app.radiusrf.features.sdr.domain

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

enum class SdrMode(val label: String) {
    WFM("WFM"),
    NFM("NFM"),
    AM("AM"),
    USB("USB"),
    LSB("LSB"),
    CW("CW")
}

data class SdrBookmark(val name: String, val hz: Long, val mode: SdrMode)

data class SdrDetection(val hz: Long, val db: Float, val timeMs: Long, val mode: SdrMode)

class DemodState {
    var prevI = 0f
    var prevQ = 0f
    var dc = 0f
    var osc = 0.0
}

object SdrDsp {
    const val SAMPLE_RATE = 1_024_000
    const val AUDIO_RATE = 32_000
    const val DECIM = SAMPLE_RATE / AUDIO_RATE
    const val FFT_N = 256

    fun demod(
        iq: ByteArray,
        length: Int,
        mode: SdrMode,
        state: DemodState,
        pcm: ShortArray,
        sampleRate: Int = SAMPLE_RATE,
        signed: Boolean = false,
        offsetHz: Double = 0.0
    ): Int {
        val shiftHz = offsetHz + when (mode) {
            SdrMode.USB -> -1500.0
            SdrMode.LSB -> 1500.0
            SdrMode.CW -> -700.0
            else -> 0.0
        }
        val rate = sampleRate.coerceAtLeast(AUDIO_RATE)
        val decim = rate / AUDIO_RATE
        val omega = 2.0 * PI * shiftHz / rate
        val gain = if (mode == SdrMode.WFM || mode == SdrMode.NFM) 6000.0 else 80.0
        var produced = 0
        var acc = 0.0
        var count = 0
        var index = 0
        while (index + 1 < length) {
            var i = sample(iq[index], signed)
            var q = sample(iq[index + 1], signed)
            index += 2
            if (shiftHz != 0.0) {
                state.osc += omega
                val c = cos(state.osc).toFloat()
                val s = sin(state.osc).toFloat()
                val ni = i * c - q * s
                val nq = i * s + q * c
                i = ni
                q = nq
            }
            val audio = when (mode) {
                SdrMode.AM -> {
                    val mag = sqrt(i * i + q * q)
                    state.dc = state.dc * 0.9995f + mag * 0.0005f
                    mag - state.dc
                }
                SdrMode.USB, SdrMode.LSB, SdrMode.CW -> i
                else -> {
                    val cross = i * state.prevQ - q * state.prevI
                    val dot = i * state.prevI + q * state.prevQ
                    atan2(cross, dot)
                }
            }
            state.prevI = i
            state.prevQ = q
            acc += audio
            count++
            if (count == decim) {
                val scaled = (acc / count) * gain
                if (produced < pcm.size) {
                    pcm[produced++] = scaled.coerceIn(-32767.0, 32767.0).toInt().toShort()
                }
                acc = 0.0
                count = 0
            }
        }
        return produced
    }

    /** Power in dB, fftshifted so index 0 is the low side of the tuned span. */
    fun spectrum(iq: ByteArray, length: Int, out: FloatArray, signed: Boolean = false) {
        val n = out.size
        val re = FloatArray(n)
        val im = FloatArray(n)
        var k = 0
        for (i in 0 until n) {
            if (k + 1 >= length) break
            val window = (0.5 * (1.0 - cos(2.0 * PI * i / (n - 1).coerceAtLeast(1)))).toFloat()
            re[i] = sample(iq[k], signed) * 128f * window
            im[i] = sample(iq[k + 1], signed) * 128f * window
            k += 2
        }
        transform(re, im)
        for (i in 0 until n) {
            val src = (i + n / 2) and (n - 1)
            val power = re[src] * re[src] + im[src] * im[src]
            out[i] = (10.0 * log10(power.toDouble() + 1e-12)).toFloat()
        }
    }

    fun binFrequency(centerHz: Long, bin: Int, sampleRate: Int = SAMPLE_RATE, fftN: Int = FFT_N): Long {
        val width = sampleRate.toLong()
        val bins = fftN.coerceAtLeast(1)
        return centerHz - width / 2 + width * bin / bins
    }

    private fun sample(raw: Byte, signed: Boolean): Float {
        val centered = if (signed) raw.toInt().toFloat() else (raw.toInt() and 0xFF) - 127.5f
        return centered / 128f
    }

    private fun transform(re: FloatArray, im: FloatArray) {
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
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = (-2.0 * PI / len).toFloat()
            val wlenRe = cos(ang)
            val wlenIm = sin(ang)
            var i = 0
            while (i < n) {
                var wRe = 1f
                var wIm = 0f
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * wRe - im[i + k + len / 2] * wIm
                    val vIm = re[i + k + len / 2] * wIm + im[i + k + len / 2] * wRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nextRe = wRe * wlenRe - wIm * wlenIm
                    wIm = wRe * wlenIm + wIm * wlenRe
                    wRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
