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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.radiusrf.core.lifecycle.HardwareLifecycleEffect
import app.radiusrf.core.permission.PermissionGate
import app.radiusrf.core.permission.PermissionUtils
import app.radiusrf.core.ui.zd.ZdChip
import app.radiusrf.core.ui.zd.ZdChipRow
import app.radiusrf.core.ui.zd.ZdFootnote
import app.radiusrf.core.ui.zd.ZdIcons
import app.radiusrf.core.ui.zd.ZdListCard
import app.radiusrf.core.ui.zd.ZdListRow
import app.radiusrf.core.ui.zd.ZdSignal
import app.radiusrf.core.ui.zd.ZdStatePanel
import app.radiusrf.core.ui.zd.ZdTag
import app.radiusrf.core.ui.zd.ZdToolScanBar
import app.radiusrf.features.meshtastic.domain.MeshSurveyMode
import app.radiusrf.features.meshtastic.viewmodel.MeshNode
import app.radiusrf.features.meshtastic.viewmodel.MeshtasticViewModel
import app.radiusrf.features.run_compare.domain.GeoFix
import app.radiusrf.features.run_compare.ui.MapMark
import app.radiusrf.features.run_compare.ui.RunCompareMap
import app.radiusrf.ui.theme.ZdColors

@Composable
fun MeshtasticScreen(viewModel: MeshtasticViewModel = hiltViewModel()) {
    PermissionGate(
        permissions = (PermissionUtils.blePermissions() + PermissionUtils.locationPermissions()).distinct(),
        rationale = "Bluetooth hears the link a mesh node offers a phone. Location marks where this phone heard it."
    ) {
        MeshtasticBody(viewModel)
    }
}

@Composable
private fun MeshtasticBody(viewModel: MeshtasticViewModel) {
    val state by viewModel.state.collectAsState()
    HardwareLifecycleEffect(state.isScanning, viewModel::pauseScan, viewModel::resumeScan, viewModel::stopScan)
    val bluetooth = state.survey == MeshSurveyMode.BLUETOOTH
    Column(Modifier.fillMaxSize()) {
        ZdToolScanBar(
            running = state.isScanning,
            paused = state.isPaused,
            onStart = viewModel::startScan,
            onPause = viewModel::pauseScan,
            onResume = viewModel::resumeScan,
            onStop = viewModel::stopScan,
            runningNote = if (bluetooth) {
                "Bluetooth · ${state.nodes.size}"
            } else {
                "LoRa · ${state.channel ?: "hopping"} · ${state.nodes.size}"
            },
            idleNote = if (bluetooth) "Companion links. Does not join the mesh." else "LoRa on a plugged-in receiver. Does not transmit."
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ZdChipRow {
                    ZdChip("Bluetooth", bluetooth, onClick = { viewModel.setSurvey(MeshSurveyMode.BLUETOOTH) })
                    ZdChip("LoRa", !bluetooth, onClick = { viewModel.setSurvey(MeshSurveyMode.LORA) })
                }
            }
            state.status?.let { item { ZdFootnote(it, icon = ZdIcons.Warning) } }
            val marks = state.nodes.mapNotNull { node ->
                val lat = node.lat ?: return@mapNotNull null
                val lng = node.lng ?: return@mapNotNull null
                MapMark(GeoFix(lat, lng), meshColor(node.protocol))
            }
            if (marks.isNotEmpty()) {
                item {
                    RunCompareMap(
                        lines = emptyList(),
                        marks = marks,
                        refitKey = marks.size
                    )
                }
            }
            if (state.nodes.isEmpty()) {
                item {
                    ZdStatePanel(
                        kicker = if (state.isScanning) "Listening" else "Ready",
                        icon = ZdIcons.Radio,
                        iconTint = ZdColors.Accent,
                        iconBackground = ZdColors.AccentBg,
                        title = "Mesh nodes near you",
                        body = if (bluetooth) {
                            "Hears the Bluetooth link Meshtastic, Reticulum, RNode, and MeshCore nodes advertise for a phone or computer."
                        } else {
                            "Hops 433, 868, and 915 MHz on an RTL-SDR, HackRF, Airspy, or HydraSDR and names a LoRa preamble when the sync word is known."
                        },
                        primaryAction = if (state.isScanning) null else "Start survey" to viewModel::startScan,
                        primaryIcon = ZdIcons.Play,
                        fullScreen = false
                    )
                }
            } else {
                item {
                    ZdListCard(state.nodes) { node -> MeshRow(node) }
                }
            }
            item {
                ZdFootnote("A map pin is where this phone was when it heard the node. Bluetooth does not decode the LoRa packet, and LoRa does not read a position from inside it.")
            }
        }
    }
}

@Composable
private fun MeshRow(node: MeshNode) {
    ZdListRow(
        title = node.name,
        subtitle = "${node.protocol} · ${node.detail}",
        leading = node.rssi?.let { rssi -> { ZdSignal(rssi) } },
        trailing = { ZdTag(node.path) }
    )
}

private fun meshColor(protocol: String): Color = when {
    protocol.startsWith("Meshtastic") -> ZdColors.Accent
    protocol.startsWith("Reticulum") || protocol.startsWith("RNode") -> ZdColors.Medium
    protocol.startsWith("MeshCore") -> ZdColors.High
    else -> ZdColors.Info
}
