package app.radiusrf.features.network_scanner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanDiscoveryTest {

    @Test
    fun `SSDP parser reads location and classifies cameras`() {
        val packet = """
            HTTP/1.1 200 OK
            CACHE-CONTROL: max-age=1800
            LOCATION: http://192.168.1.42:8080/onvif/device_service
            SERVER: Linux/4.9 UPnP/1.0 IpCamera/1.0
            ST: urn:schemas-upnp-org:device:Basic:1
            USN: uuid:camera-1::urn:schemas-upnp-org:device:Basic:1
        """.trimIndent()
        val service = SsdpParser.parse(packet)!!
        assertEquals("SSDP", service.protocol)
        assertEquals("192.168.1.42", service.ip)
        assertEquals(8080, service.port)
        assertEquals("Camera", service.kind)
    }

    @Test
    fun `empty packet is ignored`() {
        assertNull(SsdpParser.parse(""))
        assertNull(SsdpParser.parse("NOTIFY * HTTP/1.1\n"))
    }

    @Test
    fun `mDNS types map to everyday labels`() {
        assertEquals("Cast / TV", LanServiceClassifier.kind("_googlecast._tcp", "Living Room"))
        assertEquals("Printer", LanServiceClassifier.kind("_ipp._tcp", "HP"))
        assertEquals("Smart home", LanServiceClassifier.kind("_hap._tcp", "Hue"))
    }
}
