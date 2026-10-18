package app.radiusrf.features.rogue_ap_detector.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radiusrf.core.alerts.AlertCenterRepository
import app.radiusrf.core.alerts.AlertSeverity
import app.radiusrf.core.alerts.AlertSource
import app.radiusrf.features.rogue_ap_detector.domain.RiskLevel
import app.radiusrf.features.rogue_ap_detector.domain.RogueApAlert
import app.radiusrf.features.rogue_ap_detector.domain.RogueApAnalyzer
import app.radiusrf.features.rogue_ap_detector.domain.RogueApState
import app.radiusrf.features.wifi.domain.WifiScanner
import app.radiusrf.core.prefs.AppSettings
import app.radiusrf.core.sessions.ItemKind
import app.radiusrf.core.sessions.SessionItem
import app.radiusrf.core.sessions.SessionRepository
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
class RogueApViewModel @Inject constructor(
    private val wifiScanner: WifiScanner,
    private val alertCenterRepository: AlertCenterRepository,
    private val sessions: SessionRepository,
    private val settings: AppSettings,
    private val demoBus: DemoDataBus
) : ViewModel() {

    /** When the current run started; null when no run is in progress. */
    private var runStartedAt: Long? = null


    private val analyzer = RogueApAnalyzer()

    private val _state = MutableStateFlow(RogueApState(knownSsids = settings.trustedNetworks.value))
    val state: StateFlow<RogueApState> = _state.asStateFlow()

    private var scanJob: Job? = null

    // Keys of alerts already forwarded to the Alert Center this session, so a
    // persistent rogue AP doesn't get re-recorded on every WiFi scan cycle.
    private val reportedAlertKeys = mutableSetOf<String>()

    fun startScan() {
        if (scanJob?.isActive == true) return
        val keep = _state.value.isPaused
        _state.value = _state.value.copy(isScanning = true, isPaused = false, error = null)
        if (!keep) runStartedAt = System.currentTimeMillis()

        scanJob = viewModelScope.launch {
            wifiScanner.scan()
                .catch { e ->
                    _state.value = _state.value.copy(
                        isScanning = false,
                        error = "WiFi scan error: ${e.message}"
                    )
                }
                .collect { accessPoints ->
                    val currentKnown = _state.value.knownSsids
                    val alerts = analyzer.analyze(accessPoints, currentKnown)

                    val suspiciousBssids = alerts.map { it.suspiciousAp.bssid }.toSet()
                    val safeCount = accessPoints.count { it.bssid !in suspiciousBssids }

                    reportNewAlerts(alerts)

                    _state.value = _state.value.copy(
                        isScanning = true,
                        totalAps = accessPoints.size,
                        alerts = alerts,
                        safeAps = safeCount,
                        suspiciousAps = alerts.size,
                        error = null
                    )
                }
        }
    }

    fun pauseScan() {
        if (!_state.value.isScanning) return
        scanJob?.cancel()
        scanJob = null
        _state.value = _state.value.copy(isScanning = false, isPaused = true)
    }

    fun resumeScan() = startScan()

    fun stopScan() {
        runStartedAt?.let { started ->
            runStartedAt = null
            recordSession(started)
        }
        scanJob?.cancel()
        scanJob = null
        _state.value = _state.value.copy(isScanning = false, isPaused = false)
    }

    /** Trusted networks are saved in [AppSettings] so they survive leaving the screen. */
    fun addKnownSsid(ssid: String) {
        val trimmed = ssid.trim()
        if (trimmed.isBlank()) return
        settings.setTrustedNetworks(_state.value.knownSsids + trimmed)
        _state.value = _state.value.copy(knownSsids = settings.trustedNetworks.value)
    }

    fun removeKnownSsid(ssid: String) {
        settings.setTrustedNetworks(_state.value.knownSsids - ssid)
        _state.value = _state.value.copy(knownSsids = settings.trustedNetworks.value)
    }

    fun clearAlerts() {
        reportedAlertKeys.clear()
        _state.value = _state.value.copy(
            alerts = emptyList(),
            suspiciousAps = 0,
            safeAps = _state.value.totalAps
        )
    }

    private fun reportNewAlerts(alerts: List<RogueApAlert>) {
        val newAlerts = alerts.filter { reportedAlertKeys.add("${it.suspiciousAp.bssid}:${it.threatType}") }
        if (newAlerts.isEmpty()) return
        viewModelScope.launch {
            newAlerts.forEach { alert ->
                alertCenterRepository.record(
                    source = AlertSource.ROGUE_AP,
                    severity = alert.riskLevel.toAlertSeverity(),
                    title = alert.title,
                    detail = alert.description,
                    timestamp = alert.timestamp
                )
            }
        }
    }

    private fun RiskLevel.toAlertSeverity(): AlertSeverity = when (this) {
        RiskLevel.CRITICAL -> AlertSeverity.CRITICAL
        RiskLevel.HIGH -> AlertSeverity.HIGH
        RiskLevel.MEDIUM, RiskLevel.SAFE -> AlertSeverity.MEDIUM
        RiskLevel.LOW -> AlertSeverity.LOW
    }

    private fun recordSession(startedAt: Long) {
        val state = _state.value
        sessions.recordInBackground(
            tool = "rogue_ap",
            title = "Rogue AP check",
            startedAt = startedAt,
            items = state.alerts.map { a ->
                SessionItem(
                    key = a.suspiciousAp.bssid,
                    label = a.suspiciousAp.ssid,
                    kind = ItemKind.WIFI,
                    rssi = a.suspiciousAp.rssi,
                    detail = a.threatType.label,
                    flagged = a.riskLevel != RiskLevel.SAFE && a.riskLevel != RiskLevel.LOW
                )
            },
            summary = "${state.totalAps} networks checked · ${state.alerts.size} finding${if (state.alerts.size == 1) "" else "s"}"
        )
    }

    override fun onCleared() {
        stopScan()
    }

    init {
        observeDemoRequests(demoBus, DemoData.Routes.ROGUE_AP) { loadDemoData() }
    }

    /** Debug-only: replaces live state with [DemoData] so the populated UI can be verified without hardware. */
    private fun loadDemoData() {
        stopScan()
        _state.value = _state.value.copy(totalAps = 9, alerts = DemoData.rogueAlerts, safeAps = 6, suspiciousAps = 3, error = null)
    }
}
