package app.radiusrf.features.sdr.domain

data class SdrBand(val name: String, val centerHz: Long, val mode: SdrMode)

object SdrBands {
    val rtlRates = intArrayOf(1_024_000, 2_048_000, 2_400_000, 2_560_000, 3_200_000)
    val hackrfRates = intArrayOf(2_000_000, 4_000_000, 8_000_000, 10_000_000, 16_000_000, 20_000_000)
    val fftSizes = intArrayOf(256, 1024, 4096)

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

    fun ratesFor(radio: SdrRadio): IntArray = if (radio == SdrRadio.HACKRF) hackrfRates else rtlRates

    fun nearestRate(radio: SdrRadio, wanted: Int): Int {
        val choices = ratesFor(radio)
        return choices.minBy { kotlin.math.abs(it - wanted) }
    }
}

enum class SdrRadio { NONE, RTL, HACKRF, OTHER }
