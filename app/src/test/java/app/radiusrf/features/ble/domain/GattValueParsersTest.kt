package app.radiusrf.features.ble.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GattValueParsersTest {

    @Test
    fun `parse returns null for empty data`() {
        assertNull(GattValueParsers.parse("00002a19-0000-1000-8000-00805f9b34fb", byteArrayOf()))
    }

    @Test
    fun `parse returns null for unknown characteristic uuid`() {
        assertNull(GattValueParsers.parse("0000ffff-0000-1000-8000-00805f9b34fb", byteArrayOf(0x01)))
    }

    @Test
    fun `parse dispatches battery level by uuid`() {
        val result = GattValueParsers.parse(
            "00002a19-0000-1000-8000-00805f9b34fb",
            byteArrayOf(0x55)
        )
        assertEquals("Battery: 85%", result)
    }

    @Test
    fun `parse matches uuid case-insensitively`() {
        val result = GattValueParsers.parse(
            "00002A19-0000-1000-8000-00805F9B34FB",
            byteArrayOf(0x32)
        )
        assertEquals("Battery: 50%", result)
    }

    @Test
    fun `parse dispatches 8-bit heart rate`() {
        // flags=0x00 (8-bit, no energy, no RR), heart rate=72
        val result = GattValueParsers.parse(
            "00002a37-0000-1000-8000-00805f9b34fb",
            byteArrayOf(0x00, 72)
        )
        assertEquals("Heart Rate: 72 bpm", result)
    }

    @Test
    fun `parse dispatches 16-bit heart rate`() {
        // flags=0x01 (16-bit flag set), heart rate=300 (0x012C, little-endian)
        val result = GattValueParsers.parse(
            "00002a37-0000-1000-8000-00805f9b34fb",
            byteArrayOf(0x01, 0x2C, 0x01)
        )
        assertEquals("Heart Rate: 300 bpm (16-bit)", result)
    }

    @Test
    fun `parse dispatches device name as utf8 string`() {
        val result = GattValueParsers.parse(
            "00002a00-0000-1000-8000-00805f9b34fb",
            "RadiusRF".toByteArray(Charsets.UTF_8)
        )
        assertEquals("RadiusRF", result)
    }

    @Test
    fun `readUInt16LE reads little-endian unsigned 16-bit value`() {
        val data = byteArrayOf(0x34, 0x12) // 0x1234 = 4660
        assertEquals(4660, GattValueParsers.readUInt16LE(data, 0))
    }

    @Test
    fun `readUInt16LE returns 0 when out of bounds`() {
        assertEquals(0, GattValueParsers.readUInt16LE(byteArrayOf(0x01), 0))
    }

    @Test
    fun `readInt16LE interprets values above 0x8000 as negative`() {
        // 0xFFFF little-endian = -1 as signed 16-bit
        val data = byteArrayOf(0xFF.toByte(), 0xFF.toByte())
        assertEquals(-1, GattValueParsers.readInt16LE(data, 0))
    }

    @Test
    fun `readInt16LE leaves positive values unchanged`() {
        val data = byteArrayOf(0x64, 0x00) // 100
        assertEquals(100, GattValueParsers.readInt16LE(data, 0))
    }

    @Test
    fun `readUInt32LE reads little-endian unsigned 32-bit value`() {
        val data = byteArrayOf(0x78, 0x56, 0x34, 0x12) // 0x12345678
        assertEquals(0x12345678L, GattValueParsers.readUInt32LE(data, 0))
    }

    @Test
    fun `parse dispatches tx power level as signed byte`() {
        val result = GattValueParsers.parse(
            "00002a07-0000-1000-8000-00805f9b34fb",
            byteArrayOf((-20).toByte())
        )
        assertEquals("Tx Power: -20 dBm", result)
    }

    @Test
    fun `parse dispatches body sensor location`() {
        val result = GattValueParsers.parse(
            "00002a38-0000-1000-8000-00805f9b34fb",
            byteArrayOf(0x02)
        )
        assertEquals("Body Sensor Location: Wrist", result)
    }
}
