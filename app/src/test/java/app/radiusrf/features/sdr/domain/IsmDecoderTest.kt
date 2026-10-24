package app.radiusrf.features.sdr.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IsmDecoderTest {

    @Test
    fun `extracts OOK pulses from a synthetic burst`() {
        val rate = 1_000_000
        val iq = ByteArray(2_000)
        var i = 0
        repeat(8) { pulse ->
            val high = pulse % 2 == 0
            repeat(40) {
                iq[i++] = if (high) 200.toByte() else 128.toByte()
                iq[i++] = 128.toByte()
            }
        }
        val train = IsmDecoder.extract(iq, i, signed = false, sampleRate = rate)
        assertNotNull(train)
        assertTrue(train!!.widthsUs.size >= 3)
        val burst = IsmDecoder.classify(train, 433_920_000L, now = 1L)
        assertEquals("OOK", burst.modulation)
        assertTrue(burst.family.isNotBlank())
        assertTrue(burst.pulses >= 3)
    }

    @Test
    fun `short pulses classify as TPMS family`() {
        val train = IsmDecoder.PulseTrain(List(32) { if (it % 2 == 0) 80 else 90 }, fsk = false)
        val burst = IsmDecoder.classify(train, 315_000_000L, now = 2L)
        assertEquals("TPMS / short sensor", burst.family)
        assertEquals(32, burst.pulses)
        assertEquals("OOK", burst.modulation)
    }
}
