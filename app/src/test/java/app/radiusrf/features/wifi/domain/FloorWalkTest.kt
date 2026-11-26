package app.radiusrf.features.wifi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FloorWalkTest {

    @Test
    fun `strength runs from weak to strong`() {
        assertEquals(0f, FloorWalkMath.strength(-100), 0.001f)
        assertEquals(1f, FloorWalkMath.strength(-30), 0.001f)
        assertEquals(0f, FloorWalkMath.strength(-120), 0.001f)
        assertTrue(FloorWalkMath.strength(-65) in 0.4f..0.6f)
    }

    @Test
    fun `unknown network names are dropped`() {
        assertNull(FloorWalkMath.cleanSsid(null))
        assertNull(FloorWalkMath.cleanSsid("  "))
        assertNull(FloorWalkMath.cleanSsid("<unknown ssid>"))
        assertEquals("Home", FloorWalkMath.cleanSsid("\"Home\""))
    }

    @Test
    fun `a point is kept when the phone moves or the signal steps`() {
        assertTrue(FloorWalkMath.shouldStore(null, 0.0, 0.0, -60, 1_000))
        val first = sample(x = 0.0, z = 0.0, rssi = -60, timeMs = 1_000)
        assertFalse(FloorWalkMath.shouldStore(first, 0.1, 0.0, -60, 1_200))
        assertTrue(FloorWalkMath.shouldStore(first, 0.25, 0.0, -60, 1_200))
        assertTrue(FloorWalkMath.shouldStore(first, 0.06, 0.0, -60, 2_200))
        assertFalse(FloorWalkMath.shouldStore(first, 0.0, 0.0, -62, 3_000))
        assertTrue(FloorWalkMath.shouldStore(first, 0.0, 0.0, -70, 3_000))
        assertFalse(FloorWalkMath.shouldStore(first, Double.NaN, 0.0, -60, 3_000))
    }

    @Test
    fun `a roam is a change of access point`() {
        val first = sample(bssid = "AA:BB:CC:DD:EE:01")
        assertFalse(FloorWalkMath.markRoam(null, first).roam)
        assertFalse(FloorWalkMath.markRoam(first, first.copy(timeMs = 2)).roam)
        assertFalse(FloorWalkMath.markRoam(first, first.copy(bssid = "aa:bb:cc:dd:ee:01")).roam)
        assertTrue(FloorWalkMath.markRoam(first, first.copy(bssid = "AA:BB:CC:DD:EE:02")).roam)
        assertFalse(FloorWalkMath.markRoam(first, first.copy(bssid = null)).roam)
    }

    @Test
    fun `heading is yaw around up`() {
        assertEquals(0f, FloorWalkMath.yawDegrees(0f, 0f, 0f, 1f), 0.1f)
        val half = kotlin.math.sqrt(0.5f)
        assertEquals(90f, FloorWalkMath.yawDegrees(0f, half, 0f, half), 0.1f)
    }

    @Test
    fun `a shared sheet names the walk, the scale, and each access point change`() {
        val walk = FloorWalkRecord(
            id = "walk-1",
            startedAt = 50L,
            ssid = "Café",
            samples = listOf(
                sample(bssid = "AA:BB:CC:DD:EE:01"),
                sample(x = 1.5, z = -0.25, rssi = -78, bssid = "AA:BB:CC:DD:EE:02", mhz = 5180, roam = true)
            )
        )
        val sheet = FloorWalkMath.sheet(walk, "Nov 26, 2026, 9:14 AM")
        assertEquals("Café", sheet.title)
        assertEquals("Nov 26, 2026, 9:14 AM · 2 points · 2.4 GHz, 5 GHz", sheet.detail)
        assertEquals("1 m, estimated", sheet.scaleLabel)
        assertEquals(FloorWalkMath.SHEET_NOTE, sheet.note)
        assertEquals(listOf("AA:BB:CC:DD:EE:02 · -78 dBm"), sheet.roams)
    }

    @Test
    fun `a saved walk round trips`() {
        val walk = FloorWalkRecord(
            id = "walk-1",
            startedAt = 50L,
            ssid = "Café",
            samples = listOf(
                sample(bssid = "AA:BB:CC:DD:EE:01", roam = false),
                sample(x = 1.5, z = -0.25, rssi = -78, bssid = "AA:BB:CC:DD:EE:02", mhz = 5180, roam = true)
            )
        )
        val decoded = FloorWalkMath.decode(FloorWalkMath.encode(walk))
        assertEquals(walk, decoded)
    }

    @Test
    fun `scale bar grows with the path`() {
        assertEquals(1.0, FloorWalkMath.scaleBarMetres(4.0), 0.0)
        assertEquals(5.0, FloorWalkMath.scaleBarMetres(30.0), 0.0)
        assertEquals(10.0, FloorWalkMath.scaleBarMetres(80.0), 0.0)
    }

    private fun sample(
        x: Double = 0.0,
        z: Double = 0.0,
        rssi: Int = -60,
        timeMs: Long = 1_000,
        bssid: String? = "AA:BB:CC:DD:EE:01",
        mhz: Int? = 2412,
        roam: Boolean = false
    ) = FloorSample(timeMs, x, z, 0f, rssi, bssid, mhz, roam)
}
