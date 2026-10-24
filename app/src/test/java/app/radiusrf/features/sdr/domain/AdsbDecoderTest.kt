package app.radiusrf.features.sdr.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdsbDecoderTest {

    @Test
    fun `constructed identification frame parses callsign and ICAO`() {
        val payload = BooleanArray(56)
        write(payload, 0, 5, 4)
        val letters = "UAL123  "
        letters.forEachIndexed { index, ch ->
            val table = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ#####_###############0123456789######"
            write(payload, 8 + index * 6, 6, table.indexOf(ch).coerceAtLeast(0))
        }
        val bits = AdsbDecoder.frame(17, 0xA1B2C3, payload)
        assertTrue(AdsbDecoder.crcOk(bits))
        val frame = AdsbDecoder.parse(bits)
        assertEquals("A1B2C3", frame?.icao)
        assertEquals("UAL123", frame?.callsign)
        assertEquals(17, frame?.df)
    }

    private fun write(bits: BooleanArray, start: Int, width: Int, value: Int) {
        var i = 0
        while (i < width) {
            bits[start + i] = ((value ushr (width - 1 - i)) and 1) != 0
            i++
        }
    }
}
