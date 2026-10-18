package app.radiusrf.features.ir.viewmodel

import androidx.lifecycle.ViewModel
import app.radiusrf.features.ir.domain.FlipperIrParser
import app.radiusrf.features.ir.domain.IrProtocol
import app.radiusrf.features.ir.domain.IrRemoteButton
import app.radiusrf.features.ir.domain.IrRemoteProfile
import app.radiusrf.features.ir.domain.IrRemoteState
import app.radiusrf.features.ir.domain.IrScreenTab
import app.radiusrf.features.ir.domain.IrSignal
import app.radiusrf.features.ir.domain.IrTransmitter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import app.radiusrf.core.debug.DemoDataBus
import app.radiusrf.core.debug.DemoData
import app.radiusrf.core.debug.observeDemoRequests
import app.radiusrf.features.ir.domain.IrRemoteDatabase

@HiltViewModel
class IrViewModel @Inject constructor(
    private val irTransmitter: IrTransmitter,
    private val demoBus: DemoDataBus
) : ViewModel() {

    private val _state = MutableStateFlow(IrRemoteState(isIrAvailable = irTransmitter.isAvailable))
    val state: StateFlow<IrRemoteState> = _state.asStateFlow()

    fun setActiveTab(tab: IrScreenTab) {
        _state.value = _state.value.copy(activeTab = tab)
    }

    fun setProtocol(protocol: IrProtocol) {
        _state.value = _state.value.copy(selectedProtocol = protocol, frequency = protocol.defaultFrequency)
    }

    fun setFrequency(freq: Int) { _state.value = _state.value.copy(frequency = freq) }
    fun setCode(code: String) { _state.value = _state.value.copy(code = code) }

    fun transmit() {
        val s = _state.value
        val signal = IrSignal(protocol = s.selectedProtocol, frequency = s.frequency, code = s.code)
        val result = irTransmitter.transmit(signal)
        _state.value = _state.value.copy(lastTransmitResult = result)
    }

    fun transmitSignal(signal: IrSignal) {
        val result = irTransmitter.transmit(signal)
        _state.value = _state.value.copy(lastTransmitResult = result)
    }

    fun importFlipperFile(content: String) {
        val signals = FlipperIrParser.parse(content)
        _state.value = _state.value.copy(importedSignals = signals)
    }

    fun selectProfile(profile: IrRemoteProfile) {
        _state.value = _state.value.copy(selectedProfile = profile)
    }

    fun transmitRemoteButton(button: IrRemoteButton) {
        val signal = IrSignal(
            protocol = button.protocol, frequency = button.frequency,
            code = button.code.toString(16).uppercase(), name = button.label
        )
        val result = irTransmitter.transmit(signal)
        _state.value = _state.value.copy(lastTransmitResult = result)
    }

    init {
        observeDemoRequests(demoBus, DemoData.Routes.IR) { loadDemoData() }
    }

    /** Debug-only: replaces live state with [DemoData] so the populated UI can be verified without hardware. */
    private fun loadDemoData() {
        _state.value = _state.value.copy(
            isIrAvailable = true,
            selectedProfile = IrRemoteDatabase.profiles.first(),
            importedSignals = DemoData.irSignals,
            code = "20DF10EF"
        )
    }
}
