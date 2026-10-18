package app.radiusrf.features.locate.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radiusrf.features.ble.domain.BleScanner
import app.radiusrf.features.locate.domain.LocateCue
import app.radiusrf.features.locate.domain.LocateRadio
import app.radiusrf.features.locate.domain.LocateSample
import app.radiusrf.features.locate.domain.LocateSignals
import app.radiusrf.features.wifi.domain.WifiNicPreference
import app.radiusrf.features.wifi.domain.WifiNicSource
import app.radiusrf.features.wifi.domain.WifiScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LocateUiState(
    val radio: LocateRadio = LocateRadio.BLE,
    val address: String = "",
    val label: String = "",
    val channel: Int = 0,
    val running: Boolean = false,
    val cue: LocateCue = LocateCue.WAITING,
    val rssi: Int? = null,
    val peakRssi: Int = Int.MIN_VALUE,
    val lastSeen: Long = 0L,
    val samples: List<LocateSample> = emptyList(),
    val missing: Boolean = false,
    val nic: WifiNicSource = WifiNicSource.PHONE,
    val beep: Boolean = true,
    val haptics: Boolean = true,
    val error: String? = null
) {
    val sweepPace: Boolean get() = radio == LocateRadio.WIFI && nic == WifiNicSource.PHONE
}

/** Follows one Bluetooth device or one access point so the person can walk toward it. */
@HiltViewModel
class LocateViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val scanner: BleScanner,
    private val wifi: WifiScanner,
    private val nic: WifiNicPreference
) : ViewModel() {

    private val _state = MutableStateFlow(
        LocateUiState(
            radio = if (savedStateHandle.get<String>("kind") == "wifi") LocateRadio.WIFI else LocateRadio.BLE,
            address = savedStateHandle.get<String>("address").orEmpty(),
            label = savedStateHandle.get<String>("label").orEmpty().trim(),
            channel = savedStateHandle.get<Int>("ch") ?: 0,
            nic = nic.source.value
        )
    )
    val state: StateFlow<LocateUiState> = _state.asStateFlow()

    private var scanJob: Job? = null
    private var clockJob: Job? = null

    init {
        viewModelScope.launch {
            nic.source.drop(1).collect { source ->
                _state.update { it.copy(nic = source) }
                if (_state.value.radio != LocateRadio.WIFI) return@collect
                val previous = scanJob
                if (previous?.isActive != true) return@collect
                stopListening()
                previous.join()
                listen()
            }
        }
    }

    fun start() {
        if (scanJob?.isActive == true) return
        _state.update { it.copy(running = true, error = null, missing = false) }
        clockJob?.cancel()
        clockJob = viewModelScope.launch {
            while (isActive) {
                delay(500)
                if (_state.value.running) publishCue()
            }
        }
        listen()
    }

    fun stop() {
        stopListening()
        clockJob?.cancel()
        clockJob = null
        _state.update { it.copy(running = false) }
    }

    fun reset() {
        _state.update {
            it.copy(
                samples = emptyList(),
                peakRssi = Int.MIN_VALUE,
                lastSeen = 0L,
                rssi = null,
                missing = false,
                cue = LocateCue.WAITING,
                error = null
            )
        }
    }

    fun selectSource(source: WifiNicSource) {
        nic.select(source)
    }

    fun toggleBeep() = _state.update { it.copy(beep = !it.beep) }
    fun toggleHaptics() = _state.update { it.copy(haptics = !it.haptics) }

    private fun listen() {
        scanJob = viewModelScope.launch {
            if (_state.value.radio == LocateRadio.WIFI) listenWifi() else listenBle()
        }
    }

    private fun stopListening() {
        scanJob?.cancel()
        scanJob = null
    }

    private suspend fun listenBle() {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            scanner.scan(lowLatency = true, timeoutMs = SCAN_WINDOW_MS)
                .catch { e -> _state.update { it.copy(error = e.message ?: "Bluetooth scan failed", running = false) } }
                .collect { devices ->
                    val hit = devices.firstOrNull { it.address.equals(_state.value.address, ignoreCase = true) } ?: return@collect
                    note(hit.rssi, hit.lastSeen, channel = null)
                }
            if (_state.value.error != null) break
        }
    }

    private suspend fun listenWifi() {
        wifi.scan()
            .catch { e -> _state.update { it.copy(error = "WiFi locate error: ${e.message}", running = false) } }
            .collect { aps ->
                val match = aps.firstOrNull { it.bssid.equals(_state.value.address, ignoreCase = true) }
                if (match != null) {
                    val heard = match.heardAt.takeIf { it > 0L } ?: System.currentTimeMillis()
                    note(match.rssi, heard, match.channel)
                } else if (_state.value.samples.isNotEmpty() && aps.isNotEmpty()) {
                    _state.update { it.copy(missing = true) }
                    publishCue()
                }
            }
    }

    private fun note(rssi: Int, seenAt: Long, channel: Int?) {
        val current = _state.value
        if (seenAt <= current.lastSeen && current.lastSeen != 0L) return
        val sample = LocateSample(seenAt, rssi)
        val samples = (current.samples + sample).takeLast(120)
        _state.update {
            it.copy(
                rssi = rssi,
                lastSeen = seenAt,
                peakRssi = maxOf(it.peakRssi, rssi),
                samples = samples,
                missing = false,
                channel = channel ?: it.channel,
                error = null
            )
        }
        publishCue(seenAt)
    }

    private fun publishCue(now: Long = System.currentTimeMillis()) {
        val s = _state.value
        val cue = if (s.sweepPace) {
            LocateSignals.sweeps(s.samples, now, s.lastSeen, s.missing)
        } else {
            LocateSignals.packets(s.samples, now, s.lastSeen, s.missing)
        }
        if (cue != s.cue) _state.update { it.copy(cue = cue) }
    }

    override fun onCleared() {
        stop()
    }

    companion object {
        const val SCAN_WINDOW_MS = 60_000L
    }
}
