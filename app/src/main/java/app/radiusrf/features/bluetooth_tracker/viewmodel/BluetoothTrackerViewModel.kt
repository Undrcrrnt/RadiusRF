package app.radiusrf.features.bluetooth_tracker.viewmodel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radiusrf.core.alerts.AlertCenterRepository
import app.radiusrf.core.alerts.AlertSeverity
import app.radiusrf.core.alerts.AlertSource
import app.radiusrf.features.ble.domain.BleScanner
import app.radiusrf.features.bluetooth_tracker.domain.DetectedTracker
import app.radiusrf.features.bluetooth_tracker.domain.TrackerIdentifier
import app.radiusrf.features.bluetooth_tracker.domain.TrackerScanState
import app.radiusrf.features.bluetooth_tracker.domain.TrackerType
import app.radiusrf.features.bluetooth_tracker.domain.TrackingRisk
import app.radiusrf.core.sessions.ItemKind
import app.radiusrf.core.sessions.SessionItem
import app.radiusrf.core.sessions.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import app.radiusrf.core.debug.DemoDataBus
import app.radiusrf.core.debug.DemoData
import app.radiusrf.core.debug.observeDemoRequests

@HiltViewModel
class BluetoothTrackerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bleScanner: BleScanner,
    private val alertCenterRepository: AlertCenterRepository,
    private val sessions: SessionRepository,
    private val demoBus: DemoDataBus
) : ViewModel() {

    /** When the current run started; null when no run is in progress. */
    private var runStartedAt: Long? = null


    private val _state = MutableStateFlow(TrackerScanState())
    val state: StateFlow<TrackerScanState> = _state.asStateFlow()

    private val identifier = TrackerIdentifier()
    private val trackerMap = mutableMapOf<String, DetectedTracker>()
    private val places = mutableMapOf<String, MutableList<Pair<Double, Double>>>()
    private var scanJob: Job? = null
    private var timerJob: Job? = null
    private var scanStartTime: Long = 0L
    private var totalDevicesScanned = 0

    // Highest risk tier already reported per address, so we only alert again
    // when a tracker's risk climbs to a new tier, not on every BLE advertisement.
    private val reportedRiskTiers = mutableMapOf<String, TrackingRisk>()

    fun startScan() {
        if (_state.value.isScanning) return

        scanJob?.cancel()
        timerJob?.cancel()
        val keep = _state.value.isPaused
        if (!keep) {
            trackerMap.clear()
            places.clear()
            totalDevicesScanned = 0
            scanStartTime = System.currentTimeMillis()
            _state.value = TrackerScanState(isScanning = true)
            runStartedAt = System.currentTimeMillis()
        } else {
            _state.value = _state.value.copy(isScanning = true, isPaused = false, error = null)
        }

        // Timer to update scan duration every second
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000L)
                val elapsed = System.currentTimeMillis() - scanStartTime
                _state.value = _state.value.copy(scanDurationMs = elapsed)
            }
        }

        scanJob = viewModelScope.launch {
            bleScanner.scan(lowLatency = true, timeoutMs = 0)
                .catch { e ->
                    _state.value = _state.value.copy(
                        isScanning = false,
                        error = "Scan failed: ${e.message}"
                    )
                    timerJob?.cancel()
                }
                .collect { devices ->
                    totalDevicesScanned = devices.size
                    val now = System.currentTimeMillis()

                    for (device in devices) {
                        val match = identifier.match(device) ?: continue
                        notePlace(match.identity)
                        val placeCount = places[match.identity]?.size ?: 0
                        val existing = trackerMap[match.identity]
                        val updated = if (existing != null) {
                            val bump = now - existing.lastSeen > 1_000L
                            existing.copy(
                                address = device.address,
                                rssi = device.rssi,
                                lastSeen = now,
                                seenCount = existing.seenCount + if (bump) 1 else 0,
                                name = device.name ?: existing.name,
                                places = placeCount
                            )
                        } else {
                            DetectedTracker(
                                address = device.address,
                                name = device.name,
                                type = match.type,
                                rssi = device.rssi,
                                firstSeen = now,
                                lastSeen = now,
                                seenCount = 1,
                                risk = TrackingRisk.LOW,
                                identity = match.identity,
                                places = placeCount
                            )
                        }
                        trackerMap[match.identity] = updated.copy(risk = identifier.assessRisk(updated))
                    }

                    // Rebuild the state with sorted trackers
                    val sortedTrackers = trackerMap.values
                        .sortedWith(
                            compareBy<DetectedTracker> { it.risk.ordinal }
                                .thenByDescending { it.seenCount }
                        )
                        .toList()

                    val highRisk = sortedTrackers.count {
                        it.risk == TrackingRisk.HIGH
                    }

                    reportRiskEscalations(sortedTrackers)

                    _state.value = _state.value.copy(
                        trackers = sortedTrackers,
                        totalDevicesScanned = totalDevicesScanned,
                        highRiskCount = highRisk,
                        scanDurationMs = System.currentTimeMillis() - scanStartTime
                    )
                }
        }
    }

    fun pauseScan() {
        if (!_state.value.isScanning) return
        scanJob?.cancel()
        timerJob?.cancel()
        scanJob = null
        timerJob = null
        _state.value = _state.value.copy(isScanning = false, isPaused = true)
    }

    fun resumeScan() = startScan()

    fun stopScan() {
        runStartedAt?.let { started ->
            runStartedAt = null
            recordSession(started)
        }
        scanJob?.cancel()
        timerJob?.cancel()
        scanJob = null
        timerJob = null
        _state.value = _state.value.copy(isScanning = false, isPaused = false)
    }

    fun clearTrackers() {
        stopScan()
        trackerMap.clear()
        places.clear()
        totalDevicesScanned = 0
        scanStartTime = 0L
        reportedRiskTiers.clear()
        _state.value = TrackerScanState()
    }

    private fun reportRiskEscalations(trackers: List<DetectedTracker>) {
        val escalated = trackers.filter { tracker ->
            val rank = tracker.risk.severityRank()
            if (rank < MIN_ALERT_RANK) return@filter false
            val previousRank = reportedRiskTiers[tracker.identity]?.severityRank() ?: -1
            if (rank > previousRank) {
                reportedRiskTiers[tracker.identity] = tracker.risk
                true
            } else {
                false
            }
        }
        if (escalated.isEmpty()) return

        viewModelScope.launch {
            escalated.forEach { tracker ->
                alertCenterRepository.record(
                    source = AlertSource.BLUETOOTH_TRACKER,
                    severity = if (tracker.risk == TrackingRisk.HIGH) AlertSeverity.HIGH else AlertSeverity.MEDIUM,
                    title = "${tracker.type.label} seen in ${tracker.places} places",
                    detail = "${tracker.type.label} address ${tracker.address}, seen ${tracker.seenCount} times.",
                    timestamp = tracker.lastSeen
                )
            }
        }
    }

    private fun notePlace(identity: String) {
        val here = lastLocation() ?: return
        val list = places.getOrPut(identity) { mutableListOf() }
        if (list.none { meters(it.first, it.second, here.first, here.second) < 250.0 }) {
            list.add(here)
        }
    }

    private fun lastLocation(): Pair<Double, Double>? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val fix = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstNotNullOfOrNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            ?: return null
        return fix.latitude to fix.longitude
    }

    private fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    private fun TrackingRisk.severityRank(): Int = when (this) {
        TrackingRisk.HIGH -> 3
        TrackingRisk.MEDIUM -> 2
        TrackingRisk.LOW -> 1
        TrackingRisk.NONE -> 0
    }

    companion object {
        private const val MIN_ALERT_RANK = 3
    }

    private fun recordSession(startedAt: Long) {
        val trackers = _state.value.trackers
        val risky = trackers.count { it.risk == TrackingRisk.HIGH || it.risk == TrackingRisk.MEDIUM }
        sessions.recordInBackground(
            tool = "bluetooth_tracker",
            title = "Tracker watch",
            startedAt = startedAt,
            items = trackers.map { t ->
                SessionItem(
                    key = t.address,
                    label = t.displayName,
                    kind = ItemKind.TRACKER,
                    rssi = t.rssi,
                    detail = "${t.risk.label} · seen ${t.seenCount}×",
                    flagged = t.risk == TrackingRisk.HIGH || t.risk == TrackingRisk.MEDIUM
                )
            },
            summary = "${trackers.size} tracker${if (trackers.size == 1) "" else "s"} nearby" +
                if (risky > 0) " · $risky seen in 3 or more places" else ""
        )
    }

    override fun onCleared() {
        stopScan()
    }

    init {
        observeDemoRequests(demoBus, DemoData.Routes.BLUETOOTH_TRACKER) { loadDemoData() }
    }

    /** Debug-only: replaces live state with [DemoData] so the populated UI can be verified without hardware. */
    private fun loadDemoData() {
        stopScan()
        _state.value = _state.value.copy(
            trackers = DemoData.trackers,
            totalDevicesScanned = 42,
            scanDurationMs = 95_000L,
            highRiskCount = DemoData.trackers.count { it.risk == TrackingRisk.HIGH },
            error = null
        )
    }
}
