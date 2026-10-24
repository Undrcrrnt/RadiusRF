package app.radiusrf.features.bluetooth_tracker.domain

import app.radiusrf.features.ble.domain.BleDevice
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerIdentifierTest {

    private val identifier = TrackerIdentifier()

    private fun ble(
        name: String? = null,
        uuids: List<String> = emptyList(),
        manufacturerData: Map<Int, ByteArray> = emptyMap(),
        rssi: Int = -60
    ) = BleDevice(
        name = name,
        address = "AA:BB:CC:DD:EE:FF",
        rssi = rssi,
        serviceUuids = uuids,
        manufacturerData = manufacturerData
    )

    @Test
    fun `identifies Find My by manufacturer payload`() {
        val payload = byteArrayOf(0x12, 0x08, 0x00, 1, 2, 3, 4, 5, 6, 7)
        assertEquals(TrackerType.FIND_MY, identifier.match(ble(manufacturerData = mapOf(0x004C to payload)))?.type)
    }

    @Test
    fun `identifies Tile by service uuid`() {
        assertEquals(
            TrackerType.TILE,
            identifier.match(ble(uuids = listOf("0000feed-0000-1000-8000-00805f9b34fb")))?.type
        )
    }

    @Test
    fun `identifies Chipolo by company id`() {
        assertEquals(
            TrackerType.CHIPOLO,
            identifier.match(ble(manufacturerData = mapOf(0x02E5 to byteArrayOf(1, 2, 3))))?.type
        )
    }

    @Test
    fun `ordinary devices do not match`() {
        assertEquals(null, identifier.match(ble(name = "JBL Flip 6")))
    }

    @Test
    fun `identifies Find Hub by unnamed Fast Pair`() {
        assertEquals(
            TrackerType.FIND_HUB,
            identifier.match(ble(uuids = listOf("0000fe2c-0000-1000-8000-00805f9b34fb")))?.type
        )
    }

    private fun tracker(type: TrackerType, seen: Int, places: Int = 0) = DetectedTracker(
        address = "AA", name = null, type = type, rssi = -70,
        firstSeen = 1_000L, lastSeen = 2_000L, seenCount = seen, risk = TrackingRisk.NONE, places = places
    )

    @Test
    fun `risk escalates with sightings and places`() {
        assertEquals(TrackingRisk.NONE, identifier.assessRisk(tracker(TrackerType.UNKNOWN, 100, 10)))
        assertEquals(TrackingRisk.LOW, identifier.assessRisk(tracker(TrackerType.FIND_MY, 1)))
        assertEquals(TrackingRisk.MEDIUM, identifier.assessRisk(tracker(TrackerType.FIND_MY, 3)))
        assertEquals(TrackingRisk.HIGH, identifier.assessRisk(tracker(TrackerType.TILE, 3, places = 3)))
    }

    @Test
    fun `detected tracker exposes display name and signal percent`() {
        val t = tracker(TrackerType.SMARTTAG, 1)
        assertEquals("SmartTag", t.displayName)
        assertEquals("Mine", t.copy(name = "Mine").displayName)
        assertEquals(60, t.signalPercent)
    }
}
