package app.radiusrf.features.run_compare.domain

import app.radiusrf.features.wardriving.domain.SurveyRadio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunCompareTest {

    @Test
    fun `a device that shows up on later legs is a match`() {
        val follower = "AA:BB:CC:DD:EE:01"
        val sightings = listOf(
            sighting("leg-a", follower, 38.9000, -77.0400, name = "CarHotspot"),
            sighting("leg-b", follower, 38.9000, -77.0000),
            sighting("leg-c", follower, 38.8600, -77.0000)
        )
        val matches = RunCompare.matches(sightings)
        assertEquals(1, matches.size)
        assertEquals(3, matches[0].runIds.size)
        assertEquals("CarHotspot", matches[0].label)
        assertTrue(matches[0].spanMeters > 1_000.0)
    }

    @Test
    fun `a radio that stays at one stop is left out`() {
        val shop = "11:22:33:44:55:66"
        val sightings = listOf(
            sighting("leg-a", shop, 38.9000, -77.0400, name = "CafeWiFi"),
            sighting("leg-b", shop, 38.9002, -77.0401)
        )
        assertTrue(RunCompare.matches(sightings).isEmpty())
    }

    @Test
    fun `one run is not a comparison`() {
        val sightings = listOf(
            sighting("leg-a", "AA:BB:CC:DD:EE:02", 38.90, -77.04),
            sighting("leg-a", "AA:BB:CC:DD:EE:02", 38.95, -77.10)
        )
        assertTrue(RunCompare.matches(sightings).isEmpty())
    }

    @Test
    fun `cell ids are not treated as a following device`() {
        val sightings = listOf(
            sighting("leg-a", "310-410-123-456", 38.90, -77.04, radio = "LTE"),
            sighting("leg-b", "310-410-123-456", 38.80, -76.90, radio = "LTE")
        )
        assertTrue(RunCompare.matches(sightings).isEmpty())
    }

    @Test
    fun `mac addresses match regardless of case`() {
        val sightings = listOf(
            sighting("leg-a", "aa:bb:cc:dd:ee:03", 38.90, -77.04),
            sighting("leg-b", "AA:BB:CC:DD:EE:03", 38.90, -77.00)
        )
        val matches = RunCompare.matches(sightings)
        assertEquals(1, matches.size)
        assertEquals("aa:bb:cc:dd:ee:03", matches[0].mac)
    }

    @Test
    fun `paged totals keep a traveler and drop a radio that stays put`() {
        val follower = MacStats()
        follower.add(sighting("leg-a", "AA:BB:CC:DD:EE:01", 38.90, -77.04, name = "CarHotspot"))
        follower.add(sighting("leg-b", "aa:bb:cc:dd:ee:01", 38.90, -77.00))
        assertTrue(follower.qualifies())
        assertEquals("CarHotspot", follower.label)
        assertEquals(2, follower.count)
        val shop = MacStats()
        shop.add(sighting("leg-a", "11:22:33:44:55:66", 38.9000, -77.0400))
        shop.add(sighting("leg-b", "11:22:33:44:55:66", 38.9002, -77.0401))
        assertTrue(shop.qualifies().not())
        val tower = MacStats()
        tower.add(sighting("leg-a", "310-410-1", 38.90, -77.04, radio = "LTE"))
        tower.add(sighting("leg-b", "310-410-1", 38.80, -76.90, radio = "LTE"))
        assertTrue(tower.qualifies().not())
    }

    @Test
    fun `track drops fixes that barely moved`() {
        val sightings = listOf(
            sighting("leg-a", "AA:BB:CC:DD:EE:04", 38.90000, -77.04000, time = 1),
            sighting("leg-a", "AA:BB:CC:DD:EE:04", 38.90001, -77.04000, time = 2),
            sighting("leg-a", "AA:BB:CC:DD:EE:04", 38.90200, -77.04000, time = 3)
        )
        val track = RunCompare.track(sightings)
        assertEquals(2, track.size)
    }

    private fun sighting(
        session: String,
        mac: String,
        lat: Double,
        lng: Double,
        radio: String = SurveyRadio.WIFI,
        name: String? = null,
        time: Long = 0L
    ) = CompareSighting(
        sessionId = session,
        mac = mac,
        name = name,
        radio = radio,
        rssi = -60,
        frequency = 2412,
        capabilities = null,
        lat = lat,
        lng = lng,
        timestamp = time,
        altitude = 0.0,
        accuracy = 5.0
    )
}
