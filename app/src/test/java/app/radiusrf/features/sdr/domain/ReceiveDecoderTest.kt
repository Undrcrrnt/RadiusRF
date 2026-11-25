package app.radiusrf.features.sdr.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiveDecoderTest {

    @Test
    fun `aprs audio round trip keeps the call and the text`() {
        val audio = AprsDecoder.synthesize("W1AW-1", "APRS", ">hello")
        val packets = AprsDecoder.decodeAudio(audio, audio.size, 48_000)
        assertTrue(packets.isNotEmpty())
        assertEquals("W1AW-1", packets.first().from)
        assertEquals("APRS", packets.first().to)
        assertEquals(">hello", packets.first().info)
    }

    @Test
    fun `pocsag batch returns the address and the digits`() {
        val bits = PocsagDecoder.encodeBatch(address = 8, function = 0, digits = "1234")
        val messages = PocsagDecoder.decodeBits(bits, 1200)
        assertEquals(1, messages.size)
        assertEquals(8, messages.first().address)
        assertTrue(messages.first().text.contains("1234"))
    }

    @Test
    fun `acars keeps a parity checked printable block`() {
        val bits = ArrayList<Boolean>()
        ">TEST01".forEach { ch ->
            var value = ch.code and 0x7F
            if (Integer.bitCount(value) % 2 == 0) value = value or 0x80
            var bit = 0
            while (bit < 8) {
                bits += ((value ushr bit) and 1) != 0
                bit++
            }
        }
        val messages = AcarsDecoder.messages(bits)
        assertEquals(">TEST01", messages.single().text)
    }
}
