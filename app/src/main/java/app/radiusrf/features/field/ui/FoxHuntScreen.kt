package app.radiusrf.features.field.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.radiusrf.core.lifecycle.HardwareLifecycleEffect
import app.radiusrf.core.permission.PermissionGate
import app.radiusrf.core.permission.PermissionUtils
import app.radiusrf.core.ui.zd.ZdButton
import app.radiusrf.core.ui.zd.ZdButtonVariant
import app.radiusrf.core.ui.zd.ZdCard
import app.radiusrf.core.ui.zd.ZdChip
import app.radiusrf.core.ui.zd.ZdChipRow
import app.radiusrf.core.ui.zd.ZdFootnote
import app.radiusrf.core.ui.zd.ZdIconAction
import app.radiusrf.core.ui.zd.ZdIcons
import app.radiusrf.core.ui.zd.ZdSectionLabel
import app.radiusrf.features.field.domain.FoxFace
import app.radiusrf.features.field.domain.FoxHuntState
import app.radiusrf.features.field.domain.FoxHuntViewModel
import app.radiusrf.features.locate.ui.SignalMap
import app.radiusrf.ui.theme.ZdColors
import app.radiusrf.ui.theme.ZdType
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun FoxHuntScreen(viewModel: FoxHuntViewModel = hiltViewModel()) {
    PermissionGate(
        permissions = PermissionUtils.locationPermissions(),
        rationale = "The fox hunt uses the compass. Location turns the bearing into a line on the map."
    ) {
        FoxHuntBody(viewModel)
    }
}

@Composable
private fun FoxHuntBody(viewModel: FoxHuntViewModel) {
    LaunchedEffect(Unit) { viewModel.start() }
    HardwareLifecycleEffect(
        isActive = true,
        onPause = viewModel::pauseHardware,
        onResume = viewModel::resumeHardware,
        onStop = viewModel::pauseHardware
    )
    val state by viewModel.state.collectAsState()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ZdFootnote("Point a directional antenna and sweep slowly. The meter holds the strongest reading as a pale line. The heading at that peak is a bearing, not the transmitter’s location.")
        }
        item { RadioControls(state, viewModel) }
        if (state.target != null) {
            item {
                Text(state.target ?: "", style = ZdType.Heading, color = ZdColors.Accent)
                Text("${state.cue} · ${state.levelLabel}", style = ZdType.BodySmall, color = ZdColors.Text2)
                state.bearing?.let {
                    Text("Bearing ${it.roundToInt()}°", style = ZdType.Body, color = ZdColors.Text, modifier = Modifier.padding(top = 4.dp))
                }
            }
            item {
                val faces = if (state.sdrHunt) FoxFace.entries else FoxFace.entries.filter { it != FoxFace.METER }
                ZdChipRow(contentPadding = 0.dp) {
                    faces.forEach { face ->
                        ZdChip(face.label(), selected = state.face == face, onClick = { viewModel.setFace(face) })
                    }
                }
            }
            item {
                when (state.face) {
                    FoxFace.COMPASS -> CompassRose(state.heading, state.bearing)
                    FoxFace.STRENGTH -> StrengthTrace(state.samples)
                    FoxFace.MAP -> SignalMap(
                        samples = state.geoSamples,
                        currentLatitude = state.lat,
                        currentLongitude = state.lon,
                        showHeat = false
                    )
                    FoxFace.HEATMAP -> SignalMap(
                        samples = state.geoSamples,
                        currentLatitude = state.lat,
                        currentLongitude = state.lon,
                        showHeat = true
                    )
                    FoxFace.METER -> FoxHuntMeter(state, viewModel)
                }
                if (state.face == FoxFace.MAP || state.face == FoxFace.HEATMAP) {
                    Text(
                        if (state.face == FoxFace.HEATMAP) {
                            "The cloud shows where this phone heard the tuned signal. Red is strongest; the white ring is the loudest reading."
                        } else {
                            "The line shows this phone’s hunt path. It does not place the transmitter."
                        },
                        style = ZdType.Caption,
                        color = ZdColors.Text3
                    )
                }
            }
            item {
                ZdButton("Reset bearing", onClick = viewModel::resetBearing, variant = ZdButtonVariant.Secondary, modifier = Modifier.fillMaxWidth())
                ZdButton("Pick another signal", onClick = viewModel::stopHunt, variant = ZdButtonVariant.Secondary, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
    }
}

private fun FoxFace.label(): String = when (this) {
    FoxFace.METER -> "Meter"
    FoxFace.COMPASS -> "Compass"
    FoxFace.STRENGTH -> "Strength"
    FoxFace.MAP -> "Map"
    FoxFace.HEATMAP -> "Heatmap"
}

@Composable
private fun RadioControls(state: FoxHuntState, viewModel: FoxHuntViewModel) {
    val listening = state.sdrListening
    val paused = state.sdrPaused
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ZdSectionLabel("Receiver")
        ZdCard {
            Text(
                when {
                    paused -> "Paused"
                    listening -> "Listening"
                    else -> "Plug in an RTL-SDR or HackRF, then start it here"
                },
                style = ZdType.BodySmall,
                color = ZdColors.Text2
            )
            val canStart = paused || !listening || state.target == null
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ZdIconAction(
                    icon = ZdIcons.Play,
                    contentDescription = if (paused) "Resume" else "Start",
                    onClick = { if (listening && !paused) viewModel.huntSdr() else viewModel.startRadio() },
                    enabled = canStart,
                    variant = if (canStart) ZdButtonVariant.Primary else ZdButtonVariant.Secondary
                )
                ZdIconAction(
                    icon = ZdIcons.Pause,
                    contentDescription = "Pause",
                    onClick = viewModel::pauseRadio,
                    enabled = listening && !paused,
                    variant = ZdButtonVariant.Secondary
                )
                ZdIconAction(
                    icon = ZdIcons.Stop,
                    contentDescription = "Stop",
                    onClick = viewModel::stopRadio,
                    enabled = listening,
                    variant = if (listening) ZdButtonVariant.Danger else ZdButtonVariant.Secondary
                )
            }
        }
        state.radioStatus?.let { ZdFootnote(it) }
    }
}

