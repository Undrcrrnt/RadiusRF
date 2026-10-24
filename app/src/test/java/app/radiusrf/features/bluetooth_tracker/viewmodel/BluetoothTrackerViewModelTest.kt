package app.radiusrf.features.bluetooth_tracker.viewmodel

import android.content.Context
import android.content.pm.PackageManager
import app.radiusrf.core.alerts.AlertCenterRepository
import app.radiusrf.core.alerts.AlertSeverity
import app.radiusrf.core.alerts.AlertSource
import app.radiusrf.core.debug.DemoDataBus
import app.radiusrf.core.testing.MainDispatcherRule
import app.radiusrf.features.ble.domain.BleDevice
import app.radiusrf.features.ble.domain.BleScanner
import app.radiusrf.features.bluetooth_tracker.domain.TrackerType
import app.radiusrf.features.bluetooth_tracker.domain.TrackingRisk
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BluetoothTrackerViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val scanner = mockk<BleScanner>()
    private val alerts = mockk<AlertCenterRepository>(relaxed = true)
    private val context = mockk<Context>(relaxed = true) {
        every { checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_DENIED
    }

    private val airTag = BleDevice(
        null,
        "7D:1E:AA:40:9C:02",
        -60,
        manufacturerData = mapOf(0x004C to byteArrayOf(0x12, 0x08, 0, 1, 2, 3, 4, 5, 6, 7))
    )
    private val tile = BleDevice("Tile Pro", "E4:B0:21:77:0A:1F", -80, listOf("0000feed-0000-1000-8000-00805f9b34fb"))
    private val buds = BleDevice("Galaxy Buds", "5C:F3:70:A1:02:9B", -48)

    private fun vm() = BluetoothTrackerViewModel(
        context,
        scanner,
        alerts,
        mockk<app.radiusrf.core.sessions.SessionRepository>(relaxed = true),
        DemoDataBus()
    )

    @Test
    fun `only tracker devices are kept and counted`() {
        every { scanner.scan(lowLatency = true, timeoutMs = 0) } returns flowOf(listOf(airTag, tile, buds))
        val vm = vm()

        vm.startScan()
        mainRule.dispatcher.scheduler.runCurrent()

        val s = vm.state.value
        assertTrue(s.isScanning)
        assertEquals(3, s.totalDevicesScanned)
        assertEquals(setOf(TrackerType.FIND_MY, TrackerType.TILE), s.trackers.map { it.type }.toSet())
        assertTrue(s.trackers.all { it.risk == TrackingRisk.LOW && it.seenCount == 1 })
        assertEquals(0, s.highRiskCount)
        vm.stopScan()
    }

    @Test
    fun `repeated sightings raise seen count and risk and alert once per tier`() {
        every { scanner.scan(lowLatency = true, timeoutMs = 0) } returns flow {
            repeat(5) { index ->
                emit(listOf(airTag.copy(lastSeen = airTag.lastSeen + index * 1_001L)))
            }
        }
        val vm = vm()

        vm.startScan()
        mainRule.dispatcher.scheduler.runCurrent()

        val tracker = vm.state.value.trackers.single()
        assertEquals(5, tracker.seenCount)
        assertEquals(TrackingRisk.MEDIUM, tracker.risk)
        coVerify(exactly = 1) {
            alerts.record(AlertSource.BLUETOOTH_TRACKER, AlertSeverity.MEDIUM, any(), any(), any())
        }
        vm.stopScan()
    }

    @Test
    fun `low risk trackers are not forwarded to the alert center`() {
        every { scanner.scan(lowLatency = true, timeoutMs = 0) } returns flowOf(listOf(tile))
        val vm = vm()
        vm.startScan()
        mainRule.dispatcher.scheduler.runCurrent()

        coVerify(exactly = 0) { alerts.record(any(), any(), any(), any(), any()) }
        vm.stopScan()
    }

    @Test
    fun `stop and clear reset the scan`() {
        every { scanner.scan(lowLatency = true, timeoutMs = 0) } returns flowOf(listOf(airTag))
        val vm = vm()
        vm.startScan()
        mainRule.dispatcher.scheduler.runCurrent()

        vm.stopScan()
        assertFalse(vm.state.value.isScanning)
        assertEquals(1, vm.state.value.trackers.size)

        vm.clearTrackers()
        assertTrue(vm.state.value.trackers.isEmpty())
        assertEquals(0, vm.state.value.totalDevicesScanned)
    }

    @Test
    fun `scanner failure stops the scan with an error`() {
        every { scanner.scan(lowLatency = true, timeoutMs = 0) } returns flow { throw IllegalStateException("adapter off") }
        val vm = vm()

        vm.startScan()
        mainRule.dispatcher.scheduler.runCurrent()

        assertFalse(vm.state.value.isScanning)
        assertEquals("Scan failed: adapter off", vm.state.value.error)
    }
}
