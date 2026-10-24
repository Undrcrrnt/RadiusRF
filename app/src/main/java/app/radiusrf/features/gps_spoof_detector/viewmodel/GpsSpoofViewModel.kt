package app.radiusrf.features.gps_spoof_detector.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radiusrf.core.alerts.AlertCenterRepository
import app.radiusrf.core.alerts.AlertSeverity
import app.radiusrf.core.alerts.AlertSource
import app.radiusrf.features.celltower.domain.CellTowerAnalyzer
import app.radiusrf.features.celltower.domain.CellTowerState
import app.radiusrf.features.gps.domain.GpsState
import app.radiusrf.features.gps.domain.GpsTracker
import app.radiusrf.features.gps_spoof_detector.domain.GpsSpoofDetector
import app.radiusrf.features.gps_spoof_detector.domain.GpsSpoofState
import app.radiusrf.features.gps_spoof_detector.domain.SpoofCheckResult
import app.radiusrf.features.sensors.domain.SensorDataCollector
import app.radiusrf.features.wifi.domain.WifiAccessPoint
import app.radiusrf.features.wifi.domain.WifiScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

@HiltViewModel
class GpsSpoofViewModel @Inject constructor(
    private val gpsTracker: GpsTracker,
    private val cellTowerAnalyzer: CellTowerAnalyzer,
    private val wifiScanner: WifiScanner,
    private val sensorDataCollector: SensorDataCollector,
    private val spoofDetector: GpsSpoofDetector,
    private val alertCenterRepository: AlertCenterRepository
) : ViewModel() {

    private val sensorOwner = Any()
    // Tracks whether spoofing was flagged on the previous analysis pass, so we
    // only record an alert on the rising edge instead of every 5s while it persists.
    private var wasSpoofDetected = false

    private val _state = MutableStateFlow(GpsSpoofState())
    val state: StateFlow<GpsSpoofState> = _state.asStateFlow()

    // Latest data from each source
    private var latestGps = GpsState()
    private var latestCell = CellTowerState()
    private var latestWifiAps: List<WifiAccessPoint> = emptyList()

    // Collection jobs
    private var gpsJob: Job? = null
    private var cellJob: Job? = null
    private var wifiJob: Job? = null
    private var analysisJob: Job? = null

    // History of results (keep last 20)
    private val resultHistory = mutableListOf<SpoofCheckResult>()

    fun startMonitoring() {
        if (_state.value.isMonitoring) return

        val keep = _state.value.isPaused
        if (!keep) {
            spoofDetector.reset()
            resultHistory.clear()
            wasSpoofDetected = false
            _state.value = GpsSpoofState(
                isMonitoring = true,
                gpsStatus = "Starting...",
                cellStatus = "Starting...",
                wifiStatus = "Starting...",
                sensorStatus = "Starting..."
            )
        } else {
            _state.value = _state.value.copy(isMonitoring = true, isPaused = false)
        }

        // Start sensor collection
        sensorDataCollector.start(sensorOwner)
        _state.value = _state.value.copy(sensorStatus = "Active")

        // Start GPS tracking
        gpsJob = viewModelScope.launch {
            gpsTracker.track()
                .catch { e ->
                    _state.value = _state.value.copy(gpsStatus = "Error: ${e.message}")
                }
                .collect { gps ->
                    latestGps = gps
                    _state.value = _state.value.copy(
                        gpsStatus = if (gps.latitude != 0.0) "Locked (${gps.satelliteCount} sats)" else "Acquiring..."
                    )
                }
        }

        // Start cell tower monitoring
        cellJob = viewModelScope.launch {
            cellTowerAnalyzer.monitor()
                .catch { e ->
                    _state.value = _state.value.copy(cellStatus = "Error: ${e.message}")
                }
                .collect { cell ->
                    latestCell = cell
                    _state.value = _state.value.copy(
                        cellStatus = if (cell.currentCell != null) {
                            "${cell.currentCell.type.displayName} (CID: ${cell.currentCell.cid})"
                        } else "No cell info"
                    )
                }
        }

        // Start WiFi scanning
        wifiJob = viewModelScope.launch {
            wifiScanner.scan()
                .catch { e ->
                    _state.value = _state.value.copy(wifiStatus = "Error: ${e.message}")
                }
                .collect { aps ->
                    latestWifiAps = aps
                    _state.value = _state.value.copy(
                        wifiStatus = "${aps.size} APs visible"
                    )
                }
        }

        // Run analysis every 5 seconds
        analysisJob = viewModelScope.launch {
            // Initial delay to let sources collect data
            delay(2000)
            while (true) {
                runAnalysis()
                delay(5000)
            }
        }
    }

    fun pauseMonitoring() {
        if (!_state.value.isMonitoring) return
        cancelMonitoring()
        _state.value = _state.value.copy(isMonitoring = false, isPaused = true)
    }

    fun resumeMonitoring() = startMonitoring()

    fun stopMonitoring() {
        cancelMonitoring()
        _state.value = _state.value.copy(
            isMonitoring = false,
            isPaused = false,
            gpsStatus = "Idle",
            cellStatus = "Idle",
            wifiStatus = "Idle",
            sensorStatus = "Idle"
        )
    }

    private fun cancelMonitoring() {
        gpsJob?.cancel()
        cellJob?.cancel()
        wifiJob?.cancel()
        analysisJob?.cancel()
        gpsJob = null
        cellJob = null
        wifiJob = null
        analysisJob = null
        sensorDataCollector.stop(sensorOwner)
    }

    private fun runAnalysis() {
        try {
            val accelerometer = sensorDataCollector.accelerometer.value
            val barometer = sensorDataCollector.barometer.value

            val result = spoofDetector.analyze(
                gps = latestGps,
                cell = latestCell.currentCell,
                wifiAps = latestWifiAps,
                accelerometer = accelerometer,
                barometer = barometer
            )

            resultHistory.add(0, result)
            if (resultHistory.size > 20) resultHistory.removeAt(resultHistory.lastIndex)

            val spoofDetected = result.spoofConfidence > 0.3f
            if (spoofDetected && !wasSpoofDetected) {
                reportSpoofDetected(result)
            }
            wasSpoofDetected = spoofDetected

            _state.value = _state.value.copy(
                spoofDetected = spoofDetected,
                confidence = result.spoofConfidence,
                results = resultHistory.toList(),
                error = null
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                error = "Analysis error: ${e.message}"
            )
        }
    }

    private fun reportSpoofDetected(result: SpoofCheckResult) {
        val failedChecks = result.checks.filter { !it.passed }
        val severity = when {
            result.spoofConfidence >= 0.6f -> AlertSeverity.CRITICAL
            result.spoofConfidence >= 0.4f -> AlertSeverity.HIGH
            else -> AlertSeverity.MEDIUM
        }
        viewModelScope.launch {
            alertCenterRepository.record(
                source = AlertSource.GPS_SPOOF,
                severity = severity,
                title = "Possible GPS Spoofing (%.0f%% confidence)".format(result.spoofConfidence * 100),
                detail = if (failedChecks.isNotEmpty()) {
                    "Failed checks: ${failedChecks.joinToString("; ") { "${it.name} (${it.detail})" }}"
                } else {
                    "Spoof confidence crossed the alert threshold"
                }
            )
        }
    }

    override fun onCleared() {
        stopMonitoring()
    }
}
