package app.radiusrf.features.field.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.radiusrf.core.permission.PermissionGate
import app.radiusrf.core.permission.PermissionUtils
import app.radiusrf.core.ui.zd.ZdButton
import app.radiusrf.core.ui.zd.ZdButtonVariant
import app.radiusrf.core.ui.zd.ZdChip
import app.radiusrf.core.ui.zd.ZdChipRow
import app.radiusrf.core.ui.zd.ZdFootnote
import app.radiusrf.core.ui.zd.ZdListCard
import app.radiusrf.core.ui.zd.ZdListRow
import app.radiusrf.core.ui.zd.ZdSectionLabel
import app.radiusrf.features.field.domain.FoxFace
import app.radiusrf.features.field.domain.FoxHuntState
import app.radiusrf.features.field.domain.FoxHuntViewModel
import app.radiusrf.features.wifi.domain.WifiAssessment
import app.radiusrf.ui.theme.ZdColors
import app.radiusrf.ui.theme.ZdType
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun FoxHuntScreen(viewModel: FoxHuntViewModel = hiltViewModel()) {
    PermissionGate(
        permissions = (PermissionUtils.locationPermissions() + PermissionUtils.wifiPermissions() + PermissionUtils.blePermissions()).distinct(),
        rationale = "The fox hunt reads Wi-Fi, Bluetooth, and the compass. Location turns the bearing into a line on the map."
    ) {
        FoxHuntBody(viewModel)
    }
}

@Composable
private fun FoxHuntBody(viewModel: FoxHuntViewModel) {
    LaunchedEffect(Unit) { viewModel.start() }
    val state by viewModel.state.collectAsState()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ZdFootnote("Turn with a directional antenna or the phone. The line is the heading where this signal was strongest. It is a bearing, not the transmitter’s location.")
        }
        if (state.target == null) {
            item { ZdSectionLabel("SDR") }
            item {
                ZdButton(
                    if (state.sdrListening) "Signal meter" else "Start SDR listen, then open the signal meter",
                    onClick = viewModel::huntSdr,
                    enabled = state.sdrListening,
                    modifier = Modifier.fillMaxWidth()
                )
                ZdFootnote("The meter tunes the SDR that is already listening. Wi-Fi and Bluetooth hunts stay on the compass, strength, and map.")
            }
            item { ZdSectionLabel("Wi-Fi", trailingText = "${state.wifi.size}") }
            item {
                if (state.wifi.isEmpty()) ZdFootnote("No Wi-Fi networks yet.")
                else ZdListCard(state.wifi.take(12)) { ap ->
                    ZdListRow(
                        title = WifiAssessment.displayName(ap),
                        subtitle = "Access point · ${ap.rssi} dBm",
                        onClick = { viewModel.huntWifi(ap) }
                    )
                }
            }
            item { ZdSectionLabel("Bluetooth", trailingText = "${state.ble.size}") }
            item {
                if (state.ble.isEmpty()) ZdFootnote("No Bluetooth devices yet.")
                else ZdListCard(state.ble.take(12)) { device ->
                    ZdListRow(
                        title = device.displayName,
                        subtitle = "${device.rssi} dBm",
                        onClick = { viewModel.huntBle(device) }
                    )
                }
            }
        } else {
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
                    FoxFace.MAP -> LocalPlot(state.lat, state.lon, emptyList(), state.bearing, drawPath = false)
                    FoxFace.METER -> SignalMeter(state, viewModel)
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
}

@Composable
private fun SignalMeter(state: FoxHuntState, viewModel: FoxHuntViewModel) {
    var mhz by remember(state.frequencyHz) {
        mutableStateOf(String.format(Locale.US, "%.4f", state.frequencyHz / 1_000_000.0))
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = mhz,
            onValueChange = { mhz = it },
            label = { Text("MHz") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ZdButton("−1 MHz", onClick = { viewModel.stepSdr(-1_000_000L) }, variant = ZdButtonVariant.Secondary, height = 36.dp)
            ZdButton("−100 kHz", onClick = { viewModel.stepSdr(-100_000L) }, variant = ZdButtonVariant.Secondary, height = 36.dp)
            ZdButton(
                "Tune",
                onClick = { mhz.toDoubleOrNull()?.let { viewModel.tuneSdr((it * 1_000_000.0).toLong()) } },
                height = 36.dp
            )
            ZdButton("+100 kHz", onClick = { viewModel.stepSdr(100_000L) }, variant = ZdButtonVariant.Secondary, height = 36.dp)
            ZdButton("+1 MHz", onClick = { viewModel.stepSdr(1_000_000L) }, variant = ZdButtonVariant.Secondary, height = 36.dp)
        }
        Box(Modifier.fillMaxWidth().height(340.dp), contentAlignment = Alignment.Center) {
            LevelBar(state.levelDb, state.peakDb, Modifier.width(72.dp).height(340.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("CURRENT", style = ZdType.Path, color = ZdColors.Text3)
                Text("${state.levelDb.roundToInt()} dB", style = ZdType.Heading, color = ZdColors.Accent, textAlign = TextAlign.Center)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("PEAK", style = ZdType.Path, color = ZdColors.Text3)
                Text("${state.peakDb.roundToInt()} dB", style = ZdType.Heading, color = ZdColors.Text, textAlign = TextAlign.Center)
            }
        }
        ZdButton("Reset peak", onClick = viewModel::resetPeak, variant = ZdButtonVariant.Secondary, modifier = Modifier.fillMaxWidth())
        ZdFootnote(
            if (state.sdrListening) "Level at the tuned frequency. Relative dB from the receiver, not a calibrated dBm."
            else "SDR listening has stopped. Open SDR Radio and tap Listen, then come back."
        )
    }
}

@Composable
private fun LevelBar(level: Float, peak: Float, modifier: Modifier) {
    val accent = ZdColors.Accent
    Canvas(modifier) {
        val floor = -120f
        val ceil = 20f
        fun y(db: Float): Float {
            val fraction = ((db - floor) / (ceil - floor)).coerceIn(0f, 1f)
            return size.height * (1f - fraction)
        }
        drawRect(Color(0xFF10140F))
        drawRect(ZdColors.Border, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
        val top = y(level)
        drawRect(accent, topLeft = Offset(6f, top), size = Size(size.width - 12f, (size.height - top).coerceAtLeast(0f)))
        val mark = y(peak)
        drawLine(Color.White, Offset(0f, mark), Offset(size.width, mark), strokeWidth = 4f)
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
