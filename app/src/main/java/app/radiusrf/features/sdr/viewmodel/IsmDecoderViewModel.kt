package app.radiusrf.features.sdr.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radiusrf.core.debug.DemoData
import app.radiusrf.core.debug.DemoDataBus
import app.radiusrf.core.debug.observeDemoRequests
import app.radiusrf.features.sdr.domain.IsmBand
import app.radiusrf.features.sdr.domain.IsmBands
import app.radiusrf.features.sdr.domain.IsmBurst
import app.radiusrf.features.sdr.domain.IsmDecoder
import app.radiusrf.features.sdr.domain.SdrDecodeSession
import app.radiusrf.features.sdr.domain.SdrSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class IsmDecoderState(
    val isScanning: Boolean = false,
    val isPaused: Boolean = false,
    val band: IsmBand = IsmBands.all.first { it.hz == 433_920_000L },
    val bursts: List<IsmBurst> = emptyList(),
    val status: String? = null
)

@HiltViewModel
class IsmDecoderViewModel @Inject constructor(
    session: SdrSession,
    private val demoBus: DemoDataBus
) : ViewModel() {
    private val decode = SdrDecodeSession(session)
    private val _state = MutableStateFlow(IsmDecoderState())
    val state: StateFlow<IsmDecoderState> = _state.asStateFlow()

    init {
        observeDemoRequests(demoBus, DemoData.Routes.ISM_DECODER) { loadDemo() }
    }

    fun setBand(band: IsmBand) {
        _state.update { it.copy(band = band) }
        if (_state.value.isScanning) viewModelScope.launch { startScan() }
    }

    fun startScan() {
        viewModelScope.launch {
            _state.update { it.copy(isScanning = true, isPaused = false, status = "Opening receiver") }
            val error = decode.start(_state.value.band.hz, 1_024_000) { iq, n, signed, rate, hz ->
                val train = IsmDecoder.extract(iq, n, signed, rate) ?: return@start
                val burst = IsmDecoder.classify(train, hz)
                _state.update { state ->
                    state.copy(bursts = (listOf(burst) + state.bursts).distinctBy { it.id }.take(40))
                }
            }
            _state.update { it.copy(status = error, isScanning = error == null, isPaused = false) }
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
        _state.value = IsmDecoderState(
            bursts = listOf(
                IsmBurst("d1", 433_920_000L, "OOK", "Doorbell / key fob", 24, 8_200, "101100101100", 1),
                IsmBurst("d2", 433_920_000L, "OOK", "Weather / alarm sensor", 36, 14_400, "110011001100", 2),
                IsmBurst("d3", 315_000_000L, "FSK", "TPMS / short sensor", 48, 4_800, "010101110011", 3)
            )
        )
    }

    override fun onCleared() = stopScan()
}
