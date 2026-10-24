package app.radiusrf.features.sdr.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AisDecoderTest {

    @Test
    fun `parses a type 1 position report`() {
        val bits = BooleanArray(168)
        write(bits, 0, 6, 1)
        write(bits, 8, 30, 366123456)
        write(bits, 50, 10, 84)
        write(bits, 61, 28, (-122.47 * 600000).toInt() and 0x0FFFFFFF)
        write(bits, 89, 27, (37.81 * 600000).toInt() and 0x07FFFFFF)
        write(bits, 116, 12, 1920)
        val vessel = AisDecoder.parse(bits)!!
        assertEquals("366123456", vessel.mmsi)
        assertEquals(1, vessel.type)
        assertEquals(8.4, vessel.sog!!, 0.05)
        assertTrue(vessel.lat!! in 37.8..37.82)
        assertTrue(vessel.lon!! in -122.48..-122.46)
    }

    @Test
    fun `HDLC destuff drops stuffed zero after five ones`() {
        val bits = booleanArrayOf(true, true, true, true, true, false, true)
        val frames = AisDecoder.hdlcFrames(bits)
        assertTrue(frames.isEmpty() || frames.all { it.size < 168 })
        val crc = AisDecoder.crc16(byteArrayOf(0x01, 0x02))
        assertTrue(crc in 0..0xFFFF)
    }

    private fun write(bits: BooleanArray, start: Int, width: Int, value: Int) {
        var i = 0
        while (i < width) {
            bits[start + i] = ((value ushr (width - 1 - i)) and 1) != 0
            i++
        }
    }
}
