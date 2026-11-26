package app.radiusrf.features.wifi.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.Manifest
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.radiusrf.core.permission.PermissionGate
import app.radiusrf.core.ui.zd.ZdButton
import app.radiusrf.core.ui.zd.ZdButtonVariant
import app.radiusrf.core.ui.zd.ZdCard
import app.radiusrf.core.ui.zd.ZdFootnote
import app.radiusrf.core.ui.zd.ZdIconTile
import app.radiusrf.core.ui.zd.ZdIcons
import app.radiusrf.core.ui.zd.ZdListCard
import app.radiusrf.core.ui.zd.ZdListRow
import app.radiusrf.core.ui.zd.ZdSectionLabel
import app.radiusrf.features.wifi.domain.FloorSample
import app.radiusrf.features.wifi.domain.FloorWalkMath
import app.radiusrf.features.wifi.domain.FloorWalkRecord
import app.radiusrf.features.wifi.viewmodel.FloorAr
import app.radiusrf.features.wifi.viewmodel.FloorWalkState
import app.radiusrf.features.wifi.viewmodel.FloorWalkViewModel
import app.radiusrf.ui.theme.ZdColors
import app.radiusrf.ui.theme.ZdType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.max
import kotlin.math.min

@Composable
fun FloorWalkEntry(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    ZdCard(
        modifier = modifier,
        contentPadding = PaddingValues(0.dp),
        verticalSpacing = 0.dp
    ) {
        ZdListRow(
            title = "Floor walk",
            subtitle = "Map the Wi-Fi this phone is joined to",
            leading = { ZdIconTile(ZdIcons.Camera, size = 36.dp, iconSize = 18.dp) },
            showChevron = true,
            onClick = onOpen
        )
    }
}

@Composable
fun FloorWalkScreen(
    onClose: () -> Unit,
    viewModel: FloorWalkViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    BackHandler {
        when {
            state.walking && state.live.isNotEmpty() -> viewModel.finish()
            state.walking -> viewModel.cancel()
            state.viewing != null -> viewModel.closeView()
            else -> onClose()
        }
    }
    when {
        state.walking -> WalkLive(state, viewModel)
        state.viewing != null -> WalkReview(
            record = state.viewing!!,
            onBack = viewModel::closeView,
            onDelete = viewModel::deleteViewing,
            onShare = {
                val send = state.viewing?.let(viewModel::shareImage)
                if (send == null) {
                    false
                } else {
                    context.startActivity(Intent.createChooser(send, "Floor walk"))
                    true
                }
            }
        )
        else -> WalkIntro(state, viewModel, onClose)
    }
}

@Composable
private fun WalkIntro(
    state: FloorWalkState,
    viewModel: FloorWalkViewModel,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Floor walk", style = ZdType.Title, color = ZdColors.Text)
        Text(
            "Walk the room with the camera pointed at the walls and floor. The path is colored by the strength of the Wi-Fi this phone is already joined to. The picture stays on the screen and is not saved.",
            style = ZdType.BodySmall,
            color = ZdColors.Text2
        )
        ZdFootnote(linkLine(state))
        ZdFootnote(arLine(state))
        state.note?.let { ZdFootnote(it) }
        when (state.ar) {
            FloorAr.Installable -> ZdButton(
                "Set up floor tracking",
                onClick = { context.findActivity()?.let(viewModel::installAr) },
                enabled = context.findActivity() != null,
                modifier = Modifier.fillMaxWidth()
            )
            FloorAr.Ready -> ZdButton(
                "Start walk",
                onClick = viewModel::startWalk,
                enabled = state.associated,
                modifier = Modifier.fillMaxWidth()
            )
            else -> ZdButton(
                "Start walk",
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth()
            )
        }
        ZdButton("Analyzer", onClick = onClose, variant = ZdButtonVariant.Ghost, modifier = Modifier.fillMaxWidth())
        ZdSectionLabel("Saved walks")
        if (state.saved.isEmpty()) {
            ZdFootnote("No saved walks yet.")
        } else {
            ZdListCard(state.saved) { record ->
                ZdListRow(
                    title = record.ssid,
                    subtitle = record.subtitle(),
                    showChevron = true,
                    onClick = { viewModel.open(record) }
                )
            }
        }
    }
}

