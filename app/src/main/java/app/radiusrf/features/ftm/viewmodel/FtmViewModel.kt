package app.radiusrf.features.ftm.viewmodel

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radiusrf.core.debug.DemoData
import app.radiusrf.core.debug.DemoDataBus
import app.radiusrf.core.debug.observeDemoRequests
import app.radiusrf.features.ftm.domain.FtmAccessPoint
import app.radiusrf.features.ftm.domain.FtmState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.os.SystemClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FtmViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wifiManager: WifiManager,
    private val demoBus: DemoDataBus
) : ViewModel() {
    private val _state = MutableStateFlow(FtmState(available = rttAvailable()))
    val state: StateFlow<FtmState> = _state.asStateFlow()
    private var job: Job? = null
    private var lastWifiScanMs = 0L

    init {
        observeDemoRequests(demoBus, DemoData.Routes.FTM) { loadDemo() }
    }

    fun startScan() {
        if (job?.isActive == true) return
        _state.update { it.copy(isScanning = true, isPaused = false, error = null, available = rttAvailable()) }
        job = viewModelScope.launch { while (isActive) { rangeOnce(); delay(2_500) } }
    }

    fun pauseScan() {
        job?.cancel()
        job = null
        _state.update { it.copy(isScanning = false, isPaused = true) }
    }

    fun resumeScan() = startScan()

    fun stopScan() {
        job?.cancel()
        job = null
        _state.update { it.copy(isScanning = false, isPaused = false) }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun rangeOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            _state.update { it.copy(available = false, error = "802.11mc ranging needs Android 9 or later.") }
            return
        }
        val rtt = context.getSystemService(WifiRttManager::class.java)
        if (rtt == null || !rtt.isAvailable) {
            _state.update { it.copy(available = false, error = "This phone does not expose Wi-Fi RTT.") }
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastWifiScanMs > 30_000L) {
            wifiManager.startScan()
            lastWifiScanMs = now
        }
        val scans = wifiManager.scanResults.orEmpty()
        val responders = scans.filter { it.is80211mcResponder }
        val listed = if (responders.isNotEmpty()) responders else scans.take(8)
        val pending = listed.map { it.toAp() }
        _state.update { it.copy(accessPoints = pending, available = true) }
        if (responders.isEmpty()) {
            _state.update { it.copy(error = "No nearby AP advertised 802.11mc. Listed networks are RSSI-only.") }
            return
        }
        val request = RangingRequest.Builder().addAccessPoints(responders.take(RangingRequest.getMaxPeers())).build()
        rtt.startRanging(request, context.mainExecutor, object : RangingResultCallback() {
            override fun onRangingFailure(code: Int) {
                _state.update { it.copy(error = "Ranging failed ($code)") }
            }
            override fun onRangingResults(results: List<RangingResult>) {
                val byMac = results.associateBy { it.macAddress?.toString()?.uppercase() }
                _state.update { state ->
                    state.copy(
                        error = null,
                        accessPoints = state.accessPoints.map { ap ->
                            val result = byMac[ap.bssid.uppercase()] ?: return@map ap
                            if (result.status != RangingResult.STATUS_SUCCESS) {
                                ap.copy(status = "No answer")
                            } else {
                                ap.copy(
                                    distanceM = result.distanceMm / 1000f,
                                    stdDevM = result.distanceStdDevMm / 1000f,
                                    status = "FTM"
                                )
                            }
                        }
                    )
                }
            }
        })
    }

    private fun ScanResult.toAp() = FtmAccessPoint(
        ssid = SSID.ifBlank { "<hidden>" },
        bssid = BSSID.orEmpty(),
        frequency = frequency,
        rssi = level,
        supportsFtm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && is80211mcResponder,
        status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && is80211mcResponder) "Waiting" else "No FTM"
    )

    private fun rttAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val rtt = context.getSystemService(WifiRttManager::class.java)
        return rtt?.isAvailable == true
    }

    private fun loadDemo() {
        stopScan()
        _state.value = FtmState(
            available = true,
            accessPoints = listOf(
                FtmAccessPoint("Office-5G", "AA:BB:CC:01:02:03", 5180, -47, true, 4.2f, 0.4f, "FTM"),
                FtmAccessPoint("Office-5G", "AA:BB:CC:01:02:04", 5220, -61, true, 11.8f, 0.9f, "FTM"),
                FtmAccessPoint("Guest", "AA:BB:CC:99:88:77", 2462, -72, false, status = "No FTM")
            )
        )
    }

    override fun onCleared() = stopScan()
}
