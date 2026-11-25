package app.radiusrf.features.run_compare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.radiusrf.core.ui.zd.ZdButton
import app.radiusrf.core.ui.zd.ZdButtonVariant
import app.radiusrf.core.ui.zd.ZdCard
import app.radiusrf.core.ui.zd.ZdChip
import app.radiusrf.core.ui.zd.ZdFootnote
import app.radiusrf.core.ui.zd.ZdIconAction
import app.radiusrf.core.ui.zd.ZdIcons
import app.radiusrf.core.ui.zd.ZdListRow
import app.radiusrf.core.ui.zd.ZdSectionLabel
import app.radiusrf.core.ui.zd.ZdStatePanel
import app.radiusrf.core.ui.zd.ZdTag
import app.radiusrf.core.util.formatSpan
import app.radiusrf.features.run_compare.domain.CompareSighting
import app.radiusrf.features.run_compare.domain.DeviceMatch
import app.radiusrf.features.run_compare.domain.GeoFix
import app.radiusrf.features.run_compare.domain.RunCompare
import app.radiusrf.features.run_compare.domain.RunTrack
import app.radiusrf.features.run_compare.domain.SurveyRun
import app.radiusrf.features.run_compare.viewmodel.RunCompareViewModel
import app.radiusrf.features.wardriving.domain.SurveyRadio
import app.radiusrf.ui.theme.ZdColors
import app.radiusrf.ui.theme.ZdType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val deviceColors = listOf(
    Color(0xFFE24B4B),
    Color(0xFFF5A524),
    Color(0xFF3DDC97),
    Color(0xFF5B8CFF),
    Color(0xFFC084FC),
    Color(0xFF22D3EE),
    Color(0xFFFF7AD9),
    Color(0xFFB6F04A)
)

private val routeColors = listOf(
    Color(0xCC8AA4C8),
    Color(0xCCC4A574),
    Color(0xCC7DCAA5),
    Color(0xCCB7A0D6)
)

private val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.US)
private val timeFormat = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US)

@Composable
fun RunCompareScreen(viewModel: RunCompareViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val open = state.matches?.firstOrNull { it.mac == state.openMac }
    when {
        open != null -> MatchDetail(
            match = open,
            runs = state.runs,
            sightings = state.detail.takeIf { state.detailMac == open.mac }.orEmpty(),
            loading = state.detailMac != open.mac,
            onBack = viewModel::closeDetail
        )
        state.matches != null -> Results(state, viewModel)
        state.runs.isEmpty() -> ZdStatePanel(
            kicker = "No runs yet",
            title = "Log each leg in Wardriving",
            body = "Stop at the end of a leg, then start a new run for the next one. Run Compare reads those saved logs. It does not scan on its own.",
            icon = app.radiusrf.core.ui.zd.ZdIcons.Map
        )
        else -> RunPicker(state, viewModel)
    }
}

