package app.radiusrf.features.ultrasonic.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radiusrf.features.ultrasonic.domain.ToneGenerator
import app.radiusrf.features.ultrasonic.domain.UltrasonicAnalyzer
import app.radiusrf.features.ultrasonic.domain.UltrasonicScreenTab
import app.radiusrf.features.ultrasonic.domain.UltrasonicState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

@HiltViewModel
class UltrasonicViewModel @Inject constructor(
    private val analyzer: UltrasonicAnalyzer
) : ViewModel() {

    private val _state = MutableStateFlow(UltrasonicState())
    val state: StateFlow<UltrasonicState> = _state.asStateFlow()

    private var analyzeJob: Job? = null
    private var toneJob: Job? = null
    private val toneGenerator = ToneGenerator()

    fun setActiveTab(tab: UltrasonicScreenTab) {
        _state.value = _state.value.copy(activeTab = tab)
    }

    fun startAnalysis() {
        if (analyzeJob?.isActive == true) return
        _state.value = _state.value.copy(isRecording = true, isPaused = false, error = null)
        analyzeJob = viewModelScope.launch {
            analyzer.analyze()
                .catch { e -> _state.value = _state.value.copy(isRecording = false, error = e.message) }
                .collect { _state.value = it.copy(isPaused = false) }
        }
    }

    fun pauseAnalysis() {
        if (!_state.value.isRecording) return
        analyzeJob?.cancel(); analyzeJob = null
        _state.value = _state.value.copy(isRecording = false, isPaused = true)
    }

    fun resumeAnalysis() = startAnalysis()

    fun stopAnalysis() {
        analyzeJob?.cancel(); analyzeJob = null
        _state.value = _state.value.copy(isRecording = false, isPaused = false)
    }

    fun setToneFrequency(frequencyHz: Int) {
        _state.value = _state.value.copy(toneFrequency = frequencyHz)
        if (_state.value.isTonePlaying) toneGenerator.setFrequency(frequencyHz)
    }

    fun startTone() {
        if (toneJob?.isActive == true) return
        val frequency = _state.value.toneFrequency
        _state.value = _state.value.copy(isTonePlaying = true)
        toneJob = viewModelScope.launch(Dispatchers.Default) {
            try { toneGenerator.start(frequency) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.value = _state.value.copy(isTonePlaying = false, error = "Tone generation failed: ${e.message}") }
        }
    }

    fun stopTone() {
        toneGenerator.stop(); toneJob?.cancel(); toneJob = null
        _state.value = _state.value.copy(isTonePlaying = false)
    }

    override fun onCleared() { stopAnalysis(); stopTone() }
}
