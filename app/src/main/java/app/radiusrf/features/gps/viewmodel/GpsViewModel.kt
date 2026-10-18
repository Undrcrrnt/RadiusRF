package app.radiusrf.features.gps.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radiusrf.features.gps.domain.GpsState
import app.radiusrf.features.gps.domain.GpsTracker
import app.radiusrf.features.gps.domain.SatelliteInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
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
class GpsViewModel @Inject constructor(
    private val gpsTracker: GpsTracker,
    private val demoBus: DemoDataBus
) : ViewModel() {

    private val _state = MutableStateFlow(GpsState())
    val state: StateFlow<GpsState> = _state.asStateFlow()

    private var trackingJob: Job? = null

    fun toggleTracking() {
        if (_state.value.isTracking) stopTracking() else startTracking()
    }

    fun startTracking() {
        trackingJob?.cancel()
        val keep = _state.value.isPaused
        _state.value = if (keep) {
            _state.value.copy(isTracking = true, isPaused = false, error = null)
        } else {
            GpsState(isTracking = true)
        }
        trackingJob = viewModelScope.launch {
            gpsTracker.track()
                .catch { e ->
                    _state.value = _state.value.copy(
                        isTracking = false,
                        error = e.message
                    )
                }
                .collect { state ->
                    _state.value = state.copy(satellites = state.satellites.sortedInFixOrder())
                }
        }
    }

    fun pauseTracking() {
        if (!_state.value.isTracking) return
        trackingJob?.cancel()
        trackingJob = null
        _state.value = _state.value.copy(isTracking = false, isPaused = true)
    }

    fun resumeTracking() = startTracking()

    fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
        _state.value = _state.value.copy(isTracking = false, isPaused = false)
    }

    override fun onCleared() {
        stopTracking()
    }

    private fun List<SatelliteInfo>.sortedInFixOrder(): List<SatelliteInfo> =
        sortedWith(compareByDescending<SatelliteInfo> { it.usedInFix }.thenByDescending { it.cn0DbHz })

    init {
        observeDemoRequests(demoBus, DemoData.Routes.GPS) { loadDemoData() }
    }

    /** Debug-only: replaces live state with [DemoData] so the populated UI can be verified without hardware. */
    private fun loadDemoData() {
        stopTracking()
        _state.value = DemoData.gpsState
    }
}
