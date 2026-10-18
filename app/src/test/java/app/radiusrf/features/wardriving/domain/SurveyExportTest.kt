package app.radiusrf.features.wardriving.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyExportTest {

    private val wifi = WardrivingRecord(
        bssid = "AA:BB:CC:DD:EE:FF",
        ssid = "HomeNet",
        rssi = -40,
        frequency = 2437,
        capabilities = "[WPA2-PSK-CCMP][ESS]",
        lat = 37.77,
        lng = -122.41,
        timestamp = 1_000L
    )
    private val ble = wifi.copy(bssid = "A2:14:22:00:00:11", ssid = "Fitbit", radio = SurveyRadio.BLE, frequency = 0, capabilities = "Misc")

    @Test
    fun `kismet csv keeps one row per network with the strongest fix`() {
        val louder = wifi.copy(rssi = -30, lat = 37.78)
        val csv = KismetExporter.csv(listOf(wifi, louder))
        val data = csv.trim().lines().drop(1)
        assertEquals(1, data.size)
        assertTrue(data[0].startsWith("AA:BB:CC:DD:EE:FF,infrastructure,HomeNet,6,"))
        assertTrue(data[0].contains("37.78"))
    }

    @Test
    fun `kismet netxml includes the bssid and gps`() {
        val xml = KismetExporter.netxml(listOf(wifi, ble))
        assertTrue(xml.contains("<BSSID>AA:BB:CC:DD:EE:FF</BSSID>"))
        assertTrue(xml.contains("<essid cloaked=\"false\">HomeNet</essid>"))
        assertTrue(xml.contains("type=\"bt\""))
        assertTrue(xml.contains("<peak-lat>37.77</peak-lat>"))
    }

    @Test
    fun `airodump csv uses the Netseer header and privacy columns`() {
        val csv = AirodumpExporter.export(listOf(wifi))
        assertTrue(csv.startsWith("BSSID, First time seen, Last time seen, channel, Speed, Privacy, Cipher, Authentication, Power, # beacons, # IV, LAN IP, ID-length, ESSID, Key"))
        assertTrue(csv.contains("AA:BB:CC:DD:EE:FF"))
        assertTrue(csv.contains("WPA2, CCMP, PSK"))
        assertTrue(csv.contains("Station MAC, First time seen"))
    }

    @Test
    fun `pcapng starts with a section header and contains the ssid`() {
        val bytes = SurveyPcap.write(listOf(wifi, ble))
        assertEquals(0x0A, bytes[0].toInt() and 0xFF)
        assertEquals(0x0D, bytes[1].toInt() and 0xFF)
        assertEquals(0x0D, bytes[2].toInt() and 0xFF)
        assertEquals(0x0A, bytes[3].toInt() and 0xFF)
        val text = bytes.toString(Charsets.ISO_8859_1)
        assertTrue(text.contains("HomeNet"))
        assertTrue(text.contains("Fitbit"))
    }

    @Test
    fun `cell identity matches the wigle sample shape`() {
        assertEquals("310260_41021_118273301", cellIdentity(310, 260, 41021, 118273301))
    }
}
