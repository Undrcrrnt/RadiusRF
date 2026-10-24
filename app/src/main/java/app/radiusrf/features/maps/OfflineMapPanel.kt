package app.radiusrf.features.maps

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.radiusrf.core.ui.zd.ZdButton
import app.radiusrf.core.ui.zd.ZdButtonVariant
import app.radiusrf.core.ui.zd.ZdCard
import app.radiusrf.core.ui.zd.ZdFootnote
import app.radiusrf.core.ui.zd.ZdIcons
import app.radiusrf.core.ui.zd.ZdListCard
import app.radiusrf.core.ui.zd.ZdListRow
import app.radiusrf.core.ui.zd.ZdSectionLabel
import app.radiusrf.core.ui.zd.ZdSwitchRow
import app.radiusrf.ui.theme.ZdColors
import app.radiusrf.ui.theme.ZdType

@Composable
fun OfflineMapPanel(
    viewModel: OfflineMapViewModel = hiltViewModel(),
    compact: Boolean = false
) {
    val context = LocalContext.current
    val archives by viewModel.store.archives.collectAsState()
    val active by viewModel.settings.activeMapArchive.collectAsState()
    val offlineOnly by viewModel.settings.offlineTilesOnly.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val note by viewModel.note.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else "tiles.mbtiles"
            } ?: "tiles.mbtiles"
        viewModel.importArchive(uri, name)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!compact) {
            ZdSectionLabel("Maps")
            Text(
                "Import an OpenStreetMap tile archive so Locate, Fox Hunt, and Field Map can show streets with no network. Make the file on a computer with MOBAC, QGIS, or tilemaker. Do not bulk-download from tile.openstreetmap.org.",
                style = ZdType.Caption,
                color = ZdColors.Text3
            )
        }
        ZdCard(verticalSpacing = 0.dp) {
            ZdSwitchRow(
                label = "Use imported tiles only",
                description = if (archives.isEmpty()) {
                    "No archive yet. The map stays blank until you import one."
                } else {
                    "Never fetch tiles. Missing zoom levels stay empty."
                },
                checked = offlineOnly,
                onCheckedChange = viewModel::setOfflineOnly
            )
        }
        ZdButton(
            if (busy) "Importing…" else "Import tile archive",
            onClick = { picker.launch(arrayOf("*/*")) },
            enabled = !busy,
            icon = ZdIcons.Folder,
            modifier = Modifier.fillMaxWidth()
        )
        if (archives.isNotEmpty()) {
            ZdListCard(archives) { archive ->
                ZdListRow(
                    title = archive.name,
                    titleMono = false,
                    subtitle = buildString {
                        append(archive.detail)
                        if (archive.name == active) append(" · selected")
                    },
                    onClick = { viewModel.select(archive.name) },
                    trailing = {
                        app.radiusrf.core.ui.zd.ZdIconButton(
                            ZdIcons.Trash,
                            contentDescription = "Remove ${archive.name}",
                            onClick = { viewModel.delete(archive.name) }
                        )
                    }
                )
            }
        }
        note?.let { ZdFootnote(it) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMapSheet(onDismiss: () -> Unit, viewModel: OfflineMapViewModel = hiltViewModel()) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Offline maps", style = ZdType.Title, color = ZdColors.Text, modifier = Modifier.weight(1f))
            }
            OfflineMapPanel(viewModel = viewModel, compact = true)
        }
    }
}
