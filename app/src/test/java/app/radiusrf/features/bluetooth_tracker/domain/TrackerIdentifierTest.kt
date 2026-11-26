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
        serviceData: Map<String, ByteArray> = emptyMap(),
        rssi: Int = -60
    ) = BleDevice(
        name = name,
        address = "AA:BB:CC:DD:EE:FF",
        rssi = rssi,
        serviceUuids = uuids,
        manufacturerData = manufacturerData,
        serviceData = serviceData
    )

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun bytes(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var at = 0
        parts.forEach { part ->
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }

    @Test
    fun `identifies Find My by manufacturer payload`() {
        val payload = byteArrayOf(0x12, 0x08, 0x00, 1, 2, 3, 4, 5, 6, 7)
        val match = identifier.match(ble(manufacturerData = mapOf(0x004C to payload)))
        assertEquals(TrackerType.FIND_MY, match?.type)
        assertEquals(OwnerState.WITH_OWNER, match?.owner)
    }

    @Test
    fun `a separated Find My tag is eligible to alert`() {
        val payload = byteArrayOf(0x12, 0x08, 0x04, 1, 2, 3, 4, 5, 6, 7)
        val match = identifier.match(ble(manufacturerData = mapOf(0x004C to payload)))
        assertEquals(OwnerState.SEPARATED, match?.owner)
        assertEquals(
            TrackingRisk.HIGH,
            identifier.assessRisk(tracker(TrackerType.FIND_MY, 3, places = 3).copy(owner = OwnerState.SEPARATED))
        )
        assertEquals(
            TrackingRisk.LOW,
            identifier.assessRisk(tracker(TrackerType.FIND_MY, 3, places = 3).copy(owner = OwnerState.WITH_OWNER))
        )
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
    fun `Fast Pair headphones are not trackers`() {
        assertEquals(
            null,
            identifier.match(ble(name = "Galaxy Buds", uuids = listOf("0000fe2c-0000-1000-8000-00805f9b34fb")))
        )
    }

    @Test
    fun `identifies Find Hub by the location advertisement`() {
        val eid = ByteArray(20) { (it + 1).toByte() }
        val payload = bytes(byteArrayOf(0x41), eid, byteArrayOf(0x00))
        val match = identifier.match(
            ble(serviceData = mapOf("0000feaa-0000-1000-8000-00805f9b34fb" to payload))
        )
        assertEquals(TrackerType.FIND_HUB, match?.type)
        assertEquals(OwnerState.SEPARATED, match?.owner)
        assertEquals("findhub:" + eid.toHex(), match?.identity)
    }

    @Test
    fun `Eddystone on the same service is not Find Hub`() {
        val payload = byteArrayOf(0x10) + ByteArray(20)
        assertEquals(
            null,
            identifier.match(ble(serviceData = mapOf("0000feaa-0000-1000-8000-00805f9b34fb" to payload)))
        )
    }

    @Test
    fun `identifies a separated SmartTag and ignores a paired one for alerts`() {
        val privacy = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val lost = bytes(byteArrayOf(0x12, 0, 0, 0), privacy, byteArrayOf(0, 0, 0, 0))
        val match = identifier.match(
            ble(serviceData = mapOf("0000fd5a-0000-1000-8000-00805f9b34fb" to lost))
        )
        assertEquals(TrackerType.SMARTTAG, match?.type)
        assertEquals(OwnerState.SEPARATED, match?.owner)
        assertEquals("smarttag:" + privacy.toHex(), match?.identity)

        val paired = bytes(byteArrayOf(0x15, 0, 0, 0), privacy, byteArrayOf(0, 0, 0, 0))
        assertEquals(
            OwnerState.WITH_OWNER,
            identifier.match(ble(serviceData = mapOf("0000fd5a-0000-1000-8000-00805f9b34fb" to paired)))?.owner
        )
        assertEquals(
            TrackingRisk.LOW,
            identifier.assessRisk(tracker(TrackerType.SMARTTAG, 3, places = 3).copy(owner = OwnerState.WITH_OWNER))
        )
    }

    @Test
    fun `a Samsung phone advertisement is not a SmartTag`() {
        assertEquals(null, identifier.match(ble(manufacturerData = mapOf(0x0075 to ByteArray(20)))))
    }

    @Test
    fun `identifies the shared unwanted tracker advertisement by network`() {
        val google = identifier.match(
            ble(serviceData = mapOf("0000fcb2-0000-1000-8000-00805f9b34fb" to byteArrayOf(0x02, 0x00, 9, 9)))
        )
        assertEquals(TrackerType.FIND_HUB, google?.type)
        assertEquals(OwnerState.SEPARATED, google?.owner)

        val apple = identifier.match(
            ble(serviceData = mapOf("0000fcb2-0000-1000-8000-00805f9b34fb" to byteArrayOf(0x01, 0x01, 4)))
        )
        assertEquals(TrackerType.FIND_MY, apple?.type)
        assertEquals(OwnerState.WITH_OWNER, apple?.owner)

        assertEquals(
            TrackerType.AMAZON,
            identifier.match(
                ble(serviceData = mapOf("0000fcb2-0000-1000-8000-00805f9b34fb" to byteArrayOf(0x04, 0x00)))
            )?.type
        )
        assertEquals(
            TrackerType.GENERIC_TRACKER,
            identifier.match(
                ble(serviceData = mapOf("0000fcb2-0000-1000-8000-00805f9b34fb" to byteArrayOf(0x20, 0x00)))
            )?.type
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
