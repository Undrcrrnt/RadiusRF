package app.radiusrf.features.gps.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class NmeaParserTest {

    @Test
    fun `parses GGA fix data`() {
        val result = NmeaParser.parse("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47")
        assertEquals("GGA (GPS)  12:35:19 UTC  48.11730°N 11.51667°E  Fix:GPS  Sats:08  HDOP:0.9  Alt:545.4M", result)
    }

    @Test
    fun `parses RMC recommended minimum`() {
        val result = NmeaParser.parse("\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A")
        assertEquals("RMC (GPS)  12:35:19 UTC  Status:Valid  48.11730°N 11.51667°E  Speed:41.5km/h  Course:084.4°", result)
    }

    @Test
    fun `parses GSA dop and active satellites`() {
        val result = NmeaParser.parse("\$GPGSA,A,3,04,05,,09,12,,,24,,,,,2.5,1.3,2.1*39")
        assertEquals("GSA (GPS)  Fix:3D  Sats used:04,05,09,12,24  PDOP:2.5  HDOP:1.3  VDOP:2.1", result)
    }

    @Test
    fun `parses GSV satellites in view`() {
        val result = NmeaParser.parse("\$GPGSV,3,1,11,03,03,111,00,04,15,270,00,06,01,010,00,13,06,292,00*74")
        assertEquals(
            "GSV (GPS)  Msg 1/3  In view:11  #03 El:03° Az:111° SNR:00dB  #04 El:15° Az:270° SNR:00dB  " +
                "#06 El:01° Az:010° SNR:00dB  #13 El:06° Az:292° SNR:00dB",
            result
        )
    }

    @Test
    fun `parses VTG course and speed`() {
        val result = NmeaParser.parse("\$GPVTG,054.7,T,034.4,M,005.5,N,010.2,K*48")
        assertEquals("VTG (GPS)  Course:054.7° True  Speed:010.2km/h", result)
    }

    @Test
    fun `parses GLL geographic position`() {
        val result = NmeaParser.parse("\$GPGLL,4916.45,N,12311.12,W,225444,A*1D")
        assertEquals("GLL (GPS)  22:54:44 UTC  49.27417°N 123.18533°W  Status:Valid", result)
    }

    @Test
    fun `maps talker ids to constellation names`() {
        val result = NmeaParser.parse("\$GNGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*45")
        assertEquals(true, result.startsWith("GGA (GNSS)"))
    }

    @Test
    fun `falls back to raw text for unsupported sentence types`() {
        val raw = "\$GPZDA,123519,23,03,1994,00,00*61"
        assertEquals(raw, NmeaParser.parse(raw))
    }

    @Test
    fun `falls back to raw text on malformed input`() {
        val raw = "\$GPGGA,not,enough,fields*00"
        assertEquals(raw, NmeaParser.parse(raw))
    }
}
