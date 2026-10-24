package app.radiusrf.features.sdr.ui

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
import app.radiusrf.core.ui.zd.ZdChip
import app.radiusrf.core.ui.zd.ZdChipRow
import app.radiusrf.core.ui.zd.ZdFootnote
import app.radiusrf.core.ui.zd.ZdIcons
import app.radiusrf.core.ui.zd.ZdListCard
import app.radiusrf.core.ui.zd.ZdListRow
import app.radiusrf.core.ui.zd.ZdStatePanel
import app.radiusrf.core.ui.zd.ZdTag
import app.radiusrf.core.ui.zd.ZdToolScanBar
import app.radiusrf.features.sdr.domain.IsmBands
import app.radiusrf.features.sdr.viewmodel.IsmDecoderViewModel
import app.radiusrf.ui.theme.ZdColors

@Composable
fun IsmDecoderScreen(viewModel: IsmDecoderViewModel = hiltViewModel()) {
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
            runningNote = "${state.band.name} · ${state.bursts.size} bursts",
            idleNote = "RTL-SDR / HackRF · 315 / 433 / 868 / 915 MHz"
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ZdChipRow(contentPadding = 0.dp) {
                    IsmBands.all.forEach { band ->
                        ZdChip(band.name, selected = state.band.hz == band.hz, onClick = { viewModel.setBand(band) })
                    }
                }
            }
            state.status?.let { item { ZdFootnote(it, icon = ZdIcons.Warning) } }
            if (state.bursts.isEmpty()) {
                item {
                    ZdStatePanel(
                        kicker = if (state.isScanning) "Listening" else "Ready",
                        icon = ZdIcons.Radio,
                        iconTint = ZdColors.Accent,
                        iconBackground = ZdColors.AccentBg,
                        title = "What is bursting on ISM?",
                        body = "${state.band.hint}. Pulses are classified by width and modulation. Plug in a dongle, then start.",
                        primaryAction = if (state.isScanning) null else "Start listen" to viewModel::startScan,
                        primaryIcon = ZdIcons.Play,
                        fullScreen = false
                    )
                }
            } else {
                item {
                    ZdListCard(state.bursts) { burst ->
                        ZdListRow(
                            title = burst.family,
                            subtitle = "${burst.modulation} · ${burst.pulses} pulses · ${burst.durationUs} µs" +
                                (burst.bits?.let { " · $it" } ?: ""),
                            trailing = { ZdTag("${burst.bandHz / 1_000_000} MHz") }
                        )
                    }
                }
            }
            item { ZdFootnote("Receive only. A family name is a timing match, not a decoded identity.") }
        }
    }
}
