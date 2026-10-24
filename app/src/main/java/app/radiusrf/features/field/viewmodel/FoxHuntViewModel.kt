package app.radiusrf.features.field.domain

import android.annotation.SuppressLint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radiusrf.features.sdr.domain.SdrLevel
import app.radiusrf.features.sdr.domain.SdrSession
import app.radiusrf.features.locate.domain.GeoSignalSample
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

enum class FoxFace { COMPASS, STRENGTH, MAP, HEATMAP, METER }

data class FoxHuntState(
    val target: String? = null,
    val levelLabel: String = "Pick a signal",
    val heading: Float = 0f,
    val bearing: Float? = null,
    val samples: List<Int> = emptyList(),
    val cue: String = "Sweep slowly left to right",
    val face: FoxFace = FoxFace.COMPASS,
    val lat: Double? = null,
    val lon: Double? = null,
    val sdrListening: Boolean = false,
    val sdrPaused: Boolean = false,
    val radioStatus: String? = null,
    val sdrHunt: Boolean = false,
    val frequencyHz: Long = 100_000_000L,
    val levelDb: Float = -120f,
    val liveDb: Float = -120f,
    val peakDb: Float = -120f,
    val floorDb: Float = -120f,
    val ceilingDb: Float = -20f,
    val measuringBackground: Boolean = false,
    val scaleLocked: Boolean = false,
    val locationAccuracyMeters: Float? = null,
    val geoSamples: List<GeoSignalSample> = emptyList()
)

