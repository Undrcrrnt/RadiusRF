package com.emptyset.detector.detect

import com.emptyset.detector.radio.RadioKind

enum class WifiBand(val label: String) {
    GHZ_24("2.4 GHz"),
    GHZ_5("5 GHz"),
    GHZ_6("6 GHz");

    companion object {
        fun infer(channel: Int?): WifiBand = when {
            channel == null -> GHZ_24
            channel >= 36 -> GHZ_5
            else -> GHZ_24
        }
    }
}

data class HopChannel(
    val channel: Int,
    val band: WifiBand
)

/**
 * Hop schedules for 2.4 GHz, 5 GHz UNII, and 6 GHz PSC.
 * Current T2U / PAU0A / PAU0B backends tune 2.4 and 5 GHz only.
 */
object ChannelPlan {
    val BAND_24 = (1..13).map { HopChannel(it, WifiBand.GHZ_24) }

    val BAND_5 = listOf(
        36, 40, 44, 48,
        52, 56, 60, 64,
        100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144,
        149, 153, 157, 161, 165
    ).map { HopChannel(it, WifiBand.GHZ_5) }

    /** 6 GHz 20 MHz preferred scanning channels (PSC). */
    val BAND_6 = (1..233 step 4).map { HopChannel(it, WifiBand.GHZ_6) }

    fun hopset(band24: Boolean, band5: Boolean, band6: Boolean = false): List<HopChannel> =
        buildList {
            if (band24) addAll(BAND_24)
            if (band5) addAll(BAND_5)
            if (band6) addAll(BAND_6)
        }

    fun canTune(kind: RadioKind, hop: HopChannel): Boolean = when (hop.band) {
        WifiBand.GHZ_6 -> false
        WifiBand.GHZ_24, WifiBand.GHZ_5 -> kind != RadioKind.UNKNOWN
    }

    fun hopsetFor(
        kind: RadioKind,
        band24: Boolean,
        band5: Boolean,
        band6: Boolean
    ): List<HopChannel> = hopset(band24, band5, band6).filter { canTune(kind, it) }
}
