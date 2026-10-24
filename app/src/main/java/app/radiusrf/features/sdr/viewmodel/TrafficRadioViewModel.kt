package app.radiusrf.features.sdr.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radiusrf.core.debug.DemoData
import app.radiusrf.core.debug.DemoDataBus
import app.radiusrf.core.debug.observeDemoRequests
import app.radiusrf.features.sdr.domain.AdsbDecoder
import app.radiusrf.features.sdr.domain.AdsbFrame
import app.radiusrf.features.sdr.domain.AisDecoder
import app.radiusrf.features.sdr.domain.AisVessel
import app.radiusrf.features.sdr.domain.SdrDecodeSession
import app.radiusrf.features.sdr.domain.SdrSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class TrafficMode { ADSB, AIS }

data class TrafficRadioState(
    val isScanning: Boolean = false,
    val isPaused: Boolean = false,
    val mode: TrafficMode = TrafficMode.ADSB,
    val aircraft: List<AdsbFrame> = emptyList(),
    val vessels: List<AisVessel> = emptyList(),
    val status: String? = null
)

@HiltViewModel
class TrafficRadioViewModel @Inject constructor(
    session: SdrSession,
    private val demoBus: DemoDataBus
) : ViewModel() {
    private val decode = SdrDecodeSession(session)
    private val even = mutableMapOf<String, Pair<Int, Int>>()
    private val odd = mutableMapOf<String, Pair<Int, Int>>()
    private val _state = MutableStateFlow(TrafficRadioState())
    val state: StateFlow<TrafficRadioState> = _state.asStateFlow()

    init {
        observeDemoRequests(demoBus, DemoData.Routes.TRAFFIC_RADIO) { loadDemo() }
    }

    fun setMode(mode: TrafficMode) {
        _state.update { it.copy(mode = mode) }
        if (_state.value.isScanning) startScan()
    }

    fun startScan() {
        viewModelScope.launch {
            _state.update { it.copy(isScanning = true, isPaused = false, status = "Opening receiver") }
            val adsB = _state.value.mode == TrafficMode.ADSB
            val error = decode.start(
                if (adsB) 1_090_000_000L else AisDecoder.CHANNEL_B,
                if (adsB) 2_048_000 else 1_024_000
            ) { iq, n, signed, rate, _ ->
                if (adsB) {
                    val frames = AdsbDecoder.decode(iq, n, signed, rate, even, odd)
                    if (frames.isNotEmpty()) {
                        _state.update { state ->
                            val merged = (frames + state.aircraft).distinctBy { it.icao }.take(40)
                            state.copy(aircraft = merged)
                        }
                    }
                } else {
                    val vessels = AisDecoder.demod(iq, n, signed, rate).mapNotNull { AisDecoder.parse(it) }
                    if (vessels.isNotEmpty()) {
                        _state.update { state ->
                            state.copy(vessels = (vessels + state.vessels).distinctBy { it.mmsi }.take(40))
                        }
                    }
                }
            }
            _state.update { it.copy(status = error, isScanning = error == null) }
        }
    }

    fun pauseScan() {
        decode.pause()
        _state.update { it.copy(isScanning = false, isPaused = true) }
    }

    fun resumeScan() {
        decode.resume()
        _state.update { it.copy(isScanning = true, isPaused = false) }
    }

    fun stopScan() {
        decode.stop()
        _state.update { it.copy(isScanning = false, isPaused = false, status = null) }
    }

    private fun loadDemo() {
        stopScan()
        _state.value = TrafficRadioState(
            aircraft = listOf(
                AdsbFrame("A1B2C3", 17, 4, "UAL123", 34_000, 37.62, -122.37, "8DA1B2C3"),
                AdsbFrame("4CA123", 17, 11, null, 11_200, 37.71, -122.21, "8D4CA123")
            ),
            vessels = listOf(
                AisVessel("366123456", 1, null, 37.81, -122.47, 8.4, 192.0, "AIVDM type 1"),
                AisVessel("367987654", 5, "HARBOR STAR", null, null, null, null, "AIVDM type 5")
            )
        )
    }

    override fun onCleared() = stopScan()
}
