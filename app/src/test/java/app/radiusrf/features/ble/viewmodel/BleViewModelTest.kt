package app.radiusrf.features.ble.viewmodel

import app.radiusrf.core.debug.DemoDataBus
import app.radiusrf.core.testing.MainDispatcherRule
import app.radiusrf.features.ble.data.BleRepository
import app.radiusrf.features.ble.domain.BleDevice
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BleViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val repository = mockk<BleRepository>()
    private val device = BleDevice("Buds", "AA:BB:CC:DD:EE:01", -50)

    private fun vm(available: Boolean = true): BleViewModel {
        every { repository.isAvailable } returns available
        return BleViewModel(repository, mockk<app.radiusrf.core.sessions.SessionRepository>(relaxed = true), DemoDataBus())
    }

    @Test
    fun `toggleScan starts a scan and publishes devices`() {
        every { repository.scan() } returns flowOf(listOf(device))
        val vm = vm()

        vm.toggleScan()

        assertTrue(vm.scanState.value.isScanning)
        assertEquals(listOf(device), vm.scanState.value.devices)
    }

    @Test
    fun `toggleScan a second time stops the scan`() {
        every { repository.scan() } returns MutableSharedFlow()
        val vm = vm()
        vm.toggleScan()

        vm.toggleScan()

        assertFalse(vm.scanState.value.isScanning)
    }

    @Test
    fun `scanning without Bluetooth flags the adapter as disabled`() {
        val vm = vm(available = false)

        vm.startScan()

        assertFalse(vm.scanState.value.isScanning)
        assertFalse(vm.scanState.value.isBluetoothEnabled)
    }

    @Test
    fun `scanner failure surfaces as an error and stops scanning`() {
        every { repository.scan() } returns flow { throw IllegalStateException("adapter off") }
        val vm = vm()

        vm.startScan()

        assertFalse(vm.scanState.value.isScanning)
        assertEquals("adapter off", vm.scanState.value.error)
    }

    @Test
    fun `scan auto-stops after thirty seconds`() {
        every { repository.scan() } returns MutableSharedFlow()
        val vm = vm()
        vm.startScan()

        mainRule.dispatcher.scheduler.advanceTimeBy(30_001)
        mainRule.dispatcher.scheduler.runCurrent()

        assertFalse(vm.scanState.value.isScanning)
    }

    @Test
    fun `toggleBookmark flips the local flag and persists it`() {
        every { repository.scan() } returns flowOf(listOf(device, device.copy(address = "AA:BB:CC:DD:EE:02")))
        coEvery { repository.toggleBookmark(any()) } returns Unit
        val vm = vm()
        vm.startScan()

        vm.toggleBookmark(device)

        val updated = vm.scanState.value.devices
        assertTrue(updated.first { it.address == device.address }.isBookmarked)
        assertFalse(updated.first { it.address != device.address }.isBookmarked)
        coVerify(exactly = 1) { repository.toggleBookmark(device) }
        assertNotNull(vm.scanState.value)
    }
}
