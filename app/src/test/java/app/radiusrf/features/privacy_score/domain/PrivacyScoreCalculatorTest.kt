package app.radiusrf.features.privacy_score.domain

import app.radiusrf.features.ble.domain.BleDevice
import app.radiusrf.features.sensors.domain.SensorReading
import app.radiusrf.features.wifi.domain.WifiAccessPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only exercises the pure, framework-free methods on PrivacyScoreCalculator.
 * The remaining methods take a live Context/WifiManager and need instrumentation,
 * not a plain JVM unit test.
 */
class PrivacyScoreCalculatorTest {

    private fun check(status: CheckStatus, weight: Int) = PrivacyCheck(
        category = CheckCategory.DEVICE,
        name = "Test Check",
        status = status,
        detail = "detail",
        weight = weight
    )

    @Test
    fun `all checks passing yields a perfect score`() {
        val checks = listOf(check(CheckStatus.PASS, 5), check(CheckStatus.PASS, 10))
        assertEquals(100, PrivacyScoreCalculator.calculateScore(checks))
    }

    @Test
    fun `all checks failing yields a zero score`() {
        val checks = listOf(check(CheckStatus.FAIL, 5), check(CheckStatus.FAIL, 10))
        assertEquals(0, PrivacyScoreCalculator.calculateScore(checks))
    }

    @Test
    fun `warnings earn half credit`() {
        val checks = listOf(check(CheckStatus.WARNING, 10))
        assertEquals(50, PrivacyScoreCalculator.calculateScore(checks))
    }

    @Test
    fun `empty checklist scores zero rather than dividing by zero`() {
        assertEquals(0, PrivacyScoreCalculator.calculateScore(emptyList()))
    }

    @Test
    fun `category with no checks defaults to a perfect 100`() {
        val scores = PrivacyScoreCalculator.calculateCategoryScores(emptyList())
        assertEquals(100, scores[CheckCategory.WIFI])
        assertEquals(100, scores[CheckCategory.BLUETOOTH])
    }

    @Test
    fun `category score only reflects checks in that category`() {
        val checks = listOf(
            check(CheckStatus.FAIL, 10).copy(category = CheckCategory.WIFI),
            check(CheckStatus.PASS, 10).copy(category = CheckCategory.DEVICE)
        )
        val scores = PrivacyScoreCalculator.calculateCategoryScores(checks)
        assertEquals(0, scores[CheckCategory.WIFI])
        assertEquals(100, scores[CheckCategory.DEVICE])
    }

    @Test
    fun `score to grade boundaries`() {
        assertEquals("A+", PrivacyScoreCalculator.scoreToGrade(95))
        assertEquals("A", PrivacyScoreCalculator.scoreToGrade(85))
        assertEquals("B", PrivacyScoreCalculator.scoreToGrade(70))
        assertEquals("C", PrivacyScoreCalculator.scoreToGrade(55))
        assertEquals("D", PrivacyScoreCalculator.scoreToGrade(40))
        assertEquals("F", PrivacyScoreCalculator.scoreToGrade(39))
    }

    @Test
    fun `checkOpenNetworks passes when none are open`() {
        val aps = listOf(wifiAp("Secure", "WPA2"))
        val result = PrivacyScoreCalculator.checkOpenNetworks(aps)
        assertEquals(CheckStatus.PASS, result.status)
    }

    @Test
    fun `checkOpenNetworks warns for a couple open networks`() {
        val aps = listOf(wifiAp("Open1", "OPEN"), wifiAp("Open2", "OPEN"))
        val result = PrivacyScoreCalculator.checkOpenNetworks(aps)
        assertEquals(CheckStatus.WARNING, result.status)
    }

    @Test
    fun `checkOpenNetworks fails when many are open`() {
        val aps = (1..3).map { wifiAp("Open$it", "OPEN") }
        val result = PrivacyScoreCalculator.checkOpenNetworks(aps)
        assertEquals(CheckStatus.FAIL, result.status)
    }

    @Test
    fun `checkEvilTwins passes when no ssid has mixed security`() {
        val aps = listOf(wifiAp("Net", "WPA2", bssid = "AA:AA:AA:AA:AA:01"))
        val result = PrivacyScoreCalculator.checkEvilTwins(aps)
        assertEquals(CheckStatus.PASS, result.status)
    }

