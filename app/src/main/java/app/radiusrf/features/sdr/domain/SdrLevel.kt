package app.radiusrf.features.sdr.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Latest tuned-frequency level from a listening SDR, shared with the fox hunt. */
object SdrLevel {
    private val _levelDb = MutableStateFlow(-120f)
    val levelDb: StateFlow<Float> = _levelDb.asStateFlow()

    private val _frequencyHz = MutableStateFlow(100_000_000L)
    val frequencyHz: StateFlow<Long> = _frequencyHz.asStateFlow()

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    fun update(levelDb: Float, frequencyHz: Long, listening: Boolean) {
        _levelDb.value = levelDb
        _frequencyHz.value = frequencyHz
        _listening.value = listening
    }

    fun setFrequency(hz: Long) {
        _frequencyHz.value = hz
    }
}
