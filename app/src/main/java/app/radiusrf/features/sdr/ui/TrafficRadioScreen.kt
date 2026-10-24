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
import app.radiusrf.features.sdr.viewmodel.TrafficMode
import app.radiusrf.features.sdr.viewmodel.TrafficRadioViewModel
import app.radiusrf.ui.theme.ZdColors
import java.util.Locale

@Composable
fun TrafficRadioScreen(viewModel: TrafficRadioViewModel = hiltViewModel()) {
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
            runningNote = if (state.mode == TrafficMode.ADSB) "1090 MHz · ${state.aircraft.size} aircraft" else "162 MHz · ${state.vessels.size} vessels",
            idleNote = "ADS-B aircraft and AIS ships, receive only"
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ZdChipRow(contentPadding = 0.dp) {
                    ZdChip("ADS-B", selected = state.mode == TrafficMode.ADSB, onClick = { viewModel.setMode(TrafficMode.ADSB) })
                    ZdChip("AIS", selected = state.mode == TrafficMode.AIS, onClick = { viewModel.setMode(TrafficMode.AIS) })
                }
            }
            state.status?.let { item { ZdFootnote(it, icon = ZdIcons.Warning) } }
            if (state.mode == TrafficMode.ADSB && state.aircraft.isEmpty()) {
                item {
                    ZdStatePanel(
                        kicker = if (state.isScanning) "Listening 1090 MHz" else "Ready",
                        icon = ZdIcons.Radio,
                        iconTint = ZdColors.Accent,
                        iconBackground = ZdColors.AccentBg,
                        title = "Aircraft around you",
                        body = "Decodes Mode S DF17 from an RTL-SDR or HackRF on 1090 MHz. ICAO, callsign, and position when both CPR frames arrive.",
                        primaryAction = if (state.isScanning) null else "Start listen" to viewModel::startScan,
                        primaryIcon = ZdIcons.Play,
                        fullScreen = false
                    )
                }
            } else if (state.mode == TrafficMode.AIS && state.vessels.isEmpty()) {
                item {
                    ZdStatePanel(
                        kicker = if (state.isScanning) "Listening 162.025 MHz" else "Ready",
                        icon = ZdIcons.Radio,
                        iconTint = ZdColors.Accent,
                        iconBackground = ZdColors.AccentBg,
                        title = "Ships around you",
                        body = "Decodes AIS A/B on 162 MHz. MMSI, position, and name when the message carries them.",
                        primaryAction = if (state.isScanning) null else "Start listen" to viewModel::startScan,
                        primaryIcon = ZdIcons.Play,
                        fullScreen = false
                    )
                }
            } else if (state.mode == TrafficMode.ADSB) {
                item {
                    ZdListCard(state.aircraft) { frame ->
                        ZdListRow(
                            title = frame.callsign ?: frame.icao,
                            subtitle = listOfNotNull(
                                frame.icao,
                                frame.altitudeFt?.let { "$it ft" },
                                if (frame.lat != null) String.format(Locale.US, "%.3f, %.3f", frame.lat, frame.lon) else null
                            ).joinToString(" · "),
                            trailing = { ZdTag("DF${frame.df}") }
                        )
                    }
                }
            } else {
                item {
                    ZdListCard(state.vessels) { ship ->
                        ZdListRow(
                            title = ship.name ?: ship.mmsi,
                            subtitle = listOfNotNull(
                                "MMSI ${ship.mmsi}",
                                ship.sog?.let { String.format(Locale.US, "%.1f kn", it) },
                                if (ship.lat != null) String.format(Locale.US, "%.3f, %.3f", ship.lat, ship.lon) else null
                            ).joinToString(" · "),
                            trailing = { ZdTag("T${ship.type}") }
                        )
                    }
                }
            }
            item { ZdFootnote("Public broadcasts only. Airspy HF+ cannot reach 1090 MHz.") }
        }
    }
}