@Composable
private fun RunPicker(state: app.radiusrf.features.run_compare.viewmodel.RunCompareState, viewModel: RunCompareViewModel) {
    var pendingDelete by remember { mutableStateOf<SurveyRun?>(null) }
    pendingDelete?.let { run ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this run?", style = ZdType.Title, color = ZdColors.Text) },
            text = {
                Text(
                    "The log from ${dateFormat.format(Date(run.startedAt))} is removed. Export it from Wardriving first if you still need it.",
                    style = ZdType.BodySmall,
                    color = ZdColors.Text2
                )
            },
            confirmButton = {
                ZdButton(
                    text = "Delete",
                    onClick = {
                        pendingDelete = null
                        viewModel.delete(run.id)
                    },
                    variant = ZdButtonVariant.Danger
                )
            },
            dismissButton = {
                ZdButton(text = "Keep", onClick = { pendingDelete = null }, variant = ZdButtonVariant.Ghost)
            },
            containerColor = ZdColors.Surface,
            shape = RoundedCornerShape(22.dp)
        )
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                ZdSectionLabel("Saved runs", trailingText = "${state.selected.size} of ${RunCompare.MAX_RUNS}")
            }
            item {
                ZdFootnote("Check two to four legs. A match is a Wi-Fi or Bluetooth address heard on more than one of them, and heard again after you have moved at least 150 m. The trash button deletes a run.")
            }
            items(state.runs, key = { it.id }) { run ->
                val checked = run.id in state.selected
                ZdListRow(
                    title = dateFormat.format(Date(run.startedAt)),
                    titleMono = false,
                    subtitle = "${run.sightings} sightings · ${formatSpan(run.endedAt - run.startedAt)}",
                    onClick = { viewModel.toggle(run.id) },
                    trailing = {
                        ZdIconAction(
                            icon = ZdIcons.Trash,
                            contentDescription = "Delete run",
                            onClick = { pendingDelete = run },
                            variant = ZdButtonVariant.Danger,
                            enabled = !state.comparing,
                            size = 36.dp
                        )
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { viewModel.toggle(run.id) },
                            colors = CheckboxDefaults.colors(
                                checkedColor = ZdColors.Accent,
                                uncheckedColor = ZdColors.Text3,
                                checkmarkColor = ZdColors.OnAccent
                            )
                        )
                    }
                )
            }
        }
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            state.note?.let { ZdFootnote(it) }
            ZdButton(
                text = if (state.comparing) "Comparing…" else "Compare ${state.selected.size} runs",
                onClick = viewModel::compare,
                enabled = state.selected.size >= RunCompare.MIN_RUNS && !state.comparing,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun Results(state: app.radiusrf.features.run_compare.viewmodel.RunCompareState, viewModel: RunCompareViewModel) {
    val matches = state.matches.orEmpty()
    val shown = if (state.layerMac == null) matches else matches.filter { it.mac == state.layerMac }
    val picture = remember(state.tracks, matches, state.layerMac, state.detail, state.detailMac, state.runs, state.selected) {
        pictureOf(state.runs, state.selected, state.tracks, matches, state.layerMac, state.detailMac, state.detail)
    }
    Column(Modifier.fillMaxSize()) {
        RunCompareMap(
            lines = picture.lines,
            marks = picture.marks,
            refitKey = state.selected to matches.map { it.mac }
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                ZdChip("All", selected = state.layerMac == null, onClick = { viewModel.setLayer(null) })
            }
            items(matches, key = { it.mac }) { match ->
                ZdChip(
                    label = chipLabel(match),
                    selected = state.layerMac == match.mac,
                    onClick = { viewModel.setLayer(match.mac) }
                )
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                ZdSectionLabel(
                    if (matches.isEmpty()) "No device kept up" else "Stayed with you",
                    trailingText = if (matches.isEmpty()) null else "${shown.size}"
                )
            }
            if (matches.isEmpty()) {
                item {
                    ZdFootnote("Nothing was heard on two or more of these runs and also moved at least 150 m. A router at a stop stays in one place, so it is left out.")
                }
            }
            items(shown, key = { it.mac }) { match ->
                val color = deviceColors[matches.indexOfFirst { it.mac == match.mac }.coerceAtLeast(0) % deviceColors.size]
                ZdCard(onClick = { viewModel.open(match.mac) }, contentPadding = PaddingValues(0.dp), verticalSpacing = 0.dp) {
                    ZdListRow(
                        title = match.label,
                        titleMono = match.label == match.displayMac,
                        subtitle = "${match.runIds.size} runs · ${formatDistance(match.spanMeters)} · ${match.count} sightings",
                        showChevron = true,
                        leading = {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(color))
                        },
                        trailing = { ZdTag(radioLabel(match.radio)) }
                    )
                }
            }
            item {
                ZdButton(
                    text = "Choose runs",
                    onClick = viewModel::chooseRuns,
                    variant = ZdButtonVariant.Secondary,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun MatchDetail(
    match: DeviceMatch,
    runs: List<SurveyRun>,
    sightings: List<CompareSighting>,
    loading: Boolean,
    onBack: () -> Unit
) {
    val runNames = remember(runs) { runs.associate { it.id to dateFormat.format(Date(it.startedAt)) } }
    val rows = if (sightings.isNotEmpty()) sightings else match.sightings
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            ZdButton("Back to matches", onClick = onBack, variant = ZdButtonVariant.Ghost)
        }
        item {
            Column {
                ZdSectionLabel(match.label)
                Text(
                    "${radioLabel(match.radio)} · ${match.displayMac} · ${match.runIds.size} runs · ${formatDistance(match.spanMeters)}",
                    style = ZdType.Caption,
                    color = ZdColors.Text2,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
        if (rows.isEmpty()) {
            item { ZdFootnote(if (loading) "Loading sightings…" else "No stored sightings for this device.") }
        }
        itemsIndexed(rows, key = { index, sighting -> "$index-${sighting.sessionId}-${sighting.timestamp}" }) { _, sighting ->
            SightingCard(sighting, runNames[sighting.sessionId] ?: "Run")
        }
    }
}

@Composable
private fun SightingCard(sighting: CompareSighting, runLabel: String) {
    ZdCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(runLabel, style = ZdType.Label, color = ZdColors.Text)
            ZdTag(radioLabel(sighting.radio))
        }
        Fact("Time", timeFormat.format(Date(sighting.timestamp)))
        Fact("Name", sighting.name?.takeIf { it.isNotBlank() } ?: "—")
        Fact("Address", sighting.mac)
        Fact("Signal", "${sighting.rssi} dBm")
        Fact("Frequency", if (sighting.frequency > 0) "${sighting.frequency} MHz" else "—")
        Fact(
            if (sighting.radio == SurveyRadio.CLIENT) "Access point" else "Details",
            sighting.capabilities?.takeIf { it.isNotBlank() } ?: "—"
        )
        Fact("Latitude", String.format(Locale.US, "%.6f", sighting.lat))
        Fact("Longitude", String.format(Locale.US, "%.6f", sighting.lng))
        Fact("Altitude", String.format(Locale.US, "%.0f m", sighting.altitude))
        Fact("Accuracy", String.format(Locale.US, "%.0f m", sighting.accuracy))
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Column {
        Text(label, style = ZdType.Caption, color = ZdColors.Text3)
        Text(value, style = ZdType.BodySmall, color = ZdColors.Text)
    }
}

private data class MapPicture(val lines: List<MapLine>, val marks: List<MapMark>)

private fun pictureOf(
    runs: List<SurveyRun>,
    selected: Set<String>,
    tracks: List<RunTrack>,
    matches: List<DeviceMatch>,
    layerMac: String?,
    detailMac: String?,
    detail: List<CompareSighting>
): MapPicture {
    val byId = tracks.associate { it.sessionId to it.points }
    val order = runs.filter { it.id in selected }
    val lines = ArrayList<MapLine>()
    val marks = ArrayList<MapMark>()
    order.forEachIndexed { index, run ->
        val track = byId[run.id].orEmpty()
        val color = routeColors[index % routeColors.size]
        if (track.size >= 2) {
            lines += MapLine(track, color, width = 3f, dashed = true)
        } else if (track.size == 1) {
            marks += MapMark(track.first(), color)
        }
    }
    val shown = if (layerMac == null) matches else matches.filter { it.mac == layerMac }
    shown.forEach { match ->
        val color = deviceColors[matches.indexOfFirst { it.mac == match.mac }.coerceAtLeast(0) % deviceColors.size]
        val path = devicePath(match, layerMac, detailMac, detail)
        if (layerMac != null && path.size >= 2) {
            lines += MapLine(path, color, width = 4f)
        }
        path.forEach { fix -> marks += MapMark(fix, color) }
    }
    return MapPicture(lines, marks)
}

private fun devicePath(
    match: DeviceMatch,
    layerMac: String?,
    detailMac: String?,
    detail: List<CompareSighting>
): List<GeoFix> {
    if (layerMac == match.mac && detailMac == match.mac && detail.isNotEmpty()) {
        return RunCompare.track(detail)
    }
    if (match.sightings.isNotEmpty()) return RunCompare.track(match.sightings)
    return match.path
}

private fun chipLabel(match: DeviceMatch): String {
    val text = match.label
    return if (text.contains(':')) text.takeLast(8) else text.take(18)
}

private fun radioLabel(radio: String): String = when (radio) {
    SurveyRadio.WIFI -> "WI-FI"
    SurveyRadio.BLE -> "BLE"
    SurveyRadio.BT -> "BT"
    SurveyRadio.CLIENT -> "CLIENT"
    else -> radio
}

private fun formatDistance(meters: Double): String =
    if (meters >= 1000) String.format(Locale.US, "%.1f km", meters / 1000.0) else "${meters.toInt()} m"
