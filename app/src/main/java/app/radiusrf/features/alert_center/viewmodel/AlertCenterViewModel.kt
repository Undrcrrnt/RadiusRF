package app.radiusrf.features.alert_center.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radiusrf.core.alerts.AlertCenterRepository
import app.radiusrf.core.alerts.AlertResolution
import app.radiusrf.core.alerts.UnifiedAlert
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import app.radiusrf.core.debug.DemoDataBus
import app.radiusrf.core.debug.DemoData
import app.radiusrf.core.debug.observeDemoRequests

@HiltViewModel
class AlertCenterViewModel @Inject constructor(
    private val repository: AlertCenterRepository,
    private val demoBus: DemoDataBus
) : ViewModel() {

    val alerts: StateFlow<List<UnifiedAlert>> = repository.alerts.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    fun clearAll() {
        viewModelScope.launch { repository.clearAll() }
    }

    fun resolve(alert: UnifiedAlert, resolution: AlertResolution) {
        viewModelScope.launch { repository.resolve(alert.id, resolution) }
    }

    fun reopen(alert: UnifiedAlert) {
        viewModelScope.launch { repository.reopen(alert.id) }
    }

    fun clearResolved() {
        viewModelScope.launch { repository.clearResolved() }
    }

    init {
        viewModelScope.launch { repository.pruneResolved() }
        observeDemoRequests(demoBus, DemoData.Routes.ALERT_CENTER) { loadDemoData() }
    }

    /** Debug-only: replaces live state with [DemoData] so the populated UI can be verified without hardware. */
    private fun loadDemoData() {
        viewModelScope.launch {
            DemoData.unifiedAlerts.forEach { repository.record(it.source, it.severity, it.title, it.detail, it.timestamp) }
        }
    }
}
