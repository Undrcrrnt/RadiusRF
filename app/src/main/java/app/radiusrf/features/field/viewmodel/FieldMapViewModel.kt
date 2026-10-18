package app.radiusrf.features.field.domain

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radiusrf.features.sdr.domain.RemoteIdSighting
import app.radiusrf.features.sdr.domain.RemoteIdWatch
import app.radiusrf.features.wardriving.data.WardrivingRepository
import app.radiusrf.features.wardriving.domain.WardrivingRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class MapFace { PINS, PATH, LIST }

data class FieldMapState(
    val lat: Double? = null,
    val lon: Double? = null,
    val survey: List<WardrivingRecord> = emptyList(),
    val remoteIds: List<RemoteIdSighting> = emptyList(),
    val bearing: Float? = null,
    val bearingLabel: String = "No bearing yet",
    val showSurvey: Boolean = true,
    val showRemote: Boolean = true,
    val showBearing: Boolean = true,
    val face: MapFace = MapFace.PINS,
    val remoteNote: String? = null
)

@HiltViewModel
class FieldMapViewModel @Inject constructor(
    private val surveys: WardrivingRepository,
    private val locationManager: LocationManager,
    @ApplicationContext context: Context
) : ViewModel() {
    private val remoteId = RemoteIdWatch(context)
    private val _state = MutableStateFlow(FieldMapState())
    val state: StateFlow<FieldMapState> = _state.asStateFlow()

    private val fixes = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            _state.update { it.copy(lat = location.latitude, lon = location.longitude) }
        }
        @Deprecated("Legacy callback")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    private var started = false

    fun start() {
        if (started) return
        started = true
        runCatching {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000L, 0f, fixes)
            val last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (last != null) _state.update { it.copy(lat = last.latitude, lon = last.longitude) }
        }
        viewModelScope.launch {
            surveys.recentFixes().collect { rows -> _state.update { it.copy(survey = rows) } }
        }
        viewModelScope.launch {
            FieldBearing.degrees.collect { bearing -> _state.update { it.copy(bearing = bearing) } }
        }
        viewModelScope.launch {
            FieldBearing.label.collect { text -> _state.update { it.copy(bearingLabel = text) } }
        }
        val error = remoteId.start { sightings -> _state.update { it.copy(remoteIds = sightings, remoteNote = null) } }
        if (error != null) _state.update { it.copy(remoteNote = error) }
    }

    fun setFace(face: MapFace) = _state.update { it.copy(face = face) }
    fun toggleSurvey() = _state.update { it.copy(showSurvey = !it.showSurvey) }
    fun toggleRemote() = _state.update { it.copy(showRemote = !it.showRemote) }
    fun toggleBearing() = _state.update { it.copy(showBearing = !it.showBearing) }

    override fun onCleared() {
        remoteId.stop()
        runCatching { locationManager.removeUpdates(fixes) }
    }
}
