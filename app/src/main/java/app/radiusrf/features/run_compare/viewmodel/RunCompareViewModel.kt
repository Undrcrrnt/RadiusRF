package app.radiusrf.features.run_compare.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radiusrf.core.debug.DemoData
import app.radiusrf.core.debug.DemoDataBus
import app.radiusrf.core.debug.observeDemoRequests
import app.radiusrf.features.run_compare.data.PreparedComparison
import app.radiusrf.features.run_compare.data.RunCompareRepository
import app.radiusrf.features.run_compare.domain.CompareSighting
import app.radiusrf.features.run_compare.domain.DeviceMatch
import app.radiusrf.features.run_compare.domain.RunCompare
import app.radiusrf.features.run_compare.domain.RunTrack
import app.radiusrf.features.run_compare.domain.SurveyRun
import app.radiusrf.features.wardriving.domain.SurveyRadio
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class RunCompareState(
    val runs: List<SurveyRun> = emptyList(),
    val selected: Set<String> = emptySet(),
    val tracks: List<RunTrack> = emptyList(),
    val matches: List<DeviceMatch>? = null,
    val layerMac: String? = null,
    val openMac: String? = null,
    val detail: List<CompareSighting> = emptyList(),
    val detailMac: String? = null,
    val note: String? = null,
    val comparing: Boolean = false
)

