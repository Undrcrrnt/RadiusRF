package app.radiusrf.features.ble.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radiusrf.features.ble.data.BleRepository
import app.radiusrf.features.ble.domain.BleDevice
import app.radiusrf.features.ble.domain.BleScanState
import app.radiusrf.core.sessions.ItemKind
import app.radiusrf.core.sessions.SessionItem
import app.radiusrf.core.sessions.SessionRepository
import app.radiusrf.features.ble.domain.BleDeviceTypeIdentifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject
import app.radiusrf.core.debug.DemoDataBus
import app.radiusrf.core.debug.DemoData
import app.radiusrf.core.debug.observeDemoRequests

@HiltViewModel
class BleViewModel @Inject constructor(
    private val repository: BleRepository,
    private val sessions: SessionRepository,
    private val demoBus: DemoDataBus
) : ViewModel() {

    /** When the current run started; null when no run is in progress. */
    private var runStartedAt: Long? = null


    private val _scanState = MutableStateFlow(BleScanState(isBluetoothEnabled = repository.isAvailable))
    val scanState: StateFlow<BleScanState> = _scanState.asStateFlow()

    private var scanJob: Job? = null
    private var autoStopJob: Job? = null
    private var runInBackground = false

    val isAvailable: Boolean get() = repository.isAvailable

    fun toggleScan() {
        if (_scanState.value.isScanning) {
            stopScan()
        } else {
            startScan()
        }
    }

    fun startScan() {
        if (!repository.isAvailable) {
            _scanState.value = _scanState.value.copy(isBluetoothEnabled = false)
            return
        }

        scanJob?.cancel()
        val keep = _scanState.value.isPaused
        if (keep) {
            _scanState.value = _scanState.value.copy(isScanning = true, isPaused = false, isBluetoothEnabled = true, error = null)
        } else {
            _scanState.value = BleScanState(isScanning = true, isBluetoothEnabled = true)
            runStartedAt = System.currentTimeMillis()
        }
        scanJob = viewModelScope.launch {
            repository.scan()
                .catch { e ->
                    _scanState.value = _scanState.value.copy(
                        isScanning = false,
                        error = e.message
                    )
                }
                .collect { devices ->
                    _scanState.value = _scanState.value.copy(devices = devices)
                }
        }
        autoStopJob?.cancel()
        if (!runInBackground) {
            autoStopJob = viewModelScope.launch {
                delay(AUTO_STOP_TIMEOUT_MS)
                stopScan()
            }
        }
    }

    /** A background run should not hit the 30 second cutoff. */
    fun setRunInBackground(on: Boolean) {
        runInBackground = on
        if (!_scanState.value.isScanning) return
        autoStopJob?.cancel()
        autoStopJob = null
        if (!on) {
            autoStopJob = viewModelScope.launch {
                delay(AUTO_STOP_TIMEOUT_MS)
                stopScan()
            }
        }
    }

    fun pauseScan() {
        if (!_scanState.value.isScanning) return
        autoStopJob?.cancel()
        autoStopJob = null
        scanJob?.cancel()
        scanJob = null
        _scanState.value = _scanState.value.copy(isScanning = false, isPaused = true)
    }

    fun resumeScan() = startScan()

    fun stopScan() {
        _scanState.value = _scanState.value.copy(isPaused = false)
        runStartedAt?.let { started ->
            runStartedAt = null
            recordSession(started)
        }
        autoStopJob?.cancel()
        autoStopJob = null
        scanJob?.cancel()
        scanJob = null
        _scanState.value = _scanState.value.copy(isScanning = false, isPaused = false)
    }

    companion object {
        private const val AUTO_STOP_TIMEOUT_MS = 30_000L
    }

    fun toggleBookmark(device: BleDevice) {
        viewModelScope.launch {
            repository.toggleBookmark(device)
            // The scan flow only pushes updates while a scan is actively being collected, but
            // bookmarking must also work on a stopped/idle results list — flip it locally too.
            _scanState.value = _scanState.value.copy(
                devices = _scanState.value.devices.map {
                    if (it.address == device.address) it.copy(isBookmarked = !it.isBookmarked) else it
                }
            )
        }
    }

    private fun recordSession(startedAt: Long) {
        val devices = _scanState.value.devices
        val typed = devices.map { it to BleDeviceTypeIdentifier.identify(it.name, it.serviceUuids).category }
        val trackers = typed.count { it.second == "Tracker" }
        sessions.recordInBackground(
            tool = "ble",
            title = "BLE scan",
            startedAt = startedAt,
            items = typed.map { (d, category) ->
                SessionItem(
                    key = d.address,
                    label = d.name ?: "[no name]",
                    kind = if (category == "Tracker") ItemKind.TRACKER else ItemKind.BLE,
                    rssi = d.rssi,
                    detail = category,
                    flagged = category == "Tracker"
                )
            },
            summary = "${devices.size} devices" + if (trackers > 0) " · $trackers tracker${if (trackers > 1) "s" else ""}" else ""
        )
    }

    override fun onCleared() {
        stopScan()
    }

    init {
        observeDemoRequests(demoBus, DemoData.Routes.BLE) { loadDemoData() }
    }

    /** Debug-only: replaces live state with [DemoData] so the populated UI can be verified without hardware. */
    private fun loadDemoData() {
        stopScan()
        _scanState.value = _scanState.value.copy(devices = DemoData.bleDevices, isBluetoothEnabled = true, error = null)
    }
}
