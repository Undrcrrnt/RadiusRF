package app.radiusrf.features.signatures.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radiusrf.core.debug.DemoData
import app.radiusrf.core.debug.DemoDataBus
import app.radiusrf.core.debug.observeDemoRequests
import app.radiusrf.core.sessions.ItemKind
import app.radiusrf.core.sessions.SessionItem
import app.radiusrf.core.sessions.SessionRepository
import app.radiusrf.features.ble.domain.BleScanner
import app.radiusrf.features.signatures.domain.DeviceSignatures
import app.radiusrf.features.signatures.domain.SignatureHit
import app.radiusrf.features.signatures.domain.SignatureKind
import app.radiusrf.features.wifi.domain.WifiScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import android.os.SystemClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SignatureWatchState(
    val isScanning: Boolean = false,
    val isPaused: Boolean = false,
    val hits: List<SignatureHit> = emptyList(),
    val heard: Int = 0,
    val error: String? = null
)

@HiltViewModel
class SignatureWatchViewModel @Inject constructor(
    private val bleScanner: BleScanner,
    private val wifiScanner: WifiScanner,
    private val sessions: SessionRepository,
    private val demoBus: DemoDataBus
) : ViewModel() {
    private val _state = MutableStateFlow(SignatureWatchState())
    val state: StateFlow<SignatureWatchState> = _state.asStateFlow()
    private val hits = linkedMapOf<String, SignatureHit>()
    private var job: Job? = null
    private var startedAt: Long? = null
    private var lastPublishMs = 0L
    private var lastHitCount = -1

    init {
        observeDemoRequests(demoBus, DemoData.Routes.SIGNATURE_WATCH) { loadDemo() }
    }

    fun startScan() {
        if (job?.isActive == true) return
        val keep = _state.value.isPaused
        if (!keep) {
            hits.clear()
            startedAt = System.currentTimeMillis()
        }
        _state.update { it.copy(isScanning = true, isPaused = false, error = null, hits = hits.values.toList()) }
        job = viewModelScope.launch {
            launch {
                bleScanner.scan(timeoutMs = 120_000L).catch { error ->
                    _state.update { it.copy(error = error.message) }
                }.collect { devices ->
                    devices.forEach { device ->
                        DeviceSignatures.matchBle(device).forEach { hit -> hits[hit.id] = hit }
                    }
                    publish(devices.size)
                }
            }
            launch {
                wifiScanner.scan().catch { error ->
                    _state.update { it.copy(error = error.message) }
                }.collect { aps ->
                    aps.forEach { ap ->
                        DeviceSignatures.matchWifi(ap.ssid, ap.bssid, ap.rssi)?.let { hits[it.id] = it }
                    }
                    publish()
                }
            }
        }
    }

    fun pauseScan() {
        if (!_state.value.isScanning) return
        job?.cancel()
        job = null
        _state.update { it.copy(isScanning = false, isPaused = true) }
    }

    fun resumeScan() = startScan()

    fun stopScan() {
        val started = startedAt
        job?.cancel()
        job = null
        startedAt = null
        if (started != null && hits.isNotEmpty()) {
            sessions.recordInBackground(
                tool = "signature_watch",
                title = "Signature watch",
                startedAt = started,
                items = hits.values.map { hit ->
                    SessionItem(hit.address, hit.label, ItemKind.BLE, hit.rssi, hit.detail, flagged = true)
                },
                summary = "${hits.size} matching advertisements"
            )
        }
        _state.update { it.copy(isScanning = false, isPaused = false) }
    }

    private fun publish(heard: Int = _state.value.heard) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPublishMs < 500L && hits.size == lastHitCount) return
        lastPublishMs = now
        lastHitCount = hits.size
        _state.update {
            it.copy(
                hits = hits.values.sortedByDescending { hit -> hit.rssi },
                heard = maxOf(heard, it.heard)
            )
        }
    }

    private fun loadDemo() {
        stopScan()
        val demo = listOf(
            SignatureHit("demo-hub", "Find Hub", SignatureKind.FIND_HUB, "BLE", "AA:BB:CC:11:22:01", -67, "Google Find Hub / Find My Device advertisement"),
            SignatureHit("demo-glasses", "Ray-Ban Meta", SignatureKind.GLASSES, "BLE", "AA:BB:CC:11:22:02", -58, "Wearable camera / smart-glasses name"),
            SignatureHit("demo-cam", "GP2523", SignatureKind.CAMERA_SSID, "WIFI", "AA:BB:CC:11:22:03", -44, "Camera hotspot · GP2523"),
            SignatureHit("demo-serial", "HC-05", SignatureKind.SKIMMER_SERIAL, "BLE", "98:D3:31:F5:B2:7C", -41, "Common BLE/classic serial module name")
        )
        _state.value = SignatureWatchState(hits = demo, heard = 18)
    }

    override fun onCleared() {
        stopScan()
    }
}
