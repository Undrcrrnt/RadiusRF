package app.radiusrf.features.ftm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.radiusrf.core.lifecycle.HardwareLifecycleEffect
import app.radiusrf.core.permission.PermissionGate
import app.radiusrf.core.permission.PermissionUtils
import app.radiusrf.core.ui.zd.ZdFootnote
import app.radiusrf.core.ui.zd.ZdIcons
import app.radiusrf.core.ui.zd.ZdListCard
import app.radiusrf.core.ui.zd.ZdListRow
import app.radiusrf.core.ui.zd.ZdSignal
import app.radiusrf.core.ui.zd.ZdStatePanel
import app.radiusrf.core.ui.zd.ZdTag
import app.radiusrf.core.ui.zd.ZdToolScanBar
import app.radiusrf.features.ftm.viewmodel.FtmViewModel
import app.radiusrf.ui.theme.ZdColors
import java.util.Locale

@Composable
fun FtmScreen(viewModel: FtmViewModel = hiltViewModel()) {
    PermissionGate(
        permissions = (PermissionUtils.wifiPermissions() + PermissionUtils.wifiDirectPermissions()).distinct(),
        rationale = "Fine location and nearby Wi-Fi are required before the phone can range 802.11mc access points."
    ) {
        FtmBody(viewModel)
    }
}

@Composable
private fun FtmBody(viewModel: FtmViewModel) {
    val state by viewModel.state.collectAsState()
    HardwareLifecycleEffect(state.isScanning, viewModel::pauseScan, viewModel::resumeScan, viewModel::stopScan)
    Column(Modifier.fillMaxSize()) {
        ZdToolScanBar(
            running = state.isScanning,
            paused = state.isPaused,
            onStart = viewModel::startScan,
            onPause = viewModel::pauseScan,
            onResume = viewModel::resumeScan,
            onStop = viewModel::stopScan,
            runningNote = "802.11mc · ${state.accessPoints.count { it.distanceM != null }} ranged",
            idleNote = if (state.available) "Round-trip time, not RSSI guesswork" else "This phone has no Wi-Fi RTT"
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            state.error?.let { item { ZdFootnote(it, icon = ZdIcons.Warning) } }
            if (state.accessPoints.isEmpty()) {
                item {
                    ZdStatePanel(
                        kicker = if (state.isScanning) "Ranging" else "Ready",
                        icon = ZdIcons.Sweep,
                        iconTint = ZdColors.Accent,
                        iconBackground = ZdColors.AccentBg,
                        title = "How far is that access point?",
                        body = "Uses 802.11mc Fine Timing Measurement when both the phone and the AP support it. Distance is a round trip, not a signal-strength guess.",
                        primaryAction = if (state.isScanning) null else "Start ranging" to viewModel::startScan,
                        primaryIcon = ZdIcons.Play,
                        fullScreen = false
                    )
                }
            } else {
                item {
                    ZdListCard(state.accessPoints.sortedBy { it.distanceM ?: 999f }) { ap ->
                        ZdListRow(
                            title = ap.ssid,
                            subtitle = listOfNotNull(
                                ap.bssid,
                                ap.distanceM?.let { String.format(Locale.US, "%.1f m", it) },
                                ap.stdDevM?.let { "±${String.format(Locale.US, "%.1f", it)} m" }
                            ).joinToString(" · "),
                            leading = { ZdSignal(ap.rssi) },
                            trailing = {
                                ZdTag(
                                    ap.status,
                                    color = if (ap.distanceM != null) ZdColors.Accent else ZdColors.Text3,
                                    background = if (ap.distanceM != null) ZdColors.AccentBg else ZdColors.Surface2,
                                    border = if (ap.distanceM != null) ZdColors.AccentBorder else ZdColors.Border
                                )
                            }
                        )
                    }
                }
            }
            item { ZdFootnote("Most home routers do not answer FTM. Office Wi-Fi 6 APs sometimes do.") }
        }
    }
}