@Composable
private fun CompassRose(heading: Float, bearing: Float?) {
    val accent = ZdColors.Accent
    Canvas(Modifier.fillMaxWidth().height(280.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension * 0.38f
        drawCircle(ZdColors.Border, radius = r, center = c)
        drawCircle(Color(0xFF10140F), radius = r - 2f, center = c)
        val north = Math.toRadians(-heading.toDouble())
        drawLine(accent, c, Offset(c.x + (sin(north) * r).toFloat(), c.y - (cos(north) * r).toFloat()), strokeWidth = 4f)
        bearing?.let {
            val rad = Math.toRadians((it - heading).toDouble())
            drawLine(Color.White, c, Offset(c.x + (sin(rad) * r).toFloat(), c.y - (cos(rad) * r).toFloat()), strokeWidth = 3f)
        }
        drawCircle(Color.White, radius = 5f, center = c)
    }
}

@Composable
private fun StrengthTrace(samples: List<Int>) {
    val color = ZdColors.Accent
    Canvas(Modifier.fillMaxWidth().height(88.dp)) {
        drawRect(ZdColors.Border.copy(alpha = 0.35f))
        if (samples.isEmpty()) return@Canvas
        val slots = 48
        val shown = samples.takeLast(slots)
        val min = shown.min().toFloat()
        val span = (shown.max() - min).coerceAtLeast(1f)
        val step = size.width / slots
        val origin = slots - shown.size
        shown.forEachIndexed { index, value ->
            val height = ((value - min) / span) * size.height
            val x = (origin + index) * step
            drawLine(color, Offset(x, size.height), Offset(x, size.height - height), strokeWidth = step * 0.6f)
        }
    }
}
