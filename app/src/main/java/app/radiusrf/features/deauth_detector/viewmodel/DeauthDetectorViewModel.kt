package app.radiusrf.features.deauth_detector.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radiusrf.core.alerts.AlertCenterRepository
import app.radiusrf.core.alerts.AlertSeverity
import app.radiusrf.core.alerts.AlertSource
import app.radiusrf.core.debug.DemoData
import app.radiusrf.core.debug.DemoDataBus
import app.radiusrf.core.debug.observeDemoRequests
import app.radiusrf.features.deauth_detector.domain.AlertLevel
import app.radiusrf.features.deauth_detector.domain.AttackType
import app.radiusrf.features.deauth_detector.domain.DeauthEvent
import app.radiusrf.features.deauth_detector.domain.DeauthState
import app.radiusrf.features.wifi.domain.UsbWifiSession
import app.radiusrf.features.wifi.domain.WifiNicPreference
import com.emptyset.detector.detect.Ieee80211
import com.emptyset.detector.radio.RadioKind
import com.emptyset.detector.radio.UsbDeviceFinder
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DeauthDetectorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alertCenterRepository: AlertCenterRepository,
    private val usb: UsbWifiSession,
    private val nic: WifiNicPreference,
    private val demoBus: DemoDataBus
) : ViewModel() {

    private val _state = MutableStateFlow(DeauthState())
    val state: StateFlow<DeauthState> = _state.asStateFlow()

    private var monitorJob: Job? = null
    private var timerJob: Job? = null
    private var holdingUsb = false
    private val frameTimes = ArrayDeque<Long>()
    private var lastFloodAt = 0L
    private var monitoringStartTime = 0L

    fun startMonitoring() {
        if (monitorJob?.isActive == true) return
        val keep = _state.value.isPaused
        if (keep) {
            _state.value = _state.value.copy(isMonitoring = true, isPaused = false)
        } else {
            monitoringStartTime = System.currentTimeMillis()
            _state.value = DeauthState(isMonitoring = true)
        }

        timerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                val elapsed = System.currentTimeMillis() - monitoringStartTime
                _state.value = _state.value.copy(monitoringDurationMs = elapsed)
            }
        }

        monitorJob = viewModelScope.launch {
            val kind = nic.source.value.kind ?: attachedKind()
            if (kind == null) {
                val attached = UsbDeviceFinder.attached(context)
                fail(
                    if (attached.any { it.kind == RadioKind.PAU0B }) {
                        "Panda PAU0B is not used. Plug in a supported USB Wi-Fi adapter."
                    } else {
                        "Plug in an RTL8821AU, RTL8812AU, RTL8814AU, MT7612U, or Panda PAU0A."
                    }
                )
                return@launch
            }
            val error = usb.open(kind)
            if (error != null) {
                fail(error)
                return@launch
            }
            holdingUsb = true
            _state.value = _state.value.copy(adapterTitle = usb.adapterTitle, isMonitoring = true, error = null)
            launch {
                usb.status.collect { note ->
                    if (note.isNotBlank()) _state.value = _state.value.copy(statusNote = note)
                }
            }
            launch {
                usb.channel.collect { channel ->
                    if (channel > 0) _state.value = _state.value.copy(listenChannel = channel)
                }
            }
            launch {
                val message = usb.failure.filterNotNull().first()
                fail(message)
            }
            usb.frames.collect { frame ->
                val parsed = Ieee80211.parse(frame.bytes, frame.channel, frame.rssi) ?: return@collect
                val now = System.currentTimeMillis()
                frameTimes.addLast(now)
                while (frameTimes.isNotEmpty() && now - frameTimes.first() > 2_000L) frameTimes.removeFirst()
                if (frameTimes.size >= 5 && now - lastFloodAt > 2_000L) {
                    lastFloodAt = now
                    record(
                        DeauthEvent(
                            type = AttackType.DEAUTH_FLOOD,
                            level = AlertLevel.HIGH,
                            title = "Repeated deauth or disassoc frames",
                            detail = "${frameTimes.size} frames in 2 seconds on channel ${parsed.channelHint ?: frame.channel ?: "?"}.",
                            affectedSsid = null,
                            affectedBssid = null
                        )
                    )
                }
                val heard = _state.value.framesHeard + 1
                _state.value = _state.value.copy(
                    framesHeard = heard,
                    listenChannel = parsed.channelHint ?: _state.value.listenChannel,
                    isUnderAttack = true
                )
                val type = if (parsed.subtype == Ieee80211.SUBTYPE_DEAUTH) {
                    AttackType.FRAME_DEAUTH
                } else {
                    AttackType.FRAME_DISASSOC
                }
                record(
                    DeauthEvent(
                        type = type,
                        level = AlertLevel.HIGH,
                        title = "${parsed.kind} heard",
                        detail = "From ${parsed.addr2} to ${parsed.addr1}, ${parsed.reasonName}" +
                            (parsed.channelHint?.let { ", channel $it" } ?: "") +
                            (parsed.rssiDbm?.let { ", $it dBm" } ?: "") + ".",
                        affectedSsid = null,
                        affectedBssid = parsed.addr3
                    )
                )
            }
        }
    }

    private fun attachedKind(): RadioKind? {
        val attached = UsbDeviceFinder.attached(context)
        return attached.firstOrNull {
            it.device != null && it.canCapture && it.kind != RadioKind.PAU0B && it.kind != RadioKind.UNKNOWN
        }?.kind
    }

    fun pauseMonitoring() {
        if (!_state.value.isMonitoring) return
        monitorJob?.cancel()
        monitorJob = null
        timerJob?.cancel()
        timerJob = null
        releaseUsb()
        _state.value = _state.value.copy(isMonitoring = false, isPaused = true)
    }

    fun resumeMonitoring() = startMonitoring()

    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        timerJob?.cancel()
        timerJob = null
        releaseUsb()
        _state.value = _state.value.copy(isMonitoring = false, isPaused = false)
    }

    fun clearEvents() {
        _state.value = _state.value.copy(
            events = emptyList(),
            framesHeard = 0,
            isUnderAttack = false
        )
    }

    private fun record(event: DeauthEvent) {
        val current = _state.value
        val events = (listOf(event) + current.events).take(40)
        _state.value = current.copy(events = events, isUnderAttack = true)
        viewModelScope.launch {
            alertCenterRepository.record(
                source = AlertSource.DEAUTH,
                severity = AlertSeverity.HIGH,
                title = event.title,
                detail = event.detail,
                timestamp = event.timestamp
            )
        }
    }

    private fun releaseUsb() {
        if (holdingUsb) {
            holdingUsb = false
            usb.close()
        }
    }

    private fun fail(message: String) {
        timerJob?.cancel()
        monitorJob?.cancel()
        releaseUsb()
        _state.value = _state.value.copy(isMonitoring = false, error = message)
    }

    override fun onCleared() {
        stopMonitoring()
    }

    init {
        observeDemoRequests(demoBus, DemoData.Routes.DEAUTH) { loadDemoData() }
    }

    private fun loadDemoData() {
        stopMonitoring()
        _state.value = _state.value.copy(
            events = DemoData.deauthEvents,
            monitoringDurationMs = 180_000L,
            adapterTitle = "TP-Link Archer T2U Plus",
            listenChannel = 6,
            framesHeard = 4,
            isUnderAttack = true,
            error = null
        )
    }
}
