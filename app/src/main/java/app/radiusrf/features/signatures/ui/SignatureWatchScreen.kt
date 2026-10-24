package app.radiusrf.features.signatures.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import app.radiusrf.features.signatures.domain.SignatureKind
import app.radiusrf.features.signatures.viewmodel.SignatureWatchViewModel
import app.radiusrf.ui.theme.ZdColors

@Composable
fun SignatureWatchScreen(viewModel: SignatureWatchViewModel = hiltViewModel()) {
    PermissionGate(
        permissions = (PermissionUtils.blePermissions() + PermissionUtils.wifiPermissions()).distinct(),
        rationale = "Bluetooth and Wi-Fi advertisements are matched against a receive-only signature list."
    ) {
        SignatureWatchBody(viewModel)
    }
}

@Composable
private fun SignatureWatchBody(viewModel: SignatureWatchViewModel) {
    val state by viewModel.state.collectAsState()
    var kind by rememberSaveable { mutableStateOf<String?>(null) }
    HardwareLifecycleEffect(state.isScanning, viewModel::pauseScan, viewModel::resumeScan, viewModel::stopScan)
    Column(Modifier.fillMaxSize()) {
        ZdToolScanBar(
            running = state.isScanning,
            paused = state.isPaused,
            onStart = viewModel::startScan,
            onPause = viewModel::pauseScan,
            onResume = viewModel::resumeScan,
            onStop = viewModel::stopScan,
            runningNote = "BLE + WiFi · ${state.hits.size} matches",
            idleNote = if (state.hits.isEmpty()) "Find Hub, glasses, action cams, serial modules" else "Results kept"
        )
        val shown = state.hits.filter { kind == null || it.kind.name == kind }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            state.error?.let { item { ZdFootnote(it, icon = ZdIcons.Warning) } }
            if (state.hits.isNotEmpty()) {
                item {
                    ZdChipRow(contentPadding = 0.dp) {
                        ZdChip("All ${state.hits.size}", selected = kind == null, onClick = { kind = null })
                        SignatureKind.entries.forEach { entry ->
                            val n = state.hits.count { it.kind == entry }
                            if (n > 0) ZdChip("${entry.label} $n", selected = kind == entry.name, onClick = { kind = entry.name })
                        }
                    }
                }
            }
            if (shown.isEmpty()) {
                item {
                    ZdStatePanel(
                        kicker = if (state.isScanning) "Listening" else "Ready",
                        icon = ZdIcons.Tracker,
                        iconTint = ZdColors.Accent,
                        iconBackground = ZdColors.AccentBg,
                        title = "What is advertising near you?",
                        body = "Matches Google Find Hub, camera glasses, action-cam hotspots, and common serial-module names. Receive only.",
                        primaryAction = if (state.isScanning) null else "Start scan" to viewModel::startScan,
                        primaryIcon = ZdIcons.Play,
                        fullScreen = false
                    )
                }
            } else {
                item {
                    ZdListCard(shown) { hit ->
                        ZdListRow(
                            title = hit.label,
                            subtitle = "${hit.detail} · ${hit.source}",
                            leading = { ZdSignal(hit.rssi) },
                            trailing = { ZdTag(hit.kind.label) }
                        )
                    }
                }
            }
            item { ZdFootnote("A name or UUID match is not proof of a hidden camera or a skimmer. It is a reason to look.") }
        }
    }
}