@HiltViewModel
class RunCompareViewModel @Inject constructor(
    private val repository: RunCompareRepository,
    demoBus: DemoDataBus
) : ViewModel() {

    private val _state = MutableStateFlow(RunCompareState())
    val state: StateFlow<RunCompareState> = _state.asStateFlow()

    private var showingDemo = false
    private var demoSightings: List<CompareSighting>? = null
    private var detailJob: Job? = null

    init {
        observeDemoRequests(demoBus, DemoData.Routes.RUN_COMPARE) { loadDemo() }
        viewModelScope.launch {
            repository.runs().collect { runs ->
                if (showingDemo) return@collect
                _state.update { it.copy(runs = runs) }
            }
        }
    }

    fun toggle(id: String) {
        _state.update { state ->
            val selected = state.selected.toMutableSet()
            val note = when {
                id in selected -> {
                    selected.remove(id)
                    null
                }
                selected.size >= RunCompare.MAX_RUNS -> "Four runs is the most this comparison uses."
                else -> {
                    selected.add(id)
                    null
                }
            }
            state.copy(selected = selected, note = note)
        }
    }

    fun delete(id: String) {
        if (_state.value.comparing) return
        if (showingDemo) {
            demoSightings = demoSightings?.filter { it.sessionId != id }
            _state.update { state ->
                state.copy(
                    runs = state.runs.filter { it.id != id },
                    selected = state.selected - id,
                    note = null
                )
            }
            return
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.delete(id) }
                _state.update { it.copy(selected = it.selected - id, note = null) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _state.update { it.copy(note = "That run could not be deleted.") }
            }
        }
    }

    fun compare() {
        val snapshot = _state.value
        val ids = snapshot.runs.filter { it.id in snapshot.selected }.map { it.id }
        if (ids.size < RunCompare.MIN_RUNS) {
            _state.update { it.copy(note = "Select at least two runs.") }
            return
        }
        detailJob?.cancel()
        viewModelScope.launch {
            _state.update { it.copy(comparing = true, note = null) }
            try {
                val prepared = demoSightings?.let { rows -> prepareDemo(ids, rows) }
                    ?: withContext(Dispatchers.IO) { repository.comparison(ids) }
                _state.update {
                    it.copy(
                        comparing = false,
                        tracks = prepared.tracks,
                        matches = prepared.matches,
                        layerMac = null,
                        openMac = null,
                        detail = emptyList(),
                        detailMac = null
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _state.update { it.copy(comparing = false, note = "These runs could not be compared.") }
            }
        }
    }

    fun chooseRuns() {
        detailJob?.cancel()
        _state.update {
            it.copy(
                matches = null,
                tracks = emptyList(),
                layerMac = null,
                openMac = null,
                detail = emptyList(),
                detailMac = null,
                note = null
            )
        }
    }

    fun setLayer(mac: String?) {
        _state.update { it.copy(layerMac = mac) }
        if (mac != null) loadDevice(mac)
    }

    fun open(mac: String) {
        _state.update { it.copy(openMac = mac) }
        loadDevice(mac)
    }

    fun closeDetail() {
        val layer = _state.value.layerMac
        _state.update { it.copy(openMac = null) }
        if (layer != null && _state.value.detailMac != layer) loadDevice(layer)
    }

    private fun loadDevice(mac: String) {
        if (_state.value.detailMac == mac && _state.value.detail.isNotEmpty()) return
        val ids = _state.value.runs.filter { it.id in _state.value.selected }.map { it.id }
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            val rows = try {
                demoSightings?.let { stored ->
                    stored.filter { it.sessionId in ids && it.mac.trim().equals(mac, ignoreCase = true) }
                        .sortedBy { it.timestamp }
                } ?: withContext(Dispatchers.IO) { repository.deviceSightings(ids, mac) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _state.update { it.copy(detailMac = mac, detail = emptyList(), note = "That device could not be opened.") }
                return@launch
            }
            _state.update { state ->
                if (state.openMac == mac || state.layerMac == mac) {
                    state.copy(detail = rows, detailMac = mac)
                } else {
                    state
                }
            }
        }
    }

    private fun prepareDemo(
        ids: List<String>,
        rows: List<CompareSighting>
    ) = PreparedComparison(
        matches = RunCompare.matches(rows.filter { it.sessionId in ids }),
        tracks = ids.map { id ->
            RunTrack(id, RunCompare.track(rows.filter { it.sessionId == id }))
        }
    )

    private fun loadDemo() {
        val built = demoSightings()
        showingDemo = true
        demoSightings = built
        val runs = built.groupBy { it.sessionId }.map { (id, rows) ->
            SurveyRun(
                id = id,
                startedAt = rows.minOf { it.timestamp },
                endedAt = rows.maxOf { it.timestamp },
                sightings = rows.size
            )
        }.sortedBy { it.startedAt }
        _state.value = RunCompareState(runs = runs, selected = runs.map { it.id }.toSet())
    }

    private fun demoSightings(): List<CompareSighting> {
        val start = System.currentTimeMillis() - 3_600_000
        val follower = "AA:BB:CC:11:22:01"
        val passer = "DE:AD:BE:EF:00:02"
        return buildList {
            leg("North leg", start, 38.9000, -77.0400, 38.9300, -77.0400, follower, passer = null)
            leg("East leg", start + 1_200_000, 38.9300, -77.0400, 38.9300, -77.0000, follower, passer)
            leg("South leg", start + 2_400_000, 38.9300, -77.0000, 38.8900, -77.0000, follower, passer = null)
            // Heard at the stop where the first leg ends and the second begins. It does not travel.
            add(sample("North leg", "10:20:30:40:50:60", "CafeWiFi", SurveyRadio.WIFI, 38.9300, -77.0400, start + 300_000L))
            add(sample("East leg", "10:20:30:40:50:60", "CafeWiFi", SurveyRadio.WIFI, 38.9301, -77.0401, start + 1_200_000L))
        }
    }

    private fun MutableList<CompareSighting>.leg(
        id: String,
        start: Long,
        fromLat: Double,
        fromLng: Double,
        toLat: Double,
        toLng: Double,
        follower: String,
        passer: String?
    ) {
        repeat(5) { step ->
            val t = step / 4.0
            val lat = fromLat + (toLat - fromLat) * t
            val lng = fromLng + (toLng - fromLng) * t
            add(sample(id, "route-ap-$id-$step", "Street$step", SurveyRadio.WIFI, lat, lng, start + step * 60_000L))
            add(sample(id, follower, "CarHotspot", SurveyRadio.WIFI, lat + 0.0004, lng + 0.0003, start + step * 60_000L + 5_000L))
        }
        if (passer != null) {
            add(sample(id, passer, "Pixel", SurveyRadio.BLE, fromLat + 0.001, fromLng, start + 30_000L))
        }
    }

    private fun sample(
        session: String,
        mac: String,
        name: String,
        radio: String,
        lat: Double,
        lng: Double,
        time: Long
    ) = CompareSighting(
        sessionId = session,
        mac = mac,
        name = name,
        radio = radio,
        rssi = -62,
        frequency = if (radio == SurveyRadio.WIFI) 2412 else 0,
        capabilities = if (radio == SurveyRadio.WIFI) "[WPA2-PSK-CCMP][ESS]" else null,
        lat = lat,
        lng = lng,
        timestamp = time,
        altitude = 20.0,
        accuracy = 8.0
    )
}
