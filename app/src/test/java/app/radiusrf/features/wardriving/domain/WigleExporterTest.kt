package app.radiusrf.features.wardriving.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WigleExporterTest {

    private fun record(
        bssid: String = "AA:BB:CC:DD:EE:FF",
        ssid: String? = "HomeNet",
        rssi: Int = -55,
        frequency: Int = 2437,
        capabilities: String? = "[WPA2-PSK-CCMP][ESS]",
        radio: String = SurveyRadio.WIFI
    ) = WardrivingRecord(
        bssid = bssid, ssid = ssid, rssi = rssi, frequency = frequency,
        capabilities = capabilities, lat = 12.9716, lng = 77.5946, timestamp = 0L,
        radio = radio
    )

    private fun rows(csv: String) = csv.trim().lines()

    @Test
    fun `emits WiGLE pre header and column header`() {
        val lines = rows(WigleExporter.export(emptyList()))

        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("WigleWifi-1.6,appRelease=RadiusRf"))
        assertEquals(
            "MAC,SSID,AuthMode,FirstSeen,Channel,Frequency,RSSI,CurrentLatitude,CurrentLongitude,AltitudeMeters,AccuracyMeters,RCOIs,MfgrId,Type",
            lines[1]
        )
    }

    @Test
    fun `writes one row per record with channel derived from frequency`() {
        val csv = WigleExporter.export(listOf(record(), record(bssid = "11:22:33:44:55:66", frequency = 5180)))
        val data = rows(csv).drop(2)

        assertEquals(2, data.size)
        val first = data[0].split(",")
        assertEquals("AA:BB:CC:DD:EE:FF", first[0])
        assertEquals("HomeNet", first[1])
        assertEquals("[WPA2-PSK-CCMP][ESS]", first[2])
        assertEquals("6", first[4])
        assertEquals("2437", first[5])
        assertEquals("-55", first[6])
        assertEquals("12.9716", first[7])
        assertEquals("77.5946", first[8])
        assertEquals("0", first[9])
        assertEquals("0", first[10])
        assertEquals("WIFI", first[13])
        assertEquals("36", data[1].split(",")[4])
    }

    @Test
    fun `null ssid and capabilities become empty columns and unknown bands map to channel 0`() {
        val row = rows(WigleExporter.export(listOf(record(ssid = null, capabilities = null, frequency = 6000)))).last()
        val cols = row.split(",")

        assertEquals("", cols[1])
        assertEquals("", cols[2])
        assertEquals("0", cols[4])
    }

    @Test
    fun `bluetooth and cell rows use the Netseer type column`() {
        val csv = WigleExporter.export(
            listOf(
                record(bssid = "A2:14:22:00:00:11", ssid = "Fitbit", capabilities = "Misc", frequency = 0, radio = SurveyRadio.BLE),
                record(bssid = "310260_41021_118273301", ssid = "T-Mobile USA", capabilities = "LTE;310260", frequency = 1275, radio = "LTE")
            )
        )
        val data = rows(csv).drop(2)
        assertEquals("BLE", data[0].split(",").last())
        assertEquals("", data[0].split(",")[4])
        val cell = data[1].split(",")
        assertEquals("310260_41021_118273301", cell[0])
        assertEquals("1275", cell[4])
        assertEquals("1275", cell[5])
        assertEquals("LTE", cell.last())
    }

    @Test
    fun `stats helpers format percentages and durations`() {
        val stats = WardrivingStats(openCount = 1, securedCount = 3, sessionDurationMs = 125_000L)

        assertEquals(25f, stats.openPercent, 1e-5f)
        assertEquals("2m 5s", stats.formattedDuration)
        assertEquals(0f, WardrivingStats().openPercent, 0f)
    }
}
