package app.radiusrf.features.sdr.domain

data class IsmBand(val name: String, val hz: Long, val hint: String)

data class IsmBurst(
    val id: String,
    val bandHz: Long,
    val modulation: String,
    val family: String,
    val pulses: Int,
    val durationUs: Int,
    val bits: String?,
    val heardAt: Long
)

object IsmBands {
    val all = listOf(
        IsmBand("315 MHz", 315_000_000L, "North-American TPMS, PIR, and key fobs"),
        IsmBand("433 MHz", 433_920_000L, "Doorbells, weather nodes, cheap alarms"),
        IsmBand("868 MHz", 868_300_000L, "EU ISM sensors and LoRa"),
        IsmBand("915 MHz", 915_000_000L, "US ISM sensors and LoRa")
    )
}

/**
 * Pulse-width classifier for OOK/FSK bursts on 315/433/868/915 MHz.
 * Names a family from timing; it does not claim a full rtl_433 protocol dump.
 */
object IsmDecoder {
    data class PulseTrain(val widthsUs: List<Int>, val fsk: Boolean)
    private var mag = FloatArray(0)

    fun extract(iq: ByteArray, n: Int, signed: Boolean, sampleRate: Int): PulseTrain? {
        if (n < 64 || sampleRate < 8_000) return null
        val samples = n / 2
        if (mag.size < samples) mag = FloatArray(samples)
        var mean = 0f
        var i = 0
        var s = 0
        while (i + 1 < n) {
            val iVal = sample(iq[i], signed)
            val qVal = sample(iq[i + 1], signed)
            val m = kotlin.math.sqrt(iVal * iVal + qVal * qVal)
            mag[s] = m
            mean += m
            i += 2
            s++
        }
        mean /= samples
        var peak = 0f
        var p = 0
        while (p < samples) {
            if (mag[p] > peak) peak = mag[p]
            p++
        }
        val floor = mean + (peak - mean) * 0.35f
        val widths = ArrayList<Int>()
        var on = false
        var run = 0
        var crossings = 0
        var lastSign = 0
        i = 0
        s = 0
        while (i + 1 < n) {
            val high = mag[s] >= floor
            if (high == on) run++ else {
                if (on && run > 1) widths += (run * 1_000_000L / sampleRate).toInt().coerceAtLeast(1)
                on = high
                run = 1
            }
            val iVal = sample(iq[i], signed)
            val sign = if (iVal >= 0f) 1 else -1
            if (sign != lastSign && lastSign != 0) crossings++
            lastSign = sign
            i += 2
            s++
        }
        if (on && run > 1) widths += (run * 1_000_000L / sampleRate).toInt().coerceAtLeast(1)
        if (widths.size < 4) return null
        val fsk = crossings > samples / 8
        return PulseTrain(widths.take(80), fsk)
    }

    fun classify(train: PulseTrain, bandHz: Long, now: Long = System.currentTimeMillis()): IsmBurst {
        val median = median(train.widthsUs)
        val duration = train.widthsUs.sum()
        val family = when {
            median in 40..140 && train.widthsUs.size in 20..80 -> "TPMS / short sensor"
            median in 250..450 && !train.fsk -> "Doorbell / key fob"
            median in 400..800 && train.widthsUs.size >= 12 -> "Weather / alarm sensor"
            median in 80..250 && train.fsk -> "FSK sensor"
            bandHz in 860_000_000L..930_000_000L && train.fsk -> "ISM FSK / LoRa-like"
            else -> "Unknown ISM burst"
        }
        val bits = if (train.widthsUs.size >= 8) packBits(train.widthsUs, median) else null
        return IsmBurst(
            id = "$bandHz:$now:$duration",
            bandHz = bandHz,
            modulation = if (train.fsk) "FSK" else "OOK",
            family = family,
            pulses = train.widthsUs.size,
            durationUs = duration,
            bits = bits,
            heardAt = now
        )
    }

    private fun packBits(widths: List<Int>, median: Int): String {
        val short = (median * 0.65f).toInt().coerceAtLeast(1)
        val chars = widths.take(48).map { if (it > short) '1' else '0' }.toCharArray()
        return chars.concatToString()
    }

    private fun median(values: List<Int>): Int {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun sample(byte: Byte, signed: Boolean): Float =
        if (signed) byte.toInt().toFloat() else (byte.toInt() and 0xFF) - 127.5f
}
