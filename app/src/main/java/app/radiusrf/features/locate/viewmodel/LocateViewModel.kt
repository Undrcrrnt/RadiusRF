package app.radiusrf.features.locate.viewmodel

import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radiusrf.features.ble.domain.BleScanner
import app.radiusrf.features.locate.domain.LocateCue
import app.radiusrf.features.locate.domain.GeoSignalSample
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
    val error: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationAccuracyMeters: Float? = null,
    val geoSamples: List<GeoSignalSample> = emptyList()
) {
    val sweepPace: Boolean get() = radio == LocateRadio.WIFI && nic == WifiNicSource.PHONE
}

/** Follows one Bluetooth device or one access point so the person can walk toward it. */
@HiltViewModel
class LocateViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val scanner: BleScanner,
    private val wifi: WifiScanner,
    private val nic: WifiNicPreference,
    private val locationManager: LocationManager
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
    private var currentLocation: Location? = null
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            currentLocation = location
            _state.update {
                it.copy(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    locationAccuracyMeters = location.accuracy
                )
            }
        }
        @Deprecated("Legacy callback")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

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

    @SuppressLint("MissingPermission")
    fun start() {
        if (scanJob?.isActive == true) return
        _state.update { it.copy(running = true, error = null, missing = false) }
        runCatching {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 1f, locationListener)
            val last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (last != null) locationListener.onLocationChanged(last)
        }
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
        runCatching { locationManager.removeUpdates(locationListener) }
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
                error = null,
                geoSamples = emptyList()
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
        val geoSamples = currentLocation
            ?.takeIf {
                it.accuracy <= MAX_MAP_ACCURACY_METERS &&
                    SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos <= MAX_LOCATION_AGE_NS
            }
            ?.let { location ->
                val mapped = GeoSignalSample(
                    timeMs = seenAt,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = location.accuracy,
                    rssi = rssi
                )
                appendMapSample(current.geoSamples, mapped)
            }
            ?: current.geoSamples
        _state.update {
            it.copy(
                rssi = rssi,
                lastSeen = seenAt,
                peakRssi = maxOf(it.peakRssi, rssi),
                samples = samples,
                geoSamples = geoSamples,
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
        private const val MAX_MAP_ACCURACY_METERS = 50f
        private const val MAX_LOCATION_AGE_NS = 15_000_000_000L
    }
}

private fun appendMapSample(
    samples: List<GeoSignalSample>,
    next: GeoSignalSample
): List<GeoSignalSample> {
    val last = samples.lastOrNull()
    if (last != null) {
        val distance = FloatArray(1)
        Location.distanceBetween(last.latitude, last.longitude, next.latitude, next.longitude, distance)
        if (distance[0] < 1.5f && next.timeMs - last.timeMs < 1_500L) {
            return (samples.dropLast(1) + next).takeLast(800)
        }
    }
    return (samples + next).takeLast(800)
}
