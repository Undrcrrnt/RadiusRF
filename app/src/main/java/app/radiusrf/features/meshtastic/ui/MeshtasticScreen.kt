package app.radiusrf.features.meshtastic.ui

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
import app.radiusrf.features.meshtastic.viewmodel.MeshtasticViewModel
import app.radiusrf.ui.theme.ZdColors

@Composable
fun MeshtasticScreen(viewModel: MeshtasticViewModel = hiltViewModel()) {
    PermissionGate(
        permissions = PermissionUtils.blePermissions(),
        rationale = "Meshtastic phones and nodes advertise over Bluetooth so you can hear them without joining the mesh."
    ) {
        MeshtasticBody(viewModel)
    }
}

@Composable
private fun MeshtasticBody(viewModel: MeshtasticViewModel) {
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
            runningNote = "BLE hear-only · ${state.nodes.size} nodes",
            idleNote = "Bluetooth advertisements only. Does not join the mesh."
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            state.status?.let { item { ZdFootnote(it, icon = ZdIcons.Warning) } }
            state.occupancy?.let { item { ZdFootnote(it) } }
            if (state.nodes.isEmpty()) {
                item {
                    ZdStatePanel(
                        kicker = if (state.isScanning) "Listening" else "Ready",
                        icon = ZdIcons.Radio,
                        iconTint = ZdColors.Accent,
                        iconBackground = ZdColors.AccentBg,
                        title = "Meshtastic near you",
                        body = "Hears the Meshtastic Bluetooth service and names. It does not join the mesh or open the SDR.",
                        primaryAction = if (state.isScanning) null else "Start listen" to viewModel::startScan,
                        primaryIcon = ZdIcons.Play,
                        fullScreen = false
                    )
                }
            } else {
                item {
                    ZdListCard(state.nodes) { node ->
                        ZdListRow(
                            title = node.name,
                            subtitle = node.address,
                            leading = { ZdSignal(node.rssi) },
                            trailing = { ZdTag(node.source) }
                        )
                    }
                }
            }
        }
    }
}
