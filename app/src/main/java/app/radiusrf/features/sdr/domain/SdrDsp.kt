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

data class SdrBookmark(
    val name: String,
    val hz: Long,
    val mode: SdrMode,
    val bandwidthHz: Int = 0,
    val isBand: Boolean = false
)

data class SdrDetection(val hz: Long, val db: Float, val timeMs: Long, val mode: SdrMode)

data class SdrRecording(
    val name: String,
    val path: String,
    val frequencyHz: Long,
    val sampleRate: Int,
    val format: IqFileFormat,
    val bytes: Long
)

enum class IqFileFormat(val label: String) {
    UNSIGNED8("RTL-SDR 8-bit"),
    SIGNED8("HackRF 8-bit"),
    SIGNED16("Airspy 16-bit")
}

enum class DirectSampling(val label: String) {
    OFF("Off"),
    AUTO("Automatic"),
    I("I-channel"),
    Q("Q-channel")
}

class DemodState {
    var prevI = 0f
    var prevQ = 0f
    var dc = 0f
    var osc = 0.0
    var decimationPhase = 0
    var accumulator = 0.0
    var accumulatorCount = 0
    var filteredAudio = 0f
    var cicI = 0.0
    var cicQ = 0.0
    var cicCount = 0
    var lpfI = 0f
    var lpfQ = 0f
    var channelPower = 1e-12f
}

object SdrDsp {
    const val SAMPLE_RATE = 1_024_000
    const val AUDIO_RATE = 48_000
    const val DECIM = SAMPLE_RATE / AUDIO_RATE
    const val FFT_N = 4_096

    fun demod(
        iq: ByteArray,
        length: Int,
        mode: SdrMode,
        state: DemodState,
        pcm: ShortArray,
        sampleRate: Int = SAMPLE_RATE,
        signed: Boolean = false,
        offsetHz: Double = 0.0,
        channelBandwidthHz: Int = 12_500
    ): Int {
        val rate = sampleRate.coerceAtLeast(AUDIO_RATE)
        val bandwidth = channelBandwidthHz.coerceIn(50, rate / 2)
        val channelRate = maxOf(AUDIO_RATE, minOf(rate, bandwidth * 4))
        val cicDecim = maxOf(1, rate / channelRate)
        val actualChannelRate = (rate / cicDecim).coerceAtLeast(1)
        val shiftHz = offsetHz + when (mode) {
            SdrMode.USB -> -1_500.0
            SdrMode.LSB -> 1_500.0
            SdrMode.CW -> -700.0
            else -> 0.0
        }
        val omega = 2.0 * PI * shiftHz / rate
        val cutoff = bandwidth / 2.0
        val alpha = (1.0 - kotlin.math.exp(-2.0 * PI * cutoff / actualChannelRate)).toFloat().coerceIn(0.02f, 1f)
        val gain = when (mode) {
            SdrMode.WFM -> 5_500.0
            SdrMode.NFM -> 14_000.0
            SdrMode.CW -> 140.0
            else -> 90.0
        }
        val deemph = (1.0 - kotlin.math.exp(-1.0 / (AUDIO_RATE * 75e-6))).toFloat()
        var produced = 0
        var index = 0
        while (index + 1 < length) {
            var i = sample(iq[index], signed)
            var q = sample(iq[index + 1], signed)
            index += 2
            if (shiftHz != 0.0) {
                state.osc += omega
                if (state.osc > 2.0 * PI) state.osc -= 2.0 * PI
                val c = cos(state.osc).toFloat()
                val s = sin(state.osc).toFloat()
                val ni = i * c - q * s
                val nq = i * s + q * c
                i = ni
                q = nq
            }
            state.cicI += i
            state.cicQ += q
            state.cicCount++
            if (state.cicCount < cicDecim) continue
            val fi = (state.cicI / state.cicCount).toFloat()
            val fq = (state.cicQ / state.cicCount).toFloat()
            state.cicI = 0.0
            state.cicQ = 0.0
            state.cicCount = 0
            state.lpfI += alpha * (fi - state.lpfI)
            state.lpfQ += alpha * (fq - state.lpfQ)
            val filteredI = state.lpfI
            val filteredQ = state.lpfQ
            state.channelPower = state.channelPower * 0.95f + (filteredI * filteredI + filteredQ * filteredQ) * 0.05f
            var audio = when (mode) {
                SdrMode.AM -> {
                    val mag = sqrt(filteredI * filteredI + filteredQ * filteredQ)
                    state.dc = state.dc * 0.995f + mag * 0.005f
                    mag - state.dc
                }
                SdrMode.USB, SdrMode.LSB, SdrMode.CW -> filteredI
                else -> {
                    val cross = filteredI * state.prevQ - filteredQ * state.prevI
                    val dot = filteredI * state.prevI + filteredQ * state.prevQ
                    atan2(cross, dot)
                }
            }
            state.prevI = filteredI
            state.prevQ = filteredQ
            state.accumulator += audio
            state.accumulatorCount++
            state.decimationPhase += AUDIO_RATE
            if (state.decimationPhase >= actualChannelRate) {
                state.decimationPhase -= actualChannelRate
                var sample = (state.accumulator / state.accumulatorCount.coerceAtLeast(1)).toFloat()
                if (mode == SdrMode.WFM) {
                    state.filteredAudio += deemph * (sample - state.filteredAudio)
                    sample = state.filteredAudio
                }
                if (produced < pcm.size) {
                    pcm[produced++] = (sample * gain).coerceIn(-32767.0, 32767.0).toInt().toShort()
                }
                state.accumulator = 0.0
                state.accumulatorCount = 0
            }
        }
        return produced
    }

    fun channelPowerDb(state: DemodState): Float =
        (10.0 * log10(state.channelPower.toDouble() + 1e-12)).toFloat()

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
        val fullScale = (n * 64.0).let { it * it }
        for (i in 0 until n) {
            val src = (i + n / 2) and (n - 1)
            val power = re[src] * re[src] + im[src] * im[src]
            out[i] = (10.0 * log10(power.toDouble() / fullScale + 1e-12)).toFloat()
        }
    }

    /**
     * A freshly cleared spectrum is all zeros. A measured one has a real dB span.
     * Fitting the plot to the cleared buffer pins the scale above the noise and hides the trace.
     */
    fun isMeasuredSpectrum(bins: FloatArray): Boolean {
        if (bins.size < 8) return false
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        for (value in bins) {
            if (!value.isFinite()) return false
            if (value < lo) lo = value
            if (value > hi) hi = value
        }
        return !(hi > -1f && hi - lo < 3f)
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
