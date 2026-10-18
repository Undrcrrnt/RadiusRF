package app.radiusrf.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ByteArrayExtTest {

    @Test
    fun `converts bytes to colon-separated uppercase hex`() {
        val bytes = byteArrayOf(0x0A, 0xFF.toByte(), 0x00, 0x1B)
        assertEquals("0A:FF:00:1B", bytes.toHexString())
    }

    @Test
    fun `empty array produces empty string`() {
        assertEquals("", ByteArray(0).toHexString())
    }

    @Test
    fun `single byte has no separator`() {
        assertEquals("7F", byteArrayOf(0x7F).toHexString())
    }
}