    @Test
    fun `checkEvilTwins warns when the same ssid has different security types`() {
        val aps = listOf(
            wifiAp("Net", "WPA2", bssid = "AA:AA:AA:AA:AA:01"),
            wifiAp("Net", "OPEN", bssid = "BB:BB:BB:BB:BB:02")
        )
        val result = PrivacyScoreCalculator.checkEvilTwins(aps)
        assertEquals(CheckStatus.WARNING, result.status)
    }

    @Test
    fun `checkBleTrackers ignores bookmarked devices`() {
        val devices = listOf(
            BleDevice(name = "AirTag", address = "AA:AA:AA:AA:AA:01", rssi = -50, isBookmarked = true)
        )
        val result = PrivacyScoreCalculator.checkBleTrackers(devices)
        assertEquals(CheckStatus.PASS, result.status)
    }

    @Test
    fun `checkBleTrackers flags an unrecognized airtag by name`() {
        val devices = listOf(
            BleDevice(name = "AirTag", address = "AA:AA:AA:AA:AA:01", rssi = -50, isBookmarked = false)
        )
        val result = PrivacyScoreCalculator.checkBleTrackers(devices)
        assertEquals(CheckStatus.WARNING, result.status)
    }

    @Test
    fun `checkBleTrackers fails with multiple unknown trackers`() {
        val devices = listOf(
            BleDevice(name = "AirTag", address = "AA:AA:AA:AA:AA:01", rssi = -50),
            BleDevice(name = "Tile", address = "AA:AA:AA:AA:AA:02", rssi = -50)
        )
        val result = PrivacyScoreCalculator.checkBleTrackers(devices)
        assertEquals(CheckStatus.FAIL, result.status)
    }

    @Test
    fun `checkBleDeviceCount thresholds`() {
        val few = List(5) { BleDevice(name = null, address = "A$it", rssi = -50) }
        val many = List(25) { BleDevice(name = null, address = "A$it", rssi = -50) }
        val crowded = List(60) { BleDevice(name = null, address = "A$it", rssi = -50) }

        assertEquals(CheckStatus.PASS, PrivacyScoreCalculator.checkBleDeviceCount(few).status)
        assertEquals(CheckStatus.WARNING, PrivacyScoreCalculator.checkBleDeviceCount(many).status)
        assertEquals(CheckStatus.FAIL, PrivacyScoreCalculator.checkBleDeviceCount(crowded).status)
    }

    @Test
    fun `checkMagneticAnomaly passes when magnetometer unavailable`() {
        val reading = SensorReading(name = "magnetometer", isAvailable = false)
        val result = PrivacyScoreCalculator.checkMagneticAnomaly(reading)
        assertEquals(CheckStatus.PASS, result.status)
    }

    @Test
    fun `checkMagneticAnomaly passes for a normal earth field reading`() {
        val reading = SensorReading(
            name = "magnetometer",
            values = floatArrayOf(20f, 20f, 20f), // magnitude ~34.6 uT, within 25-65
            isAvailable = true
        )
        assertEquals(CheckStatus.PASS, PrivacyScoreCalculator.checkMagneticAnomaly(reading).status)
    }

    @Test
    fun `checkMagneticAnomaly fails for a strong anomaly`() {
        val reading = SensorReading(
            name = "magnetometer",
            values = floatArrayOf(200f, 200f, 200f),
            isAvailable = true
        )
        assertEquals(CheckStatus.FAIL, PrivacyScoreCalculator.checkMagneticAnomaly(reading).status)
    }

    @Test
    fun `checkUltrasonicBeacons warns without audio permission`() {
        assertEquals(CheckStatus.WARNING, PrivacyScoreCalculator.checkUltrasonicBeacons(false).status)
    }

    @Test
    fun `checkUltrasonicBeacons passes with audio permission`() {
        assertEquals(CheckStatus.PASS, PrivacyScoreCalculator.checkUltrasonicBeacons(true).status)
    }

    private fun wifiAp(ssid: String, security: String, bssid: String = "AA:AA:AA:AA:AA:01") = WifiAccessPoint(
        ssid = ssid,
        bssid = bssid,
        rssi = -50,
        frequency = 2437,
        capabilities = when (security) {
            "WPA2" -> "[WPA2-PSK][ESS]"
            "OPEN" -> "[ESS]"
            else -> security
        }
    )
}
