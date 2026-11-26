package app.radiusrf.features.connection_log.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PacketCodecTest {

    @Test
    fun `ipv4 header checksum matches the standard vector`() {
        val header = byteArrayOf(
            0x45, 0x00, 0x00, 0x3c, 0x1c, 0x46, 0x40, 0x00, 0x40, 0x06,
            0x00, 0x00,
            0xac.toByte(), 0x10, 0x0a, 0x63,
            0xac.toByte(), 0x10, 0x0a, 0x0c
        )
        assertEquals(0xb1e6, PacketCodec.checksum(header, 0, 20, 0))
    }

    @Test
    fun `built tcp packet round trips and its checksums match`() {
        val source = byteArrayOf(10, 251.toByte(), 1, 1)
        val destination = byteArrayOf(1, 2, 3, 4)
        val packet = PacketCodec.tcpPacket(
            source = source,
            destination = destination,
            sourcePort = 40000,
            destinationPort = 443,
            sequence = 100,
            acknowledgment = 200,
            flags = 0x18,
            payload = "hello".toByteArray()
        )
        val ip = PacketCodec.ipv4(packet)
        assertNotNull(ip)
        val header = packet.copyOf(20)
        val stored = ((packet[10].toInt() and 0xFF) shl 8) or (packet[11].toInt() and 0xFF)
        header[10] = 0
        header[11] = 0
        assertEquals(stored, PacketCodec.checksum(header, 0, 20, 0))

        val tcp = PacketCodec.tcp(packet, ip!!)
        assertEquals(443, tcp?.destinationPort)
        assertEquals("hello", tcp?.payload?.toString(Charsets.US_ASCII))
    }

    @Test
    fun `dns answers map an address to a name`() {
        val payload = byteArrayOf(
            0, 1, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 0,
            3, 'w'.code.toByte(), 'w'.code.toByte(), 'w'.code.toByte(),
            7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
            3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0,
            0, 1, 0, 1,
            0xC0.toByte(), 12,
            0, 1, 0, 1,
            0, 0, 0, 60,
            0, 4,
            1, 2, 3, 4
        )
        assertEquals(mapOf("1.2.3.4" to "www.example.com"), PacketCodec.dnsAnswers(payload))
    }

    @Test
    fun `server name is read from a client hello`() {
        val name = "example.com".toByteArray()
        val hello = ArrayList<Byte>()
        fun add(vararg values: Int) { values.forEach { hello += it.toByte() } }
        add(0x16, 0x03, 0x03, 0, 0)
        add(0x01, 0, 0, 0)
        add(0x03, 0x03)
        repeat(32) { add(0x11) }
        add(0)
        add(0, 2, 0x00, 0x2f)
        add(1, 0)
        val listLength = 3 + name.size
        val extensionLength = 2 + listLength
        add(0, extensionLength)
        add(0, 0, 0, extensionLength)
        add(0, listLength, 0, 0, name.size)
        name.forEach { hello += it }
        val recordLength = hello.size - 5
        hello[3] = (recordLength shr 8).toByte()
        hello[4] = recordLength.toByte()
        assertEquals("example.com", PacketCodec.serverName(hello.toByteArray()))
    }
}
