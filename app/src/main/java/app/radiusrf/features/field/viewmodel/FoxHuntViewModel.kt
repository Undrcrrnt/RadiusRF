package app.radiusrf.features.field.domain

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radiusrf.features.ble.domain.BleDevice
import app.radiusrf.features.ble.domain.BleScanner
import app.radiusrf.features.sdr.domain.SdrLevel
import app.radiusrf.features.sdr.domain.SdrSession
import app.radiusrf.features.wifi.domain.WifiAccessPoint
import app.radiusrf.features.wifi.domain.WifiScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.collections.ArrayDeque
import kotlin.math.roundToInt

enum class FoxFace { COMPASS, STRENGTH, MAP, METER }

data class FoxHuntState(
    val wifi: List<WifiAccessPoint> = emptyList(),
    val ble: List<BleDevice> = emptyList(),
    val target: String? = null,
    val levelLabel: String = "Pick a signal",
    val heading: Float = 0f,
    val bearing: Float? = null,
    val samples: List<Int> = emptyList(),
    val cue: String = "Turn in a circle",
    val face: FoxFace = FoxFace.COMPASS,
    val lat: Double? = null,
    val lon: Double? = null,
    val sdrListening: Boolean = false,
    val sdrHunt: Boolean = false,
    val frequencyHz: Long = 100_000_000L,
    val levelDb: Float = -120f,
    val peakDb: Float = -120f
)

@HiltViewModel
class FoxHuntViewModel @Inject constructor(
    private val wifiScanner: WifiScanner,
    private val bleScanner: BleScanner,
    private val sensorManager: SensorManager,
    private val locationManager: LocationManager,
    private val sdr: SdrSession
) : ViewModel() {
    private val _state = MutableStateFlow(FoxHuntState())
    val state: StateFlow<FoxHuntState> = _state.asStateFlow()

    private var hunt: Job? = null
    private var heading = 0f
    private var bestLevel = Int.MIN_VALUE
    private var bestHeading = 0f
    private var recent = ArrayDeque<Int>()
    private var targetLevel: (() -> Int?)? = null

    private val compass = object : SensorEventListener {
        private val rotation = FloatArray(9)
        private val orientation = FloatArray(3)
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
            SensorManager.getRotationMatrixFromVector(rotation, event.values)
            SensorManager.getOrientation(rotation, orientation)
            heading = ((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0).toFloat()
            _state.update { it.copy(heading = heading) }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

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
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.also {
            sensorManager.registerListener(compass, it, SensorManager.SENSOR_DELAY_UI)
        }
        runCatching {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000L, 0f, fixes)
            val last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (last != null) _state.update { it.copy(lat = last.latitude, lon = last.longitude) }
        }
        viewModelScope.launch {
            wifiScanner.scan().collect { found -> _state.update { it.copy(wifi = found) } }
        }
        viewModelScope.launch {
            bleScanner.scan(lowLatency = true, timeoutMs = 600_000L).collect { found ->
                _state.update { it.copy(ble = found.sortedByDescending { device -> device.rssi }.take(40)) }
            }
        }
        viewModelScope.launch {
            SdrLevel.listening.collect { on -> _state.update { it.copy(sdrListening = on) } }
        }
        viewModelScope.launch {
            SdrLevel.frequencyHz.collect { hz ->
                _state.update { state ->
                    if (state.frequencyHz == hz) state else state.copy(frequencyHz = hz, peakDb = state.levelDb)
                }
            }
        }
        viewModelScope.launch {
            SdrLevel.levelDb.collect { db ->
                _state.update { state ->
                    state.copy(levelDb = db, peakDb = maxOf(state.peakDb, db))
                }
            }
        }
    }

    fun setFace(face: FoxFace) = _state.update { it.copy(face = face) }

    fun huntWifi(ap: WifiAccessPoint) {
        arm(ap.ssid.ifBlank { ap.bssid }, sdrHunt = false) { _state.value.wifi.find { it.bssid == ap.bssid }?.rssi }
    }

    fun huntBle(device: BleDevice) {
        arm(device.displayName, sdrHunt = false) { _state.value.ble.find { it.address == device.address }?.rssi }
    }

    fun huntSdr() {
        arm("SDR", sdrHunt = true) {
            if (!SdrLevel.listening.value) null else SdrLevel.levelDb.value.roundToInt()
        }
    }

    fun tuneSdr(hz: Long) {
        sdr.tune(hz)
    }

    fun stepSdr(deltaHz: Long) {
        sdr.tune(_state.value.frequencyHz + deltaHz)
    }

    fun resetPeak() {
        _state.update { it.copy(peakDb = it.levelDb) }
    }

    fun resetBearing() {
        bestLevel = Int.MIN_VALUE
        recent.clear()
        FieldBearing.set(null, "No bearing yet")
        _state.update { it.copy(bearing = null, samples = emptyList(), cue = "Turn in a circle") }
    }

    fun stopHunt() {
        hunt?.cancel()
        targetLevel = null
        resetBearing()
        _state.update { it.copy(target = null, levelLabel = "Pick a signal", sdrHunt = false, face = FoxFace.COMPASS) }
    }

    private fun arm(label: String, sdrHunt: Boolean, level: () -> Int?) {
        hunt?.cancel()
        targetLevel = level
        bestLevel = Int.MIN_VALUE
        recent.clear()
        val face = if (sdrHunt) FoxFace.METER else FoxFace.COMPASS
        _state.update {
            it.copy(
                target = label,
                samples = emptyList(),
                bearing = null,
                cue = "Turn in a circle",
                sdrHunt = sdrHunt,
                face = face,
                peakDb = it.levelDb
            )
        }
        hunt = viewModelScope.launch {
            while (isActive) {
                val value = level()
                if (value != null) sample(value)
                delay(200)
            }
        }
    }

    private fun sample(value: Int) {
        if (value > bestLevel) {
            bestLevel = value
            bestHeading = heading
            FieldBearing.set(bestHeading, "${_state.value.target} at ${bestHeading.roundToInt()}°")
        }
        recent.addLast(value)
        while (recent.size > 48) recent.removeFirst()
        val older = recent.take(recent.size / 2).average().takeIf { recent.size >= 8 }
        val newer = recent.takeLast(recent.size / 2).average().takeIf { recent.size >= 8 }
        val cue = when {
            older == null || newer == null -> "Turn in a circle"
            newer > older + 3 -> "Closer"
            newer < older - 3 -> "Further"
            else -> "About the same"
        }
        _state.update {
            it.copy(
                levelLabel = "$value",
                bearing = if (bestLevel == Int.MIN_VALUE) null else bestHeading,
                samples = recent.toList(),
                cue = cue
            )
        }
    }

    override fun onCleared() {
        hunt?.cancel()
        sensorManager.unregisterListener(compass)
        runCatching { locationManager.removeUpdates(fixes) }
    }
}
