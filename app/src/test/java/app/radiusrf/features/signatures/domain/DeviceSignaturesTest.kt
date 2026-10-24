package app.radiusrf.features.signatures.domain

import app.radiusrf.features.ble.domain.BleDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSignaturesTest {

    private fun ble(
        name: String? = null,
        uuids: List<String> = emptyList(),
        manufacturerData: Map<Int, ByteArray> = emptyMap()
    ) = BleDevice(name, "AA:BB:CC:DD:EE:FF", -55, uuids, manufacturerData)

    @Test
    fun `Find Hub matches unnamed Fast Pair and named Find My Device`() {
        assertTrue(DeviceSignatures.isFindHub(ble(uuids = listOf("0000fe2c-0000-1000-8000-00805f9b34fb"))))
        assertTrue(DeviceSignatures.isFindHub(ble(name = "Find My Device")))
        assertTrue(DeviceSignatures.matchBle(ble(name = "Find Hub")).any { it.kind == SignatureKind.FIND_HUB })
    }

    @Test
    fun `named headphones with Fast Pair are not Find Hub`() {
        assertTrue(DeviceSignatures.matchBle(ble(name = "Pixel Buds", uuids = listOf("0000fe2c-0000-1000-8000-00805f9b34fb"))).none { it.kind == SignatureKind.FIND_HUB })
    }

    @Test
    fun `glasses action cams and serial modules match`() {
        assertEquals(SignatureKind.GLASSES, DeviceSignatures.matchBle(ble(name = "Ray-Ban Meta")).single().kind)
        assertEquals(SignatureKind.ACTION_CAM, DeviceSignatures.matchBle(ble(name = "GoPro 12")).single().kind)
        assertEquals(SignatureKind.SKIMMER_SERIAL, DeviceSignatures.matchBle(ble(name = "HC-05")).single().kind)
    }

    @Test
    fun `wifi camera and glasses hotspots match`() {
        assertEquals(SignatureKind.CAMERA_SSID, DeviceSignatures.matchWifi("GP2523411", "aa:bb", -40)?.kind)
        assertEquals(SignatureKind.GLASSES, DeviceSignatures.matchWifi("Ray-Ban-1234", "aa:bc", -50)?.kind)
        assertNull(DeviceSignatures.matchWifi("HomeNet", "aa:bd", -60))
    }

    @Test
    fun `Meshtastic matches service uuid and name`() {
        assertTrue(DeviceSignatures.isMeshtastic(ble(name = "Meshtastic_A1")))
        assertTrue(DeviceSignatures.isMeshtastic(ble(uuids = listOf(DeviceSignatures.MESHTASTIC_UUID))))
    }
}
