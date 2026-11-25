package app.radiusrf.features.sdr.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenDroneIdTest {

    @Test
    fun `wifi vendor field and bluetooth pack carry the same messages`() {
        val message = ByteArray(25)
        message[0] = 0x00
        "TESTDRONE".toByteArray().copyInto(message, 2)
        val pack = ByteArray(27)
        pack[0] = 0x0D
        pack[1] = 1
        message.copyInto(pack, 2)
        val vendor = byteArrayOf(0xFA.toByte(), 0x0B, 0xBC.toByte()) + pack

        assertEquals(1, OpenDroneId.messages(pack).size)
        assertEquals(1, OpenDroneId.fromVendorIe(vendor).size)
        val sighting = OpenDroneId.fold(null, "aa:bb:cc:dd:ee:ff", -50, message)
        assertEquals("TESTDRONE", sighting.id)
    }

    @Test
    fun `location message keeps a real coordinate`() {
        val message = ByteArray(25)
        message[0] = 0x01
        val lat = (377749000).toInt()
        val lon = (-1224194000).toInt()
        put(message, 5, lat)
        put(message, 9, lon)
        val sighting = OpenDroneId.fold(null, "wifi", -60, message)
        assertEquals(37.7749, sighting.lat!!, 0.0001)
        assertTrue(sighting.lon!! < 0)
    }

    private fun put(data: ByteArray, offset: Int, value: Int) {
        data[offset] = value.toByte()
        data[offset + 1] = (value shr 8).toByte()
        data[offset + 2] = (value shr 16).toByte()
        data[offset + 3] = (value shr 24).toByte()
    }
}
