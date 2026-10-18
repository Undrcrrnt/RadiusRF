package app.radiusrf.features.wifi.domain

import app.radiusrf.core.util.WifiBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelAnalyzerTest {

    private fun ap(ssid: String, bssid: String, rssi: Int, frequency: Int) =
        WifiAccessPoint(ssid = ssid, bssid = bssid, rssi = rssi, frequency = frequency, capabilities = "[ESS]")

    @Test
    fun `groups access points by channel and averages rssi`() {
        val aps = listOf(
            ap("A", "AA:AA:AA:AA:AA:01", rssi = -40, frequency = 2412), // channel 1
            ap("B", "AA:AA:AA:AA:AA:02", rssi = -60, frequency = 2412), // channel 1
            ap("C", "AA:AA:AA:AA:AA:03", rssi = -50, frequency = 2437)  // channel 6
        )

        val scores = ChannelAnalyzer.analyze(aps)

        assertEquals(2, scores.size)
        val channel1 = scores.first { it.channel == 1 }
        assertEquals(2, channel1.apCount)
        assertEquals(-50, channel1.avgRssi) // average of -40 and -60
        assertEquals(WifiBand.BAND_2_4GHZ, channel1.band)

        val channel6 = scores.first { it.channel == 6 }
        assertEquals(1, channel6.apCount)
    }

    @Test
    fun `results are sorted by channel ascending`() {
        val aps = listOf(
            ap("A", "AA:AA:AA:AA:AA:01", rssi = -40, frequency = 2462), // channel 11
            ap("B", "AA:AA:AA:AA:AA:02", rssi = -40, frequency = 2412)  // channel 1
        )

        val scores = ChannelAnalyzer.analyze(aps)

        assertEquals(listOf(1, 11), scores.map { it.channel })
    }

    @Test
    fun `analyze on empty list returns empty scores`() {
        assertEquals(emptyList<ChannelScore>(), ChannelAnalyzer.analyze(emptyList()))
    }

    @Test
    fun `bestChannel picks the least congested channel in the requested band`() {
        val scores = listOf(
            ChannelScore(channel = 1, band = WifiBand.BAND_2_4GHZ, apCount = 5, avgRssi = -50),
            ChannelScore(channel = 6, band = WifiBand.BAND_2_4GHZ, apCount = 1, avgRssi = -50),
            ChannelScore(channel = 11, band = WifiBand.BAND_2_4GHZ, apCount = 3, avgRssi = -50),
            ChannelScore(channel = 36, band = WifiBand.BAND_5GHZ, apCount = 0, avgRssi = -50)
        )

        assertEquals(6, ChannelAnalyzer.bestChannel(scores, WifiBand.BAND_2_4GHZ))
    }

    @Test
    fun `bestChannel returns null when no scores exist for the band`() {
        val scores = listOf(
            ChannelScore(channel = 1, band = WifiBand.BAND_2_4GHZ, apCount = 5, avgRssi = -50)
        )
        assertNull(ChannelAnalyzer.bestChannel(scores, WifiBand.BAND_6GHZ))
    }
}
