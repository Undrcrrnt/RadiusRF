package app.radiusrf.features.locate.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocateTrackerTest {

    @Test
    fun `packet comparison reports closer`() {
        val samples = listOf(
            LocateSample(2_000, -80),
            LocateSample(3_000, -79),
            LocateSample(8_500, -65),
            LocateSample(9_500, -64)
        )
        assertEquals(LocateCue.CLOSER, LocateSignals.packets(samples, 10_000, 9_500, missing = false))
    }

    @Test
    fun `packet comparison reports further`() {
        val samples = listOf(
            LocateSample(2_000, -55),
            LocateSample(3_000, -56),
            LocateSample(8_500, -75),
            LocateSample(9_500, -76)
        )
        assertEquals(LocateCue.FURTHER, LocateSignals.packets(samples, 10_000, 9_500, missing = false))
    }

    @Test
    fun `quiet and missing states are distinct`() {
        val samples = listOf(LocateSample(1_000, -70))
        assertEquals(LocateCue.QUIET, LocateSignals.packets(samples, 10_000, 1_000, missing = false))
        assertEquals(LocateCue.GONE, LocateSignals.packets(samples, 10_000, 1_000, missing = true))
    }

    @Test
    fun `wifi sweeps compare consecutive observations`() {
        assertEquals(
            LocateCue.CLOSER,
            LocateSignals.sweeps(
                listOf(LocateSample(1_000, -80), LocateSample(9_000, -65)),
                now = 9_000,
                lastSeen = 9_000,
                missing = false
            )
        )
    }

    @Test
    fun `tick interval scales with signal and stops when quiet`() {
        assertTrue(
            LocateSignals.tickIntervalMs(-40, LocateCue.CLOSER)!! <
                LocateSignals.tickIntervalMs(-90, LocateCue.CLOSER)!!
        )
        assertNull(LocateSignals.tickIntervalMs(-40, LocateCue.QUIET))
    }
}
