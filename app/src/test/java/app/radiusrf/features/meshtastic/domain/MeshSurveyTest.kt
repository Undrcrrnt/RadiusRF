package app.radiusrf.features.meshtastic.domain

import app.radiusrf.features.ble.domain.BleDevice
import app.radiusrf.features.signatures.domain.DeviceSignatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class MeshSurveyTest {

    @Test
    fun `bluetooth companion links are named by service or firmware name`() {
        assertEquals(
            "Meshtastic",
            MeshCompanionId.identify(ble(uuids = listOf(DeviceSignatures.MESHTASTIC_UUID)))?.protocol
        )
        assertEquals(
            "Meshtastic",
            MeshCompanionId.identify(ble(name = "Meshnology N30"))?.protocol
        )
        assertEquals(
            "Reticulum",
            MeshCompanionId.identify(ble(uuids = listOf("37145b00-442d-4a94-917f-8f42c5da28e3")))?.protocol
        )
        assertEquals("Reticulum", MeshCompanionId.identify(ble(name = "RNode"))?.protocol)
        assertEquals("MeshCore", MeshCompanionId.identify(ble(name = "MeshCore_7A"))?.protocol)
        assertNull(MeshCompanionId.identify(ble(name = "Galaxy Buds")))
    }

    @Test
    fun `a Meshtastic sync word is read from the chirp preamble`() {
        val sf = 7
        val (i, q) = preamble(sf, 0x2B)
        val hit = LoraMesh.detect(i, q)
        assertEquals("Meshtastic", hit?.protocol)
        assertEquals(sf, hit?.spreading)
        assertEquals(0x2B, hit?.sync)
    }

    @Test
    fun `the default sync word stays shared by RNode and MeshCore`() {
        val (i, q) = preamble(7, 0x12)
        assertEquals("RNode / MeshCore", LoraMesh.detect(i, q)?.protocol)
    }

    @Test
    fun `noise is not a mesh preamble`() {
        val n = 2_048
        val i = FloatArray(n) { (it % 5 - 2) / 50f }
        val q = FloatArray(n) { ((it * 3) % 5 - 2) / 50f }
        assertNull(LoraMesh.detect(i, q))
    }

    private fun ble(name: String? = null, uuids: List<String> = emptyList()) = BleDevice(
        name = name,
        address = "AA:BB:CC:DD:EE:FF",
        rssi = -60,
        serviceUuids = uuids
    )

    private fun preamble(sf: Int, sync: Int): Pair<FloatArray, FloatArray> {
        val shift = sf - 4
        val symbols = IntArray(8) { 0 } + intArrayOf((sync ushr 4) shl shift, (sync and 0x0F) shl shift)
        val n = 1 shl sf
        val i = FloatArray(n * symbols.size)
        val q = FloatArray(n * symbols.size)
        symbols.forEachIndexed { index, symbol ->
            for (k in 0 until n) {
                val at = index * n + k
                val phase = Math.PI * k * k / n + 2.0 * Math.PI * symbol * k / n
                i[at] = cos(phase).toFloat()
                q[at] = sin(phase).toFloat()
            }
        }
        return i to q
    }
}
