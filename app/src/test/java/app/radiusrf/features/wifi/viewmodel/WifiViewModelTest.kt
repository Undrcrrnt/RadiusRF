package app.radiusrf.features.wifi.viewmodel

import app.radiusrf.core.testing.MainDispatcherRule
import app.radiusrf.core.util.WifiBand
import app.radiusrf.features.wifi.domain.WifiAccessPoint
import app.radiusrf.features.wifi.domain.WifiScanner
import app.radiusrf.features.wifi.domain.WifiNicPreference
import app.radiusrf.features.wifi.domain.WifiNicSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import app.radiusrf.core.debug.DemoDataBus

class WifiViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val scanner = mockk<WifiScanner>()
    private val sessions = mockk<app.radiusrf.core.sessions.SessionRepository>(relaxed = true)
    private val nic = mockk<WifiNicPreference>().also {
        every { it.source } returns MutableStateFlow(WifiNicSource.PHONE)
    }
    private fun viewModel() = WifiViewModel(scanner, sessions, nic, DemoDataBus())

    private fun ap(bssid: String, freq: Int = 2437, rssi: Int = -50) =
        WifiAccessPoint("Net-$bssid", bssid, rssi, freq, "[WPA2-PSK-CCMP][ESS]")

    @Test
    fun `startScan publishes access points and channel scores`() {
        every { scanner.scan() } returns flowOf(listOf(ap("A"), ap("B", freq = 5180)))
        val vm = viewModel()

        vm.startScan()

        assertTrue(vm.isScanning.value)
        assertEquals(listOf("A", "B"), vm.accessPoints.value.map { it.bssid })
        assertEquals(setOf(6, 36), vm.channelScores.value.map { it.channel }.toSet())
    }

    @Test
    fun `stopScan clears the scanning flag but keeps results`() {
        every { scanner.scan() } returns flowOf(listOf(ap("A")))
        val vm = viewModel()
        vm.startScan()

        vm.stopScan()

        assertFalse(vm.isScanning.value)
        assertEquals(1, vm.accessPoints.value.size)
    }

    @Test
    fun `startScan is ignored while a scan is already running`() {
        val live = MutableSharedFlow<List<WifiAccessPoint>>()
        every { scanner.scan() } returns live
        val vm = viewModel()

        vm.startScan()
        vm.startScan()

        verify(exactly = 1) { scanner.scan() }
    }

    @Test
    fun `scan keeps running past thirty seconds until stop`() {
        every { scanner.scan() } returns MutableSharedFlow()
        val vm = viewModel()
        vm.startScan()

        mainRule.dispatcher.scheduler.advanceTimeBy(60_000)
        mainRule.dispatcher.scheduler.runCurrent()
        assertTrue(vm.isScanning.value)

        vm.stopScan()
        assertFalse(vm.isScanning.value)
    }

    @Test
    fun `band selection is independent of scanning`() {
        val vm = viewModel()
        assertNull(vm.selectedBand.value)

        vm.selectBand(WifiBand.BAND_5GHZ)

        assertEquals(WifiBand.BAND_5GHZ, vm.selectedBand.value)
        vm.selectBand(null)
        assertNull(vm.selectedBand.value)
    }
}
