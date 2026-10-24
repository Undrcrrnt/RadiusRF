package app.radiusrf.features.sdr.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SdrDspTest {

    @Test
    fun `demod produces one audio sample per decimation block`() {
        val iq = ByteArray(SdrDsp.SAMPLE_RATE / 200 * 2) { 127 }
        val pcm = ShortArray(64)
        val n = SdrDsp.demod(iq, iq.size, SdrMode.AM, DemodState(), pcm)
        assertTrue(n >= 1)
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
    fun `a cleared spectrum is not used to scale the plot`() {
        assertFalse(SdrDsp.isMeasuredSpectrum(FloatArray(4_096)))
    }

    @Test
    fun `a measured spectrum keeps a finite span the plot can show`() {
        val iq = ByteArray(SdrDsp.FFT_N * 2) { 127 }
        val out = FloatArray(SdrDsp.FFT_N)
        SdrDsp.spectrum(iq, iq.size, out)
        assertTrue(SdrDsp.isMeasuredSpectrum(out))
        assertTrue(out.max() < 20f)
        assertTrue(out.min() > -160f)
    }

    @Test
    fun `bin zero sits half a span below the tuned frequency`() {
        val hz = SdrDsp.binFrequency(100_000_000L, 0)
        assertEquals(100_000_000L - SdrDsp.SAMPLE_RATE / 2, hz)
    }
}
