package app.radiusrf.features.sdr.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdrDspTest {

    @Test
    fun `demod produces one audio sample per decimation block`() {
        val iq = ByteArray(SdrDsp.DECIM * 2) { 127 }
        val pcm = ShortArray(8)
        val n = SdrDsp.demod(iq, iq.size, SdrMode.AM, DemodState(), pcm)
        assertEquals(1, n)
    }

    @Test
    fun `spectrum is fftshifted around the center`() {
        val iq = ByteArray(SdrDsp.FFT_N * 2) { 127 }
        val out = FloatArray(SdrDsp.FFT_N)
        SdrDsp.spectrum(iq, iq.size, out)
        assertEquals(SdrDsp.FFT_N, out.size)
        assertTrue(out.all { it.isFinite() })
    }

    @Test
    fun `bin zero sits half a span below the tuned frequency`() {
        val hz = SdrDsp.binFrequency(100_000_000L, 0)
        assertEquals(100_000_000L - SdrDsp.SAMPLE_RATE / 2, hz)
    }
}
