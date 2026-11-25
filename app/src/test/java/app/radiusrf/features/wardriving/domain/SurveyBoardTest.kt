package app.radiusrf.features.wardriving.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyBoardTest {

    @Test
    fun `a repeated address counts once and a new row still adds to the log`() {
        val board = SurveyBoard()
        board.note(wifi("aa:bb:cc:00:00:01", "[ESS]", time = 1))
        board.note(wifi("AA:BB:CC:00:00:01", "[ESS]", time = 2))
        board.addWritten(2)
        val live = board.snapshot(startTime = 0, now = 5_000)
        assertEquals(1, live.wifiNetworks)
        assertEquals(1, live.stats.openCount)
        assertEquals(0, live.stats.securedCount)
        assertEquals(2, live.stats.totalRecords)
    }

    @Test
    fun `clients and cells stay out of the network count`() {
        val board = SurveyBoard()
        board.note(wifi("aa:bb:cc:00:00:02", "[WPA2-PSK-CCMP][ESS]"))
        board.note(
            WardrivingRecord(
                bssid = "11:22:33:44:55:66",
                ssid = "Cafe",
                rssi = -40,
                capabilities = "aa:bb:cc:00:00:02",
                lat = 1.0,
                lng = 2.0,
                radio = SurveyRadio.CLIENT
            )
        )
        board.note(
            WardrivingRecord(
                bssid = "310-260-1-2",
                ssid = "Carrier",
                rssi = -90,
                lat = 1.0,
                lng = 2.0,
                radio = "LTE"
            )
        )
        val live = board.snapshot(0)
        assertEquals(1, live.wifiNetworks)
        assertEquals(1, live.clientDevices)
        assertEquals(1, live.cells)
        assertEquals(1, live.clients.size)
        assertEquals(1, live.stats.securedCount)
        assertTrue(live.networks.none { it.isClient })
    }

    @Test
    fun `the published network list keeps the newest addresses`() {
        val board = SurveyBoard()
        repeat(SurveyBoard.DISPLAY_NETWORKS + 10) { index ->
            board.note(wifi("02:00:00:00:00:%02X".format(index), "[WPA2-PSK-CCMP][ESS]", rssi = -40 - index, time = index.toLong()))
        }
        val live = board.snapshot(0)
        assertEquals(SurveyBoard.DISPLAY_NETWORKS, live.networks.size)
        assertEquals((SurveyBoard.DISPLAY_NETWORKS + 9).toLong(), live.networks.first().timestamp)
        assertEquals(SurveyBoard.DISPLAY_NETWORKS + 10, live.wifiNetworks)
    }

    @Test
    fun `the published client list keeps the strongest addresses`() {
        val board = SurveyBoard()
        repeat(SurveyBoard.DISPLAY_CLIENTS + 5) { index ->
            board.note(
                WardrivingRecord(
                    bssid = "10:00:00:00:00:%02X".format(index),
                    ssid = "Ap",
                    rssi = -20 - index,
                    capabilities = "aa:bb:cc:00:00:01",
                    lat = 1.0,
                    lng = 2.0,
                    radio = SurveyRadio.CLIENT
                )
            )
        }
        val live = board.snapshot(0)
        assertEquals(SurveyBoard.DISPLAY_CLIENTS, live.clients.size)
        assertEquals(-20, live.clients.first().rssi)
        assertEquals(SurveyBoard.DISPLAY_CLIENTS + 5, live.clientDevices)
    }

    private fun wifi(mac: String, capabilities: String, rssi: Int = -50, time: Long = 0) = WardrivingRecord(
        bssid = mac,
        ssid = "Net",
        rssi = rssi,
        frequency = 2412,
        capabilities = capabilities,
        lat = 1.0,
        lng = 2.0,
        timestamp = time
    )
}
