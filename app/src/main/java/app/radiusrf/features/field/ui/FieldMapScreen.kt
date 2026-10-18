package app.radiusrf.features.field.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.radiusrf.core.permission.PermissionGate
import app.radiusrf.core.permission.PermissionUtils
import app.radiusrf.core.ui.zd.ZdChip
import app.radiusrf.core.ui.zd.ZdChipRow
import app.radiusrf.core.ui.zd.ZdFootnote
import app.radiusrf.core.ui.zd.ZdListCard
import app.radiusrf.core.ui.zd.ZdListRow
import app.radiusrf.features.field.domain.FieldMapState
import app.radiusrf.features.field.domain.FieldMapViewModel
import app.radiusrf.features.field.domain.MapFace
import app.radiusrf.ui.theme.ZdColors
import app.radiusrf.ui.theme.ZdType
import java.util.Locale

@Composable
fun FieldMapScreen(viewModel: FieldMapViewModel = hiltViewModel()) {
    PermissionGate(
        permissions = (PermissionUtils.locationPermissions() + PermissionUtils.blePermissions()).distinct(),
        rationale = "The map uses this phone’s GPS and Bluetooth Remote ID. It does not download map tiles."
    ) {
        FieldMapBody(viewModel)
    }
}

@Composable
private fun FieldMapBody(viewModel: FieldMapViewModel) {
    LaunchedEffect(Unit) { viewModel.start() }
    val state by viewModel.state.collectAsState()
    val marks = buildList {
        if (state.showSurvey) {
            state.survey.asReversed().forEach { row ->
                if (row.lat != 0.0 || row.lng != 0.0) {
                    add(PlotMark(row.lat, row.lng, surveyColor(row.radio)))
                }
            }
        }
        if (state.showRemote) {
            state.remoteIds.forEach { sighting ->
                val aircraftAt = if (sighting.lat != null && sighting.lon != null) {
                    add(PlotMark(sighting.lat, sighting.lon, Color(0xFFE35D4F)))
                    lastIndex
                } else {
                    null
                }
                if (sighting.operatorLat != null && sighting.operatorLon != null) {
                    add(PlotMark(sighting.operatorLat, sighting.operatorLon, Color(0xFFF0A020), joinedTo = aircraftAt))
                }
            }
        }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ZdFootnote("Pins, path, and list are drawn from GPS logs and Remote ID on this phone. There is no street basemap and no network fetch. You are the accent dot. North is up.")
        }
        state.remoteNote?.let { item { ZdFootnote(it) } }
        item {
            ZdChipRow(contentPadding = 0.dp) {
                MapFace.entries.forEach { face ->
                    ZdChip(face.name.lowercase().replaceFirstChar { it.uppercase() }, selected = state.face == face, onClick = { viewModel.setFace(face) })
                }
            }
        }
        item {
            ZdChipRow(contentPadding = 0.dp) {
                ZdChip("Survey", selected = state.showSurvey, onClick = viewModel::toggleSurvey)
                ZdChip("Remote ID", selected = state.showRemote, onClick = viewModel::toggleRemote)
                ZdChip("Bearing", selected = state.showBearing, onClick = viewModel::toggleBearing)
            }
        }
        item {
            Text(state.bearingLabel, style = ZdType.BodySmall, color = ZdColors.Text2)
        }
        if (state.face != MapFace.LIST) {
            item {
                LocalPlot(
                    lat = state.lat,
                    lon = state.lon,
                    marks = marks,
                    bearing = if (state.showBearing) state.bearing else null,
                    drawPath = state.face == MapFace.PATH
                )
            }
        }
        if (state.face == MapFace.LIST || state.face == MapFace.PATH) {
            item {
                val rows = listRows(state)
                if (rows.isEmpty()) ZdFootnote("Nothing with a position yet. A wardrive log or a Remote ID broadcast will show up here.")
                else ZdListCard(rows) { row -> ZdListRow(title = row.first, subtitle = row.second) }
            }
        }
    }
}

private fun listRows(state: FieldMapState): List<Pair<String, String>> {
    val survey = if (!state.showSurvey) emptyList() else state.survey.take(30).map { row ->
        (row.ssid ?: row.bssid) to "${row.radio} · ${fmt(row.lat)}, ${fmt(row.lng)} · ${row.rssi} dBm"
    }
    val remote = if (!state.showRemote) emptyList() else state.remoteIds.map { sighting ->
        val aircraft = if (sighting.lat != null) "aircraft ${fmt(sighting.lat)}, ${fmt(sighting.lon)}" else "no aircraft fix"
        val operator = if (sighting.operatorLat != null) "${sighting.operatorKind ?: "Operator"} ${fmt(sighting.operatorLat)}, ${fmt(sighting.operatorLon)}" else "no operator fix"
        sighting.id to "$aircraft · $operator"
    }
    return survey + remote
}

private fun surveyColor(radio: String): Color = when (radio) {
    "WIFI" -> Color(0xFF7CDB6A)
    "BLE", "BT" -> Color(0xFF6AA7FF)
    else -> Color(0xFFC9A227)
}

private fun fmt(value: Double?): String =
    if (value == null) "—" else String.format(Locale.US, "%.5f", value)
