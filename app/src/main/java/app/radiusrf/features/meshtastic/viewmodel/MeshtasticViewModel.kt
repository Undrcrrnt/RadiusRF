package app.radiusrf.features.meshtastic.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radiusrf.core.debug.DemoData
import app.radiusrf.core.debug.DemoDataBus
import app.radiusrf.core.debug.observeDemoRequests
import android.os.SystemClock
import app.radiusrf.features.ble.domain.BleScanner
import app.radiusrf.features.signatures.domain.DeviceSignatures
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MeshtasticNode(
    val address: String,
    val name: String,
    val rssi: Int,
    val source: String
)

data class MeshtasticState(
    val isScanning: Boolean = false,
    val isPaused: Boolean = false,
    val nodes: List<MeshtasticNode> = emptyList(),
    val occupancy: String? = null,
    val status: String? = null
)

@HiltViewModel
class MeshtasticViewModel @Inject constructor(
    private val bleScanner: BleScanner,
    private val demoBus: DemoDataBus
) : ViewModel() {
    private val _state = MutableStateFlow(MeshtasticState())
    val state: StateFlow<MeshtasticState> = _state.asStateFlow()
    private var job: Job? = null
    private var lastPublishMs = 0L

    init {
        observeDemoRequests(demoBus, DemoData.Routes.MESHTASTIC) { loadDemo() }
    }

    fun startScan() {
        if (job?.isActive == true) return
        _state.update { it.copy(isScanning = true, isPaused = false, status = null) }
        job = viewModelScope.launch {
            launch {
                bleScanner.scan(timeoutMs = 120_000L).catch { error ->
                    _state.update { it.copy(status = error.message) }
                }.collect { devices ->
                    val nodes = devices.filter { DeviceSignatures.isMeshtastic(it) }.map {
                        MeshtasticNode(it.address, it.name ?: "Meshtastic", it.rssi, "BLE")
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (nodes.isEmpty() || now - lastPublishMs < 500L && nodes.size == _state.value.nodes.size) return@collect
                    lastPublishMs = now
                    _state.update { state ->
                        state.copy(nodes = (nodes + state.nodes).distinctBy { it.address }.sortedByDescending { it.rssi })
                    }
                }
            }
        }
    }

    fun pauseScan() {
        job?.cancel()
        job = null
        _state.update { it.copy(isScanning = false, isPaused = true) }
    }

    fun resumeScan() = startScan()

    fun stopScan() {
        job?.cancel()
        job = null
        _state.update { it.copy(isScanning = false, isPaused = false) }
    }

    private fun loadDemo() {
        stopScan()
        _state.value = MeshtasticState(
            nodes = listOf(
                MeshtasticNode("AA:11:22:33:44:55", "Meshtastic_A3F1", -61, "BLE"),
                MeshtasticNode("AA:11:22:33:44:66", "Meshtastic_Trail", -78, "BLE")
            ),
            occupancy = "915.0 MHz quiet in demo"
        )
    }

    override fun onCleared() = stopScan()
}
