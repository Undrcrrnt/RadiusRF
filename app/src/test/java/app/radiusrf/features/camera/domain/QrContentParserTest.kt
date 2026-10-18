package app.radiusrf.features.camera.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrContentParserTest {

    @Test
    fun `parses http url`() {
        val (type, value) = QrContentParser.parse("http://example.com")
        assertEquals(QrContentType.URL, type)
        assertEquals("http://example.com", value)
    }

    @Test
    fun `parses https url`() {
        val (type, _) = QrContentParser.parse("https://example.com")
        assertEquals(QrContentType.URL, type)
    }

    @Test
    fun `parses wifi config with ssid password and type`() {
        val (type, value) = QrContentParser.parse("WIFI:S:MyNetwork;P:secret123;T:WPA;;")
        assertEquals(QrContentType.WIFI, type)
        assertTrue(value.contains("SSID: MyNetwork"))
        assertTrue(value.contains("Password: secret123"))
        assertTrue(value.contains("Type: WPA"))
    }

    @Test
    fun `wifi config missing a field yields blank for that field`() {
        val (_, value) = QrContentParser.parse("WIFI:S:OpenNet;T:nopass;;")
        assertTrue(value.contains("SSID: OpenNet"))
        assertTrue(value.contains("Password: "))
    }

    @Test
    fun `parses vcard as-is`() {
        val raw = "BEGIN:VCARD\nFN:Jane Doe\nEND:VCARD"
        val (type, value) = QrContentParser.parse(raw)
        assertEquals(QrContentType.VCARD, type)
        assertEquals(raw, value)
    }

    @Test
    fun `parses lowercase mailto`() {
        val (type, value) = QrContentParser.parse("mailto:test@example.com")
        assertEquals(QrContentType.EMAIL, type)
        assertEquals("test@example.com", value)
    }

    @Test
    fun `parses uppercase MAILTO`() {
        val (type, value) = QrContentParser.parse("MAILTO:test@example.com")
        assertEquals(QrContentType.EMAIL, type)
        assertEquals("test@example.com", value)
    }

    @Test
    fun `parses tel`() {
        val (type, value) = QrContentParser.parse("tel:+15551234567")
        assertEquals(QrContentType.PHONE, type)
        assertEquals("+15551234567", value)
    }

    @Test
    fun `parses smsto`() {
        val (type, value) = QrContentParser.parse("smsto:+15551234567")
        assertEquals(QrContentType.SMS, type)
        assertEquals("+15551234567", value)
    }

    @Test
    fun `parses geo`() {
        val (type, value) = QrContentParser.parse("geo:37.7749,-122.4194")
        assertEquals(QrContentType.GEO, type)
        assertEquals("37.7749,-122.4194", value)
    }

    @Test
    fun `falls back to plain text`() {
        val (type, value) = QrContentParser.parse("just some text")
        assertEquals(QrContentType.TEXT, type)
        assertEquals("just some text", value)
    }
}
