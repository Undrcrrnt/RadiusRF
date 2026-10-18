package app.radiusrf.features.wardriving.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

// Shared between WardrivingScanService (which owns the actual collection loop, so it survives
// screen navigation) and WardrivingViewModel (which is destroyed/recreated per navigation and
// needs to know whether a session is already running, and its true start time, on recreation).
@Singleton
class WardrivingSessionState @Inject constructor() {

    data class ActiveSession(val sessionId: String, val startTime: Long, val mode: SurveyMode, val paused: Boolean = false)

    private val _active = MutableStateFlow<ActiveSession?>(null)
    val active: StateFlow<ActiveSession?> = _active

    fun start(sessionId: String, mode: SurveyMode): Long {
        val startTime = System.currentTimeMillis()
        _active.value = ActiveSession(sessionId, startTime, mode)
        return startTime
    }

    fun pause() {
        _active.value = _active.value?.copy(paused = true)
    }

    fun resume() {
        _active.value = _active.value?.copy(paused = false)
    }

    fun stop() {
        _active.value = null
    }
}