@HiltViewModel
class FoxHuntViewModel @Inject constructor(
    private val sensorManager: SensorManager,
    private val locationManager: LocationManager,
    private val sdr: SdrSession
) : ViewModel() {
    private val _state = MutableStateFlow(FoxHuntState())
    val state: StateFlow<FoxHuntState> = _state.asStateFlow()

    private var hunt: Job? = null
    private var scaleJob: Job? = null
    private var heading = 0f
    private var bestLevel = Int.MIN_VALUE
    private var bestHeading = 0f
    private var recent = ArrayDeque<Int>()
    private var recentHeadings = ArrayDeque<Float>()
    private var targetLevel: (() -> Int?)? = null
    private var currentLocation: Location? = null
    private val baseline = ArrayDeque<Float>()
    private var lastTuneHz = 0L

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
            currentLocation = location
            _state.update {
                it.copy(
                    lat = location.latitude,
                    lon = location.longitude,
                    locationAccuracyMeters = location.accuracy
                )
            }
        }
        @Deprecated("Legacy callback")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    private var started = false
    private var hardwareActive = false

    fun start() {
        if (started) {
            resumeHardware()
            return
        }
        started = true
        _state.update { it.copy(sdrListening = sdr.engine.isRunning(), sdrPaused = sdr.paused) }
        resumeHardware()
        viewModelScope.launch {
            SdrLevel.listening.collect { on ->
                _state.update { it.copy(sdrListening = on, sdrPaused = if (on) sdr.paused else false) }
            }
        }
        viewModelScope.launch {
            sdr.pausedFlow.collect { held -> _state.update { it.copy(sdrPaused = held) } }
        }
        viewModelScope.launch {
            SdrLevel.frequencyHz.collect { hz ->
                val retune = lastTuneHz != 0L && lastTuneHz != hz
                lastTuneHz = hz
                _state.update { state -> if (state.frequencyHz == hz) state else state.copy(frequencyHz = hz) }
                if (retune && _state.value.sdrHunt) beginScale()
            }
        }
        viewModelScope.launch {
            SdrLevel.levelDb.collect { db -> onLevel(db) }
        }
    }

    @SuppressLint("MissingPermission")
    fun resumeHardware() {
        if (hardwareActive) return
        hardwareActive = true
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.also {
            sensorManager.registerListener(compass, it, SensorManager.SENSOR_DELAY_UI)
        }
        runCatching {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000L, 0f, fixes)
            val last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (last != null) fixes.onLocationChanged(last)
        }
    }

    fun pauseHardware() {
        if (!hardwareActive) return
        hardwareActive = false
        sensorManager.unregisterListener(compass)
        runCatching { locationManager.removeUpdates(fixes) }
    }

    fun setFace(face: FoxFace) = _state.update { it.copy(face = face) }

    fun startRadio() {
        viewModelScope.launch {
            val error = sdr.start()
            if (error != null) {
                _state.update { it.copy(radioStatus = error) }
                return@launch
            }
            if (!sdr.engine.isRunning()) return@launch
            _state.update { it.copy(sdrListening = true, sdrPaused = false, radioStatus = null) }
            if (_state.value.target == null) huntSdr()
        }
    }

    fun pauseRadio() {
        if (!_state.value.sdrListening || _state.value.sdrPaused) return
        sdr.pause()
        _state.update { it.copy(sdrPaused = true) }
    }

    fun stopRadio() {
        sdr.stop()
        stopHunt()
        _state.update { it.copy(sdrListening = false, sdrPaused = false, radioStatus = null) }
    }

    fun huntSdr() {
        arm("SDR", sdrHunt = true) {
            if (!SdrLevel.listening.value) null else _state.value.liveDb.roundToInt()
        }
        beginScale()
    }

    fun tuneSdr(hz: Long) {
        sdr.tune(hz)
    }

    fun stepSdr(deltaHz: Long) {
        sdr.tune(_state.value.frequencyHz + deltaHz)
    }

    fun resetPeak() {
        _state.update {
            it.copy(
                peakDb = it.liveDb,
                cue = if (it.sdrHunt) "Sweep slowly left to right" else it.cue
            )
        }
    }

    /** Locks the current peak as the top of the scale so left/right of the peak is easier to see. */
    fun setRef() {
        val s = _state.value
        val top = maxOf(s.peakDb, s.liveDb, s.floorDb + 8f)
        _state.update {
            it.copy(
                ceilingDb = top,
                scaleLocked = true,
                cue = "Work either side of the peak"
            )
        }
    }

    fun resetBearing() {
        bestLevel = Int.MIN_VALUE
        recent.clear()
        recentHeadings.clear()
        FieldBearing.set(null, "No bearing yet")
        _state.update {
            it.copy(
                bearing = null,
                samples = emptyList(),
                geoSamples = emptyList(),
                cue = if (it.sdrHunt) "Sweep slowly left to right" else "Turn in a circle"
            )
        }
    }

    fun stopHunt() {
        hunt?.cancel()
        scaleJob?.cancel()
        targetLevel = null
        resetBearing()
        _state.update {
            it.copy(
                target = null,
                levelLabel = "Pick a signal",
                sdrHunt = false,
                face = FoxFace.COMPASS,
                measuringBackground = false
            )
        }
    }

    private fun arm(label: String, sdrHunt: Boolean, level: () -> Int?) {
        hunt?.cancel()
        targetLevel = level
        bestLevel = Int.MIN_VALUE
        recent.clear()
        recentHeadings.clear()
        val face = if (sdrHunt) FoxFace.METER else FoxFace.COMPASS
        _state.update {
            it.copy(
                target = label,
                samples = emptyList(),
                geoSamples = emptyList(),
                bearing = null,
                cue = if (sdrHunt) "Measuring background — hold still" else "Turn in a circle",
                sdrHunt = sdrHunt,
                face = face,
                peakDb = it.liveDb
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
        recent.addLast(value)
        recentHeadings.addLast(heading)
        while (recent.size > 48) {
            recent.removeFirst()
            recentHeadings.removeFirst()
        }
        val strongestBin = recent.zip(recentHeadings)
            .groupBy { (_, degrees) -> (degrees / 30f).toInt().coerceIn(0, 11) }
            .maxByOrNull { (_, samples) -> samples.map { it.first }.average() }
        strongestBin?.let { (bin, samples) ->
            bestLevel = samples.map { it.first }.average().roundToInt()
            bestHeading = bin * 30f + 15f
            FieldBearing.set(bestHeading, "${_state.value.target} at ${bestHeading.roundToInt()}°")
        }
        val older = recent.take(recent.size / 2).average().takeIf { recent.size >= 8 }
        val newer = recent.takeLast(recent.size / 2).average().takeIf { recent.size >= 8 }
        val s = _state.value
        val cue = when {
            s.sdrHunt && s.measuringBackground -> "Measuring background — hold still"
            s.sdrHunt && newer != null && older != null && newer > older + 2 -> "Rising — keep turning"
            s.sdrHunt && s.peakDb - s.liveDb >= 4f && s.peakDb > s.floorDb + 4f -> "Past the peak — come back"
            s.sdrHunt && s.peakDb - s.liveDb < 2f && s.peakDb > s.floorDb + 4f -> "Peak — centre the antenna, then Set Ref"
            s.sdrHunt -> "Sweep slowly left to right"
            older == null || newer == null -> "Turn in a circle"
            newer > older + 3 -> "Closer"
            newer < older - 3 -> "Further"
            else -> "About the same"
        }
        val geoSamples = currentLocation
            ?.takeIf {
                it.accuracy <= 50f &&
                    SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos <= MAX_LOCATION_AGE_NS
            }
            ?.let { location ->
                appendFoxMapSample(
                    _state.value.geoSamples,
                    GeoSignalSample(
                        timeMs = System.currentTimeMillis(),
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracyMeters = location.accuracy,
                        rssi = value
                    )
                )
            }
            ?: _state.value.geoSamples
        _state.update {
            it.copy(
                levelLabel = "$value",
                bearing = if (bestLevel == Int.MIN_VALUE) null else bestHeading,
                samples = recent.toList(),
                geoSamples = geoSamples,
                cue = cue
            )
        }
    }

    private fun onLevel(db: Float) {
        val current = _state.value
        val live = if (current.measuringBackground || current.liveDb <= -119f) db
        else current.liveDb * 0.65f + db * 0.35f
        if (current.measuringBackground) {
            baseline.addLast(live)
            while (baseline.size > 24) baseline.removeFirst()
        }
        _state.update {
            it.copy(
                levelDb = db,
                liveDb = live,
                peakDb = if (it.measuringBackground) it.peakDb else maxOf(it.peakDb, live)
            )
        }
    }

    private fun beginScale() {
        scaleJob?.cancel()
        baseline.clear()
        _state.update {
            it.copy(
                measuringBackground = true,
                scaleLocked = false,
                peakDb = it.liveDb,
                cue = "Measuring background — hold still"
            )
        }
        scaleJob = viewModelScope.launch {
            delay(1_600)
            val samples = baseline.toList()
            val floor = if (samples.isEmpty()) -100f else samples.sorted()[samples.size / 5]
            val loud = samples.maxOrNull() ?: floor
            val ceiling = maxOf(floor + 40f, loud + 8f)
            _state.update {
                it.copy(
                    measuringBackground = false,
                    scaleLocked = true,
                    floorDb = floor,
                    ceilingDb = ceiling,
                    peakDb = it.liveDb,
                    cue = "Sweep slowly left to right"
                )
            }
        }
    }

    override fun onCleared() {
        hunt?.cancel()
        scaleJob?.cancel()
        pauseHardware()
    }

    private companion object {
        const val MAX_LOCATION_AGE_NS = 15_000_000_000L
    }
}

private fun appendFoxMapSample(
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