@Composable
private fun WalkLive(state: FloorWalkState, viewModel: FloorWalkViewModel) {
    val callbacks = remember(viewModel) {
        FloorCameraCallbacks().apply {
            onPose = viewModel::onPose
            onError = viewModel::onCameraError
        }
    }
    PermissionGate(
        permissions = listOf(Manifest.permission.CAMERA),
        rationale = "The floor walk uses the camera to keep your place in the room. The picture is not saved."
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(linkLine(state), style = ZdType.Label, color = ZdColors.Text)
                state.note?.let { Text(it, style = ZdType.Caption, color = ZdColors.Medium) }
            }
            FloorCamera(viewModel.host, callbacks, Modifier.weight(1f).fillMaxWidth())
            FloorPlan(state.live, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ZdButton("Done", onClick = viewModel::finish, modifier = Modifier.weight(1f))
                ZdButton("Discard", onClick = viewModel::cancel, variant = ZdButtonVariant.Secondary, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun WalkReview(
    record: FloorWalkRecord,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Boolean
) {
    val roams = record.samples.filter { it.roam && !it.bssid.isNullOrBlank() }
    var shareFailed by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(record.ssid, style = ZdType.Title, color = ZdColors.Text)
        Text(record.subtitle(), style = ZdType.Caption, color = ZdColors.Text3)
        ZdFootnote("The path is where this phone walked. Blank space was not measured. The scale is an estimate and drifts.")
        FloorPlan(record.samples)
        if (shareFailed) ZdFootnote("Couldn’t create the image.")
        ZdButton(
            "Share image",
            onClick = { shareFailed = !onShare() },
            icon = ZdIcons.Share,
            variant = ZdButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth()
        )
        if (roams.isNotEmpty()) {
            ZdSectionLabel("Access point changes")
            ZdListCard(roams.take(12)) { sample ->
                ZdListRow(
                    title = sample.bssid.orEmpty(),
                    subtitle = "${sample.rssi} dBm"
                )
            }
        }
        ZdButton("Walks", onClick = onBack, variant = ZdButtonVariant.Secondary, modifier = Modifier.fillMaxWidth())
        ZdButton("Delete walk", onClick = onDelete, variant = ZdButtonVariant.Danger, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun FloorPlan(samples: List<FloorSample>, modifier: Modifier = Modifier) {
    val bar = FloorWalkMath.scaleBarMetres(FloorWalkMath.spanMetres(samples))
    val weak = ZdColors.Critical
    val mid = ZdColors.Medium
    val strong = ZdColors.Accent
    val surface = ZdColors.Surface2
    val ink = ZdColors.Text
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
        ) {
            drawRect(surface)
            if (samples.isEmpty()) return@Canvas
            val minX = samples.minOf { it.x }
            val maxX = samples.maxOf { it.x }
            val minZ = samples.minOf { it.z }
            val maxZ = samples.maxOf { it.z }
            val span = max(maxX - minX, maxZ - minZ).coerceAtLeast(4.0)
            val cx = (minX + maxX) / 2.0
            val cz = (minZ + maxZ) / 2.0
            val pad = 28.dp.toPx()
            val usable = min(size.width, size.height) - pad * 2f
            val scale = usable / span.toFloat()
            fun point(sample: FloorSample) = Offset(
                size.width / 2f + ((sample.x - cx) * scale).toFloat(),
                size.height / 2f - ((sample.z - cz) * scale).toFloat()
            )
            for (index in 1 until samples.size) {
                drawLine(
                    color = floorColor(samples[index].rssi, weak, mid, strong),
                    start = point(samples[index - 1]),
                    end = point(samples[index]),
                    strokeWidth = 8f,
                    cap = StrokeCap.Round
                )
            }
            samples.forEach { sample ->
                val at = point(sample)
                if (sample.roam) {
                    drawCircle(Color.White, radius = 8f, center = at, style = Stroke(width = 2f))
                }
                drawCircle(floorColor(sample.rssi, weak, mid, strong), radius = 4.5f, center = at)
            }
            val barPx = (bar * scale).toFloat().coerceAtMost(size.width - pad * 2f)
            val y = size.height - 16.dp.toPx()
            drawLine(ink, Offset(pad, y), Offset(pad + barPx, y), strokeWidth = 3f, cap = StrokeCap.Round)
        }
        Text(
            "${bar.toInt()} m, estimated",
            style = ZdType.Caption,
            color = ZdColors.Text3,
            modifier = Modifier.align(Alignment.Start)
        )
    }
}

private fun floorColor(rssi: Int, weak: Color, mid: Color, strong: Color): Color {
    val t = FloorWalkMath.strength(rssi)
    return if (t < 0.5f) lerp(weak, mid, t * 2f) else lerp(mid, strong, (t - 0.5f) * 2f)
}

private fun linkLine(state: FloorWalkState): String = when {
    !state.linkReady -> "Checking the joined network…"
    !state.associated -> "Join a Wi-Fi network first. This walk maps that connection."
    else -> buildString {
        append(state.ssid ?: "Joined network")
        state.rssi?.let { append(" · $it dBm") }
        val band = FloorWalkMath.bandName(state.frequencyMhz)
        if (band.isNotBlank()) append(" · $band")
    }
}

private fun arLine(state: FloorWalkState): String = when (val ar = state.ar) {
    FloorAr.Checking -> "Checking whether this phone can track a room…"
    FloorAr.Ready -> "Camera tracking is available. Hold the phone up and walk slowly."
    FloorAr.Installable -> "This phone can track a room after Play Services for AR is installed."
    is FloorAr.Unavailable -> ar.reason
}

private fun FloorWalkRecord.subtitle(): String {
    val band = samples.firstNotNullOfOrNull { sample ->
        FloorWalkMath.bandName(sample.frequencyMhz).takeIf { it.isNotBlank() }
    }
    return listOfNotNull(
        formatWhen(startedAt).takeIf { it.isNotBlank() },
        "${samples.size} points",
        band
    ).joinToString(" · ")
}

private fun formatWhen(timeMs: Long): String = runCatching {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(timeMs))
}.getOrDefault("")

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
