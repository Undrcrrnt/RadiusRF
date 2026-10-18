package app.radiusrf.features.wifi.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radiusrf.core.util.WifiBand
import app.radiusrf.features.wifi.domain.ChannelAnalyzer
import app.radiusrf.features.wifi.domain.ChannelScore
import app.radiusrf.features.wifi.domain.WifiAccessPoint
import app.radiusrf.features.wifi.domain.WifiScanner
import app.radiusrf.core.debug.DemoData
import app.radiusrf.core.debug.DemoDataBus
import app.radiusrf.core.debug.observeDemoRequests
import app.radiusrf.core.sessions.ItemKind
import app.radiusrf.core.sessions.SessionItem
import app.radiusrf.core.sessions.SessionRepository
import app.radiusrf.features.wifi.domain.WifiAssessment
import app.radiusrf.features.wifi.domain.WifiNicPreference
import app.radiusrf.features.wifi.domain.WifiNicSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.drop

@HiltViewModel
class WifiViewModel @Inject constructor(
    private val wifiScanner: WifiScanner,
    private val sessions: SessionRepository,
    val nic: WifiNicPreference,
    demoBus: DemoDataBus
) : ViewModel() {

    private val _accessPoints = MutableStateFlow<List<WifiAccessPoint>>(emptyList())
    val accessPoints: StateFlow<List<WifiAccessPoint>> = _accessPoints.asStateFlow()

    private val _channelScores = MutableStateFlow<List<ChannelScore>>(emptyList())
    val channelScores: StateFlow<List<ChannelScore>> = _channelScores.asStateFlow()

    private val _selectedBand = MutableStateFlow<WifiBand?>(null)
    val selectedBand: StateFlow<WifiBand?> = _selectedBand.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        observeDemoRequests(demoBus, DemoData.Routes.WIFI) { loadDemoData() }
        viewModelScope.launch {
            nic.source.drop(1).collect {
                val previous = scanJob
                if (previous?.isActive != true) return@collect
                stopScan()
                previous.join()
                startScan()
            }
        }
    }

    /** Debug-only: replaces live state with [DemoData] so the populated UI can be verified without nearby networks. */
    private fun loadDemoData() {
        stopScan()
        _error.value = null
        _accessPoints.value = DemoData.wifiAccessPoints
        _channelScores.value = ChannelAnalyzer.analyze(DemoData.wifiAccessPoints)
    }

    private var scanJob: Job? = null
    private var autoStopJob: Job? = null
    private var runInBackground = false

    /** When the current run started; null when no run is in progress. */
    private var runStartedAt: Long? = null

    fun selectSource(source: WifiNicSource) {
        nic.select(source)
    }

    fun startScan() {
        if (scanJob?.isActive == true) return
        if (!_isPaused.value) runStartedAt = System.currentTimeMillis()
        _isPaused.value = false
        _isScanning.value = true
        _error.value = null
        scanJob = viewModelScope.launch {
            wifiScanner.scan()
                .catch { e ->
                    // A revoked location permission or a disabled adapter must not take the
                    // whole process down; report it like the other scanners do.
                    _isScanning.value = false
                    _error.value = "WiFi scan error: ${e.message}"
                }
                .collect { aps ->
                    _accessPoints.value = aps
                    _channelScores.value = ChannelAnalyzer.analyze(aps)
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
        if (!_isScanning.value) return
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
        if (!_isScanning.value) return
        autoStopJob?.cancel()
        autoStopJob = null
        scanJob?.cancel()
        scanJob = null
        _isScanning.value = false
        _isPaused.value = true
    }

    fun resumeScan() = startScan()

    fun stopScan() {
        _isPaused.value = false
        runStartedAt?.let { started ->
            runStartedAt = null
            recordSession(started)
        }
        autoStopJob?.cancel()
        autoStopJob = null
        scanJob?.cancel()
        scanJob = null
        _isScanning.value = false
    }

    companion object {
        private const val AUTO_STOP_TIMEOUT_MS = 30_000L
    }

    fun selectBand(band: WifiBand?) {
        _selectedBand.value = band
    }

    private fun recordSession(startedAt: Long) {
        val aps = _accessPoints.value
        val weak = aps.count { WifiAssessment.isWeak(it.security) }
        sessions.recordInBackground(
            tool = "wifi",
            title = "WiFi scan",
            startedAt = startedAt,
            items = aps.map { ap ->
                SessionItem(
                    key = ap.bssid,
                    label = WifiAssessment.displayName(ap),
                    kind = ItemKind.WIFI,
                    rssi = ap.rssi,
                    detail = "${ap.security.label} · ch ${ap.channel}",
                    flagged = WifiAssessment.isWeak(ap.security)
                )
            },
            summary = "${aps.size} networks" + if (weak > 0) " · $weak weak security" else " · all encrypted"
        )
    }

    override fun onCleared() {
        stopScan()
    }
}
