package app.radiusrf.features.wardriving.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radiusrf.core.debug.DemoData
import app.radiusrf.core.debug.DemoDataBus
import app.radiusrf.core.debug.observeDemoRequests
import app.radiusrf.core.prefs.AppSettings
import app.radiusrf.features.wardriving.data.WardrivingRepository
import app.radiusrf.features.wardriving.domain.ExportFormat
import app.radiusrf.features.wardriving.domain.SurveyMode
import app.radiusrf.features.wardriving.domain.WardrivingRecord
import app.radiusrf.features.wardriving.domain.WardrivingSession
import app.radiusrf.features.wardriving.domain.WardrivingSessionState
import app.radiusrf.features.wardriving.domain.WardrivingState
import app.radiusrf.features.wardriving.domain.WardrivingStats
import app.radiusrf.features.wardriving.service.WardrivingScanService
import app.radiusrf.features.wifi.domain.WifiNicPreference
import app.radiusrf.features.wifi.domain.WifiNicSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class WardrivingViewModel @Inject constructor(
    private val repository: WardrivingRepository,
    private val sessionState: WardrivingSessionState,
    @ApplicationContext private val appContext: Context,
    private val demoBus: DemoDataBus,
    private val settings: AppSettings,
    val nic: WifiNicPreference
) : ViewModel() {

    private val _state = MutableStateFlow(WardrivingState())
    val state: StateFlow<WardrivingState> = _state.asStateFlow()

    private var mode: SurveyMode = SurveyMode.WIFI
    private var observeJob: Job? = null
    private var watchJob: Job? = null
    private var demoBound = false
    val redactDefault: Boolean get() = settings.redactExports.value

    init {
        viewModelScope.launch {
            sessionState.failure.collect { message ->
                if (!message.isNullOrBlank()) {
                    _state.update {
                        it.copy(
                            error = message,
                            isScanning = false,
                            isPaused = false,
                            session = it.session?.copy(isActive = false)
                        )
                    }
                }
            }
        }
    }

    fun selectSource(source: WifiNicSource) {
        nic.select(source)
    }

    fun attach(mode: SurveyMode) {
        if (watchJob != null && this.mode == mode) return
        this.mode = mode
        if (!demoBound && mode == SurveyMode.WIFI) {
            demoBound = true
            observeDemoRequests(demoBus, DemoData.Routes.WARDRIVING) { loadDemoData() }
        }
        watchJob?.cancel()
        watchJob = viewModelScope.launch {
            sessionState.active.collect { active ->
                when {
                    active?.mode == mode -> {
                        val paused = active.paused
                        _state.update {
                            it.copy(
                                otherSurvey = null,
                                isPaused = paused,
                                isScanning = !paused,
                                session = WardrivingSession(
                                    id = active.sessionId,
                                    startTime = active.startTime,
                                    isActive = !paused,
                                    recordCount = it.session?.recordCount ?: 0,
                                    uniqueBssids = it.session?.uniqueBssids ?: 0
                                )
                            )
                        }
                        if (observeJob?.isActive != true) {
                            observeRecords(active.sessionId, active.startTime)
                        }
                    }
                    active != null -> {
                        observeJob?.cancel()
                        _state.update {
                            it.copy(
                                isScanning = false,
                                otherSurvey = active.mode.title,
                                session = it.session?.copy(isActive = false)
                            )
                        }
                    }
                    else -> {
                        _state.update {
                            it.copy(
                                otherSurvey = null,
                                isScanning = false,
                                session = it.session?.copy(isActive = false)
                            )
                        }
                    }
                }
            }
        }
    }

    fun startSession() {
        if (_state.value.isPaused && _state.value.session != null) {
            resumeSession()
            return
        }
        val sessionId = UUID.randomUUID().toString()
        val startTime = sessionState.start(sessionId, mode)
        _state.value = WardrivingState(
            session = WardrivingSession(id = sessionId, startTime = startTime, isActive = true),
            isScanning = true,
            isPaused = false
        )
        val intent = Intent(appContext, WardrivingScanService::class.java).apply {
            putExtra(WardrivingScanService.EXTRA_SESSION_ID, sessionId)
            putExtra(WardrivingScanService.EXTRA_MODE, mode.name)
        }
        if (!startService(intent, foreground = true)) {
            sessionState.stop()
            _state.update { it.copy(isScanning = false, session = it.session?.copy(isActive = false)) }
            return
        }
        observeRecords(sessionId, startTime)
    }

    fun pauseSession() {
        val session = _state.value.session ?: return
        if (!_state.value.isScanning) return
        val intent = Intent(appContext, WardrivingScanService::class.java).apply {
            action = WardrivingScanService.ACTION_PAUSE
            putExtra(WardrivingScanService.EXTRA_SESSION_ID, session.id)
            putExtra(WardrivingScanService.EXTRA_MODE, mode.name)
        }
        if (!startService(intent, foreground = false)) return
        _state.update { it.copy(isScanning = false, isPaused = true, session = it.session?.copy(isActive = false)) }
    }

    fun resumeSession() {
        val session = _state.value.session ?: return
        sessionState.resume()
        val intent = Intent(appContext, WardrivingScanService::class.java).apply {
            action = WardrivingScanService.ACTION_RESUME
            putExtra(WardrivingScanService.EXTRA_SESSION_ID, session.id)
            putExtra(WardrivingScanService.EXTRA_MODE, mode.name)
        }
        if (!startService(intent, foreground = true)) return
        _state.update { it.copy(isScanning = true, isPaused = false, session = it.session?.copy(isActive = true)) }
        if (observeJob?.isActive != true) observeRecords(session.id, session.startTime)
    }

    private fun resumeObserving(sessionId: String, startTime: Long) {
        _state.update {
            it.copy(
                session = WardrivingSession(id = sessionId, startTime = startTime, isActive = true),
                isScanning = true,
                otherSurvey = null
            )
        }
        observeRecords(sessionId, startTime)
    }

    private fun observeRecords(sessionId: String, startTime: Long) {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            repository.getSessionRecords(sessionId).collect { records ->
                val ordered = records
                _state.update {
                    it.copy(
                        records = ordered,
                        session = it.session?.copy(
                            recordCount = ordered.size,
                            uniqueBssids = ordered.map { record -> record.bssid }.distinct().size
                        ),
                        stats = computeStats(ordered, startTime)
                    )
                }
            }
        }
    }

    fun stopSession() {
        observeJob?.cancel()
        observeJob = null
        sessionState.stop()
        appContext.stopService(Intent(appContext, WardrivingScanService::class.java))
        _state.update {
            it.copy(isScanning = false, isPaused = false, session = it.session?.copy(isActive = false))
        }
    }

    fun suggestedExportName(format: ExportFormat): String {
        val sessionId = _state.value.session?.id ?: "session"
        return "${mode.fileSlug}_${sessionId.take(8)}_${format.fileSuffix}"
    }

    fun export(
        format: ExportFormat,
        fileName: String? = null,
        redactMacs: Boolean = settings.redactExports.value,
        onExported: (Uri) -> Unit
    ) {
        val sessionId = _state.value.session?.id ?: return
        viewModelScope.launch {
            try {
                val bytes = repository.exportSession(sessionId, format, redactMacs)
                val exportsDir = File(appContext.cacheDir, "exports").apply { mkdirs() }
                val fallback = suggestedExportName(format)
                val file = File(exportsDir, app.radiusrf.core.sessions.ExportNames.clean(fileName ?: fallback, fallback))
                file.writeBytes(bytes)
                val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
                _state.update { it.copy(exportStatus = "Exported ${format.label}") }
                onExported(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(exportStatus = "Export failed: ${e.message}") }
            }
        }
    }

    private fun startService(intent: Intent, foreground: Boolean): Boolean = try {
        if (foreground) appContext.startForegroundService(intent) else appContext.startService(intent)
        true
    } catch (error: Exception) {
        _state.update {
            it.copy(error = "Could not start background survey: ${error.message ?: "Android denied the service"}")
        }
        false
    }

    private fun computeStats(records: List<WardrivingRecord>, startTime: Long): WardrivingStats {
        val wifi = records.filter { it.isWifi }
        val uniqueSsids = wifi.mapNotNull { it.ssid }.distinct().size
        val uniqueBssids = records.map { it.bssid }.distinct().size
        val openCount = wifi.count { cap ->
            val capabilities = cap.capabilities ?: ""
            capabilities.isEmpty() || capabilities == "[ESS]"
        }
        val securedCount = wifi.size - openCount
        val durationMs = if (records.isNotEmpty()) System.currentTimeMillis() - startTime else 0L
        return WardrivingStats(
            totalRecords = records.size,
            uniqueSsids = uniqueSsids,
            uniqueBssids = uniqueBssids,
            openCount = openCount,
            securedCount = securedCount,
            sessionDurationMs = durationMs
        )
    }

    private fun loadDemoData() {
        val demo = DemoData.wardrivingRecords
        _state.value = _state.value.copy(
            session = WardrivingSession(
                id = "demo",
                startTime = System.currentTimeMillis() - DemoData.wardrivingStats.sessionDurationMs,
                recordCount = demo.size,
                uniqueBssids = demo.map { it.bssid }.distinct().size,
                isActive = false
            ),
            records = demo,
            stats = DemoData.wardrivingStats,
            error = null
        )
    }
}
