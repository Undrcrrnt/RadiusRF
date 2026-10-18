package app.radiusrf.features.proximity_radar.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityCalculatorTest {

    @Test
    fun `distance at tx power reference is about 1 meter`() {
        val distance = ProximityCalculator.estimateDistance(rssi = -59, txPower = -59)
        assertEquals(1.0f, distance, 0.05f)
    }

    @Test
    fun `weaker signal estimates greater distance`() {
        val near = ProximityCalculator.estimateDistance(rssi = -50)
        val far = ProximityCalculator.estimateDistance(rssi = -90)
        assertTrue(far > near)
    }

    @Test
    fun `distance is clamped to the 0point1 to 100 meter range`() {
        val veryClose = ProximityCalculator.estimateDistance(rssi = 0)
        val veryFar = ProximityCalculator.estimateDistance(rssi = -200)
        assertEquals(0.1f, veryClose, 0.001f)
        assertEquals(100f, veryFar, 0.001f)
    }

    @Test
    fun `stable angle is deterministic for the same address`() {
        val angle1 = ProximityCalculator.stableAngle("AA:BB:CC:DD:EE:FF")
        val angle2 = ProximityCalculator.stableAngle("AA:BB:CC:DD:EE:FF")
        assertEquals(angle1, angle2, 0.0f)
    }

    @Test
    fun `stable angle is always within 0 to 360`() {
        val addresses = listOf("00:11:22:33:44:55", "FF:EE:DD:CC:BB:AA", "random-id-123")
        addresses.forEach { address ->
            val angle = ProximityCalculator.stableAngle(address)
            assertTrue("angle $angle out of range for $address", angle in 0f..360f)
        }
    }

    @Test
    fun `classifies eddystone uuid as a beacon`() {
        val category = ProximityCalculator.classifyBleDevice(listOf("0000feaa-0000-1000-8000-00805f9b34fb"))
        assertEquals(DeviceCategory.BLE_BEACON, category)
    }

    @Test
    fun `classifies unknown service uuid as a regular device`() {
        val category = ProximityCalculator.classifyBleDevice(listOf("0000180a-0000-1000-8000-00805f9b34fb"))
        assertEquals(DeviceCategory.BLE_DEVICE, category)
    }

    @Test
    fun `device with no service uuids is a regular device`() {
        assertEquals(DeviceCategory.BLE_DEVICE, ProximityCalculator.classifyBleDevice(emptyList()))
    }

    @Test
    fun `auto scan radius defaults to 30m with no devices`() {
        assertEquals(30f, ProximityCalculator.autoScanRadius(emptyList()), 0.0f)
    }

    @Test
    fun `auto scan radius rounds up to the nearest 10m past the farthest device`() {
        val devices = listOf(
            radarDevice(distance = 12f),
            radarDevice(distance = 27f)
        )
        assertEquals(30f, ProximityCalculator.autoScanRadius(devices), 0.0f)
    }

    @Test
    fun `auto scan radius is clamped between 10 and 100`() {
        assertEquals(10f, ProximityCalculator.autoScanRadius(listOf(radarDevice(distance = 0.5f))), 0.0f)
        assertEquals(100f, ProximityCalculator.autoScanRadius(listOf(radarDevice(distance = 500f))), 0.0f)
    }

    private fun radarDevice(distance: Float) = RadarDevice(
        id = "id-$distance",
        name = "Device",
        category = DeviceCategory.UNKNOWN,
        rssi = -60,
        estimatedDistanceM = distance,
        angle = 0f,
        lastSeen = 0L,
        signalPercent = 50
    )
}
