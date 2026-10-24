package app.radiusrf.features.sdr.domain

data class SdrBand(val name: String, val centerHz: Long, val mode: SdrMode)

object SdrBands {
    val rtlRates = intArrayOf(1_024_000, 2_048_000, 2_400_000, 2_560_000, 3_200_000)
    val hackrfRates = intArrayOf(2_000_000, 4_000_000, 8_000_000, 10_000_000, 16_000_000, 20_000_000)
    val airspyRates = intArrayOf(2_500_000, 10_000_000)
    val airspyHfRates = intArrayOf(192_000, 384_000, 768_000)
    val hydraRates = intArrayOf(2_500_000, 10_000_000)
    val fftSizes = intArrayOf(256, 512, 1_024, 2_048, 4_096, 8_192, 16_384, 32_768)

    val all = listOf(
        SdrBand("AM broadcast", 1_000_000L, SdrMode.AM),
        SdrBand("FM broadcast", 98_000_000L, SdrMode.WFM),
        SdrBand("Airband", 127_000_000L, SdrMode.AM),
        SdrBand("2 m", 146_000_000L, SdrMode.NFM),
        SdrBand("70 cm", 446_000_000L, SdrMode.NFM),
        SdrBand("ISM 433", 433_920_000L, SdrMode.NFM),
        SdrBand("ISM 915", 915_000_000L, SdrMode.NFM),
        SdrBand("ADS-B", 1_090_000_000L, SdrMode.AM),
        SdrBand("GPS L1", 1_575_420_000L, SdrMode.NFM)
    )

    val stationPlans = listOf(
        SdrBookmark("AM broadcast", 1_000_000L, SdrMode.AM, 10_000, isBand = true),
        SdrBookmark("80 m LSB", 3_750_000L, SdrMode.LSB, 2_700, isBand = true),
        SdrBookmark("40 m LSB", 7_150_000L, SdrMode.LSB, 2_700, isBand = true),
        SdrBookmark("20 m USB", 14_200_000L, SdrMode.USB, 2_700, isBand = true),
        SdrBookmark("15 m USB", 21_200_000L, SdrMode.USB, 2_700, isBand = true),
        SdrBookmark("10 m USB", 28_400_000L, SdrMode.USB, 2_700, isBand = true),
        SdrBookmark("CB AM", 27_185_000L, SdrMode.AM, 10_000, isBand = true),
        SdrBookmark("FM broadcast", 98_000_000L, SdrMode.WFM, 180_000, isBand = true),
        SdrBookmark("Airband", 127_000_000L, SdrMode.AM, 8_000, isBand = true),
        SdrBookmark("2 m FM", 146_520_000L, SdrMode.NFM, 12_500, isBand = true),
        SdrBookmark("Marine VHF", 156_800_000L, SdrMode.NFM, 16_000, isBand = true),
        SdrBookmark("AIS B", 162_025_000L, SdrMode.NFM, 16_000, isBand = true),
        SdrBookmark("NOAA weather", 162_550_000L, SdrMode.NFM, 16_000, isBand = true),
        SdrBookmark("ISM 433", 433_920_000L, SdrMode.NFM, 12_500, isBand = true),
        SdrBookmark("70 cm FM", 446_000_000L, SdrMode.NFM, 12_500, isBand = true),
        SdrBookmark("ISM 915", 915_000_000L, SdrMode.NFM, 12_500, isBand = true),
        SdrBookmark("ADS-B", 1_090_000_000L, SdrMode.AM, 2_000_000, isBand = true),
        SdrBookmark("GPS L1", 1_575_420_000L, SdrMode.NFM, 2_000_000, isBand = true)
    )

    fun defaultBandwidth(mode: SdrMode): Int = when (mode) {
        SdrMode.WFM -> 180_000
        SdrMode.NFM -> 12_500
        SdrMode.AM -> 10_000
        SdrMode.USB, SdrMode.LSB -> 2_700
        SdrMode.CW -> 200
    }

    fun parseIqName(name: String): Triple<Long?, Int?, IqFileFormat?> {
        val lower = name.lowercase()
        val freq = Regex("""(\d+(?:\.\d+)?)(ghz|mhz|khz|hz)""").find(lower)?.let { match ->
            val value = match.groupValues[1].toDoubleOrNull() ?: return@let null
            when (match.groupValues[2]) {
                "ghz" -> (value * 1_000_000_000L).toLong()
                "mhz" -> (value * 1_000_000L).toLong()
                "khz" -> (value * 1_000L).toLong()
                else -> value.toLong()
            }
        }
        val rate = Regex("""(\d+(?:\.\d+)?)(msps|ksps|sps)""").find(lower)?.let { match ->
            val value = match.groupValues[1].toDoubleOrNull() ?: return@let null
            when (match.groupValues[2]) {
                "msps" -> (value * 1_000_000).toInt()
                "ksps" -> (value * 1_000).toInt()
                else -> value.toInt()
            }
        }
        val format = when {
            lower.contains("hackrf") || lower.contains("signed8") -> IqFileFormat.SIGNED8
            lower.contains("airspy") || lower.contains("hydra") || lower.contains("s16") -> IqFileFormat.SIGNED16
            lower.contains("rtl") || lower.contains("cu8") -> IqFileFormat.UNSIGNED8
            else -> null
        }
        return Triple(freq, rate, format)
    }

    fun ratesFor(radio: SdrRadio): IntArray = when (radio) {
        SdrRadio.HACKRF -> hackrfRates
        SdrRadio.AIRSPY -> airspyRates
        SdrRadio.AIRSPYHF -> airspyHfRates
        SdrRadio.HYDRA -> hydraRates
        else -> rtlRates
    }

    fun preferredRate(radio: SdrRadio): Int = when (radio) {
        SdrRadio.HACKRF -> 8_000_000
        SdrRadio.AIRSPY, SdrRadio.HYDRA -> 2_500_000
        SdrRadio.AIRSPYHF -> 192_000
        else -> SdrDsp.SAMPLE_RATE
    }

    fun bandRate(radio: SdrRadio): Int = when (radio) {
        SdrRadio.HACKRF -> 8_000_000
        SdrRadio.AIRSPY, SdrRadio.HYDRA -> 2_500_000
        SdrRadio.AIRSPYHF -> 192_000
        else -> 2_048_000
    }

    fun nearestRate(radio: SdrRadio, wanted: Int): Int {
        val choices = ratesFor(radio)
        return choices.minBy { kotlin.math.abs(it - wanted) }
    }

    fun tuneRange(radio: SdrRadio): LongRange = when (radio) {
        SdrRadio.HACKRF -> 1_000_000L..6_000_000_000L
        SdrRadio.AIRSPY -> 24_000_000L..1_750_000_000L
        SdrRadio.HYDRA -> 24_000_000L..1_800_000_000L
        SdrRadio.AIRSPYHF -> 9_000L..260_000_000L
        else -> 0L..1_766_000_000L
    }
}

enum class SdrRadio { NONE, RTL, HACKRF, AIRSPY, AIRSPYHF, HYDRA, OTHER }
