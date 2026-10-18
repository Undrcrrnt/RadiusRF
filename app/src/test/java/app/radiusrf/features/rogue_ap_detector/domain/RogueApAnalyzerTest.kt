package app.radiusrf.features.rogue_ap_detector.domain

import app.radiusrf.features.wifi.domain.WifiAccessPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RogueApAnalyzerTest {

    private val analyzer = RogueApAnalyzer()

    private fun ap(
        ssid: String,
        bssid: String,
        rssi: Int = -50,
        capabilities: String = "[WPA2-PSK][ESS]",
        frequency: Int = 2437
    ) = WifiAccessPoint(ssid = ssid, bssid = bssid, rssi = rssi, frequency = frequency, capabilities = capabilities)

    @Test
    fun `flags evil twin when same ssid has mismatched security from a different manufacturer`() {
        val legitimate = ap("HomeWifi", "AA:BB:CC:11:11:11", rssi = -40, capabilities = "[WPA2-PSK][ESS]")
        val impostor = ap("HomeWifi", "11:22:33:99:99:99", rssi = -70, capabilities = "[ESS]") // open

        val alerts = analyzer.analyze(listOf(legitimate, impostor))

        val evilTwin = alerts.single { it.threatType == ApThreatType.EVIL_TWIN }
        assertEquals(RiskLevel.CRITICAL, evilTwin.riskLevel)
        assertEquals(impostor.bssid, evilTwin.suspiciousAp.bssid)
        assertEquals(legitimate.bssid, evilTwin.legitimateAp?.bssid)
    }

    @Test
    fun `a single access point never triggers evil twin detection`() {
        val ap1 = ap("HomeWifi", "AA:BB:CC:11:11:11", rssi = -40)
        val alerts = analyzer.analyze(listOf(ap1))
        assertTrue(alerts.none { it.threatType == ApThreatType.EVIL_TWIN })
    }

    @Test
    fun `flags open network impersonating a known public ssid`() {
        val starbucks = ap("Starbucks", "AA:BB:CC:11:11:11", capabilities = "[ESS]")
        val alerts = analyzer.analyze(listOf(starbucks))
        val alert = alerts.single { it.threatType == ApThreatType.OPEN_IMPERSONATOR }
        assertEquals(RiskLevel.HIGH, alert.riskLevel)
    }

    @Test
    fun `does not flag open impersonator for an unrelated ssid`() {
        val privateNetwork = ap("MyPrivateNetXYZ987", "AA:BB:CC:11:11:11", capabilities = "[ESS]")
        val alerts = analyzer.analyze(listOf(privateNetwork))
        // Still flagged as weak security (open), just not as an impersonator
        assertTrue(alerts.none { it.threatType == ApThreatType.OPEN_IMPERSONATOR })
    }

    @Test
    fun `flags WEP network as medium risk weak security`() {
        val wepAp = ap("OldRouter", "AA:BB:CC:11:11:11", capabilities = "[WEP][ESS]")
        val alerts = analyzer.analyze(listOf(wepAp))
        val alert = alerts.single { it.threatType == ApThreatType.WEAK_SECURITY }
        assertEquals(RiskLevel.MEDIUM, alert.riskLevel)
    }

    @Test
    fun `known ssids are excluded from weak security alerts`() {
        val openAp = ap("TrustedGuestWifi", "AA:BB:CC:11:11:11", capabilities = "[ESS]")
        val alerts = analyzer.analyze(listOf(openAp), knownSsids = setOf("TrustedGuestWifi"))
        assertTrue(alerts.none { it.threatType == ApThreatType.WEAK_SECURITY })
    }

    @Test
    fun `flags a strong-signal hidden ap as suspicious`() {
        val hidden = ap("<Hidden>", "AA:BB:CC:11:11:11", rssi = -35)
        val alerts = analyzer.analyze(listOf(hidden))
        val alert = alerts.single { it.threatType == ApThreatType.HIDDEN_SUSPICIOUS }
        assertEquals(RiskLevel.MEDIUM, alert.riskLevel)
    }

    @Test
    fun `does not flag a weak-signal hidden ap`() {
        val hidden = ap("<Hidden>", "AA:BB:CC:11:11:11", rssi = -80)
        val alerts = analyzer.analyze(listOf(hidden))
        assertTrue(alerts.none { it.threatType == ApThreatType.HIDDEN_SUSPICIOUS })
    }

    @Test
    fun `flags karma attack when many distinct ssids share the same OUI`() {
        val sameOui = (1..4).map { i ->
            ap("Network$i", "AA:BB:CC:00:00:0$i", capabilities = "[ESS]")
        }
        val alerts = analyzer.analyze(sameOui)
        val karmaAlerts = alerts.filter { it.threatType == ApThreatType.KARMA_ATTACK }
        assertEquals(4, karmaAlerts.size)
        assertTrue(karmaAlerts.all { it.riskLevel == RiskLevel.CRITICAL })
    }

    @Test
    fun `does not flag karma attack for only three distinct ssids on the same OUI`() {
        val sameOui = (1..3).map { i ->
            ap("Network$i", "AA:BB:CC:00:00:0$i", capabilities = "[ESS]")
        }
        val alerts = analyzer.analyze(sameOui)
        assertTrue(alerts.none { it.threatType == ApThreatType.KARMA_ATTACK })
    }

    @Test
    fun `deduplicates multiple alerts for the same bssid keeping the highest risk`() {
        // Open + impersonating a known public SSID -> both WEAK_SECURITY (LOW) and
        // OPEN_IMPERSONATOR (HIGH) would fire for the same BSSID; only one should survive.
        val starbucks = ap("Starbucks", "AA:BB:CC:11:11:11", capabilities = "[ESS]")
        val alerts = analyzer.analyze(listOf(starbucks))

        val alertsForBssid = alerts.filter { it.suspiciousAp.bssid == starbucks.bssid }
        assertEquals(1, alertsForBssid.size)
        assertEquals(RiskLevel.HIGH, alertsForBssid.single().riskLevel)
    }

    @Test
    fun `results are sorted with most critical risk first`() {
        val karma = (1..4).map { i -> ap("Karma$i", "AA:BB:CC:00:00:0$i", capabilities = "[ESS]") }
        val wep = ap("OldRouter", "DD:EE:FF:11:11:11", capabilities = "[WEP][ESS]")

        val alerts = analyzer.analyze(karma + wep)

        assertEquals(RiskLevel.CRITICAL, alerts.first().riskLevel)
    }
}
