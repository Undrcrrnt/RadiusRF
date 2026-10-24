package app.radiusrf.features.deauth_detector.viewmodel

import android.content.Context
import app.radiusrf.core.alerts.AlertCenterRepository
import app.radiusrf.core.debug.DemoDataBus
import app.radiusrf.core.testing.MainDispatcherRule
import app.radiusrf.features.wifi.domain.UsbWifiFrame
import app.radiusrf.features.wifi.domain.UsbWifiSession
import app.radiusrf.features.wifi.domain.WifiNicPreference
import app.radiusrf.features.wifi.domain.WifiNicSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DeauthDetectorViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val usb = mockk<UsbWifiSession>(relaxed = true)
    private val nic = mockk<WifiNicPreference>()
    private val frames = MutableSharedFlow<UsbWifiFrame>()
    private val failure = MutableStateFlow<String?>(null)

    private fun viewModel(openError: String? = null): DeauthDetectorViewModel {
        every { nic.source } returns MutableStateFlow(WifiNicSource.T2U_PLUS)
        every { usb.open(any()) } returns openError
        every { usb.frames } returns frames
        every { usb.failure } returns failure
        every { usb.status } returns MutableStateFlow("ready")
        every { usb.channel } returns MutableStateFlow(6)
        every { usb.adapterTitle } returns "Test adapter"
        return DeauthDetectorViewModel(
            mockk<Context>(relaxed = true),
            mockk<AlertCenterRepository>(relaxed = true),
            usb,
            nic,
            DemoDataBus()
        )
    }

    @Test
    fun `start opens selected adapter`() {
        val vm = viewModel()
        vm.startMonitoring()
        mainRule.dispatcher.scheduler.runCurrent()

        assertTrue(vm.state.value.isMonitoring)
        assertEquals("Test adapter", vm.state.value.adapterTitle)
        assertEquals(6, vm.state.value.listenChannel)
        verify(exactly = 1) { usb.open(any()) }
        vm.stopMonitoring()
    }

    @Test
    fun `adapter error stops monitoring`() {
        val vm = viewModel("permission denied")
        vm.startMonitoring()
        mainRule.dispatcher.scheduler.runCurrent()

        assertFalse(vm.state.value.isMonitoring)
        assertEquals("permission denied", vm.state.value.error)
    }

    @Test
    fun `stop releases adapter`() {
        val vm = viewModel()
        vm.startMonitoring()
        mainRule.dispatcher.scheduler.runCurrent()
        vm.stopMonitoring()

        assertFalse(vm.state.value.isMonitoring)
        verify(exactly = 1) { usb.close() }
    }
}
