package app.radiusrf.features.meshtastic.viewmodel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radiusrf.core.debug.DemoData
import app.radiusrf.core.debug.DemoDataBus
import app.radiusrf.core.debug.observeDemoRequests
import app.radiusrf.features.ble.domain.BleScanner
import app.radiusrf.features.meshtastic.domain.LoraMesh
import app.radiusrf.features.meshtastic.domain.MeshCompanionId
import app.radiusrf.features.meshtastic.domain.MeshSurveyMode
import app.radiusrf.features.sdr.domain.SdrDecodeSession
import app.radiusrf.features.sdr.domain.SdrRadio
import app.radiusrf.features.sdr.domain.SdrSession
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MeshNode(
    val id: String,
    val protocol: String,
    val name: String,
    val detail: String,
    val rssi: Int?,
    val path: String,
    val lat: Double?,
    val lng: Double?
)

data class MeshtasticState(
    val survey: MeshSurveyMode = MeshSurveyMode.BLUETOOTH,
    val isScanning: Boolean = false,
    val isPaused: Boolean = false,
    val nodes: List<MeshNode> = emptyList(),
    val channel: String? = null,
    val status: String? = null
)

@HiltViewModel
class MeshtasticViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bleScanner: BleScanner,
    session: SdrSession,
    private val demoBus: DemoDataBus
) : ViewModel() {
    private val decode = SdrDecodeSession(session)
    private val radio = { session.radio }
    private val _state = MutableStateFlow(MeshtasticState())
    val state: StateFlow<MeshtasticState> = _state.asStateFlow()
    private var job: Job? = null
    private var lastPublishMs = 0L
    private val lock = Any()
    private val accI = ArrayList<Float>(40_000)
    private val accQ = ArrayList<Float>(40_000)

    init {
        observeDemoRequests(demoBus, DemoData.Routes.MESHTASTIC) { loadDemo() }
    }

    fun setSurvey(survey: MeshSurveyMode) {
        if (_state.value.survey == survey) return
        stopScan()
        _state.update { it.copy(survey = survey, nodes = emptyList(), channel = null, status = null) }
    }

    fun startScan() {
        if (job?.isActive == true) return
        if (_state.value.survey == MeshSurveyMode.LORA) startLora() else startBluetooth()
    }

    fun pauseScan() {
        job?.cancel()
        job = null
        if (_state.value.survey == MeshSurveyMode.LORA) decode.pause()
        _state.update { it.copy(isScanning = false, isPaused = true) }
    }

    fun resumeScan() = startScan()

    fun stopScan() {
        job?.cancel()
        job = null
        decode.stop()
        synchronized(lock) {
            accI.clear()
            accQ.clear()
        }
        _state.update { it.copy(isScanning = false, isPaused = false, channel = null) }
    }

    private fun startBluetooth() {
        _state.update { it.copy(isScanning = true, isPaused = false, status = null, channel = null) }
        job = viewModelScope.launch {
            bleScanner.scan(timeoutMs = 0).catch { error ->
                _state.update { it.copy(status = error.message) }
            }.collect { devices ->
                val heard = devices.mapNotNull { device ->
                    val match = MeshCompanionId.identify(device) ?: return@mapNotNull null
                    val here = here()
                    MeshNode(
                        id = device.address,
                        protocol = match.protocol,
                        name = device.name ?: match.protocol,
                        detail = match.detail,
                        rssi = device.rssi,
                        path = "Bluetooth",
                        lat = here?.first,
                        lng = here?.second
                    )
                }
                val now = SystemClock.elapsedRealtime()
                if (heard.isEmpty() || now - lastPublishMs < 500L && heard.size == _state.value.nodes.size) return@collect
                lastPublishMs = now
                _state.update { state ->
                    state.copy(nodes = merge(state.nodes, heard))
                }
            }
        }
    }

    private fun startLora() {
        _state.update { it.copy(isScanning = true, isPaused = false, status = "Opening receiver", channel = label(CHANNELS[0])) }
        job = viewModelScope.launch {
            val error = decode.start(CHANNELS[0], 1_024_000) { iq, n, signed, rate, hz ->
                onIq(iq, n, signed, rate, hz)
            }
            if (error != null) {
                _state.update { it.copy(isScanning = false, isPaused = false, status = error, channel = null) }
                return@launch
            }
            if (radio() == SdrRadio.AIRSPYHF) {
                decode.stop()
                _state.update {
                    it.copy(
                        isScanning = false,
                        status = "This receiver stops near 260 MHz. LoRa mesh is at 433, 868, and 915 MHz.",
                        channel = null
                    )
                }
                return@launch
            }
            _state.update { it.copy(status = null) }
            var index = 0
            while (isActive && _state.value.isScanning) {
                delay(4_000)
                if (!_state.value.isScanning) break
                index = (index + 1) % CHANNELS.size
                decode.tune(CHANNELS[index])
                synchronized(lock) {
                    accI.clear()
                    accQ.clear()
                }
                _state.update { it.copy(channel = label(CHANNELS[index])) }
            }
        }
    }

    private fun onIq(iq: ByteArray, n: Int, signed: Boolean, sampleRate: Int, hz: Long) {
        val factor = (sampleRate / 250_000).coerceAtLeast(1)
        val wide = decimate(iq, n, signed, factor)
        val ready = synchronized(lock) {
            wide.first.forEach { accI.add(it) }
            wide.second.forEach { accQ.add(it) }
            if (accI.size < 32_768) return
            val i = accI.toFloatArray()
            val q = accQ.toFloatArray()
            accI.clear()
            accQ.clear()
            i to q
        }
        val narrow = decimate(ready.first, ready.second, 2)
        val wideHit = LoraMesh.detect(ready.first, ready.second)
        val narrowHit = LoraMesh.detect(narrow.first, narrow.second)
        val hit = listOfNotNull(wideHit, narrowHit).maxByOrNull { named(it.protocol) * 1_000 + it.strength } ?: return
        val bandwidth = if (hit === narrowHit) "125 kHz" else "250 kHz"
        val sync = hit.sync?.let { " · sync 0x%02X".format(it) }.orEmpty()
        val here = here()
        val node = MeshNode(
            id = "${hit.protocol}|$hz|$bandwidth",
            protocol = hit.protocol,
            name = hit.protocol,
            detail = "LoRa SF${hit.spreading} · $bandwidth · ${label(hz)}$sync",
            rssi = null,
            path = "LoRa",
            lat = here?.first,
            lng = here?.second
        )
        _state.update { state -> state.copy(nodes = merge(state.nodes, listOf(node))) }
    }

    private fun named(protocol: String): Int = if (protocol == "LoRa" || protocol == "LoRaWAN") 0 else 1

    private fun merge(current: List<MeshNode>, heard: List<MeshNode>): List<MeshNode> {
        val map = LinkedHashMap<String, MeshNode>()
        current.forEach { map[it.id] = it }
        heard.forEach { fresh ->
            val previous = map[fresh.id]
            map[fresh.id] = if (previous != null && fresh.lat == null) fresh.copy(lat = previous.lat, lng = previous.lng) else fresh
        }
        return map.values.sortedByDescending { it.rssi ?: -200 }
    }

    private fun here(): Pair<Double, Double>? = runCatching {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val fix = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: return null
        fix.latitude to fix.longitude
    }.getOrNull()

    private fun loadDemo() {
        stopScan()
        _state.value = MeshtasticState(
            nodes = listOf(
                MeshNode("AA:11:22:33:44:55", "Meshtastic", "Meshtastic_A3F1", "Bluetooth companion link", -61, "Bluetooth", 39.7392, -104.9903),
                MeshNode("AA:11:22:33:44:66", "Reticulum", "RNode", "RNode companion link", -78, "Bluetooth", 39.7420, -104.9870),
                MeshNode("lora", "Meshtastic", "Meshtastic", "LoRa SF11 · 250 kHz · 906.875 MHz · sync 0x2B", null, "LoRa", 39.7360, -104.9920)
            ),
            channel = "906.875 MHz"
        )
    }

    override fun onCleared() = stopScan()

    companion object {
        val CHANNELS = longArrayOf(
            906_875_000L,
            915_000_000L,
            910_525_000L,
            869_525_000L,
            868_100_000L,
            433_920_000L
        )

        fun label(hz: Long): String = "%.3f MHz".format(hz / 1_000_000.0)

        fun decimate(iq: ByteArray, n: Int, signed: Boolean, factor: Int): Pair<FloatArray, FloatArray> {
            val samples = n / 2
            val out = samples / factor
            val i = FloatArray(out)
            val q = FloatArray(out)
            var index = 0
            var at = 0
            while (index + factor * 2 <= n && at < out) {
                var sumI = 0f
                var sumQ = 0f
                for (step in 0 until factor) {
                    val o = index + step * 2
                    sumI += sample(iq[o], signed)
                    sumQ += sample(iq[o + 1], signed)
                }
                i[at] = sumI / factor
                q[at] = sumQ / factor
                at++
                index += factor * 2
            }
            return i to q
        }

        fun decimate(iIn: FloatArray, qIn: FloatArray, factor: Int): Pair<FloatArray, FloatArray> {
            val out = iIn.size / factor
            val i = FloatArray(out)
            val q = FloatArray(out)
            var at = 0
            var index = 0
            while (at < out) {
                var sumI = 0f
                var sumQ = 0f
                for (step in 0 until factor) {
                    sumI += iIn[index + step]
                    sumQ += qIn[index + step]
                }
                i[at] = sumI / factor
                q[at] = sumQ / factor
                at++
                index += factor
            }
            return i to q
        }

        private fun sample(byte: Byte, signed: Boolean): Float =
            if (signed) byte.toInt().toFloat() else (byte.toInt() and 0xFF) - 127.5f
    }
}
