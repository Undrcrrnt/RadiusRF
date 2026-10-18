package app.radiusrf.features.camera.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrThreatAnalyzerTest {

    @Test
    fun `non-url content types are never flagged`() {
        val (suspicious, reason) = QrThreatAnalyzer.analyze("http://evil.tk", QrContentType.TEXT)
        assertFalse(suspicious)
        assertNull(reason)
    }

    @Test
    fun `flags IP-address urls`() {
        val (suspicious, reason) = QrThreatAnalyzer.analyze("http://192.168.1.1/login", QrContentType.URL)
        assertTrue(suspicious)
        assertEquals("URL uses IP address instead of domain name", reason)
    }

    @Test
    fun `flags suspicious TLD`() {
        val (suspicious, reason) = QrThreatAnalyzer.analyze("http://free-prize.xyz", QrContentType.URL)
        assertTrue(suspicious)
        assertTrue(reason!!.contains(".xyz"))
    }

    @Test
    fun `flags phishing pattern in path`() {
        val (suspicious, reason) = QrThreatAnalyzer.analyze(
            "https://secure-login.com/verify",
            QrContentType.URL
        )
        assertTrue(suspicious)
        assertTrue(reason!!.contains("phishing"))
    }

    @Test
    fun `flags extremely long urls`() {
        val longUrl = "https://example.com/" + "a".repeat(500)
        val (suspicious, reason) = QrThreatAnalyzer.analyze(longUrl, QrContentType.URL)
        assertTrue(suspicious)
        assertTrue(reason!!.contains("Unusually long URL"))
    }

    @Test
    fun `does not flag an ordinary safe url`() {
        val (suspicious, reason) = QrThreatAnalyzer.analyze("https://www.google.com/search?q=test", QrContentType.URL)
        assertFalse(suspicious)
        assertNull(reason)
    }
}
