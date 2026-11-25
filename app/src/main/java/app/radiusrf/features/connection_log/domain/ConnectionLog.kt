package app.radiusrf.features.connection_log.domain

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class ConnectionLog @Inject constructor() {
    private val _rows = MutableStateFlow<List<ConnectionRow>>(emptyList())
    val rows: StateFlow<List<ConnectionRow>> = _rows.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _status = MutableStateFlow("Off. Nothing is being recorded.")
    val status: StateFlow<String> = _status.asStateFlow()

    @Volatile var pcap: File? = null
        private set

    fun publish(rows: List<ConnectionRow>, running: Boolean, status: String, pcap: File?) {
        _rows.value = rows
        _running.value = running
        _status.value = status
        this.pcap = pcap
    }
}
