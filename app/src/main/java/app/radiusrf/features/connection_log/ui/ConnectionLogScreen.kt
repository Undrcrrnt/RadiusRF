package app.radiusrf.features.connection_log.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.radiusrf.core.ui.zd.ZdButton
import app.radiusrf.core.ui.zd.ZdButtonVariant
import app.radiusrf.core.ui.zd.ZdFootnote
import app.radiusrf.core.ui.zd.ZdIcons
import app.radiusrf.core.ui.zd.ZdListRow
import app.radiusrf.core.ui.zd.ZdSectionLabel
import app.radiusrf.core.ui.zd.ZdStatePanel
import app.radiusrf.features.connection_log.domain.ConnectionRow
import app.radiusrf.features.connection_log.viewmodel.ConnectionLogViewModel
import java.util.Locale

@Composable
fun ConnectionLogScreen(viewModel: ConnectionLogViewModel = hiltViewModel()) {
    val rows by viewModel.rows.collectAsState()
    val running by viewModel.running.collectAsState()
    val status by viewModel.status.collectAsState()
    val context = LocalContext.current
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.start()
    }

    Column(Modifier.fillMaxSize()) {
        if (!running && rows.isEmpty()) {
            ZdStatePanel(
                kicker = "This phone",
                title = "Connection log",
                body = status,
                icon = ZdIcons.Lan,
                primaryAction = "Start" to {
                    val intent = viewModel.consentIntent()
                    if (intent == null) viewModel.start() else consent.launch(intent)
                },
                primaryIcon = ZdIcons.Play,
                note = "A local VPN on this phone forwards IPv4 so the list and the PCAP can be written here. IPv6 stays on the phone's normal connection."
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ZdSectionLabel("Connections", trailingText = "${rows.size}")
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (running) {
                        ZdButton("Stop", onClick = viewModel::stop, variant = ZdButtonVariant.Secondary, modifier = Modifier.fillMaxWidth())
                    } else {
                        ZdButton(
                            "Start",
                            onClick = {
                                val intent = viewModel.consentIntent()
                                if (intent == null) viewModel.start() else consent.launch(intent)
                            },
                            icon = ZdIcons.Play,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    ZdButton(
                        "Share PCAP",
                        onClick = {
                            viewModel.share()?.let { share ->
                                context.startActivity(android.content.Intent.createChooser(share, "Connection log"))
                            }
                        },
                        variant = ZdButtonVariant.Secondary,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            items(rows, key = { it.id }) { row -> ConnectionLine(row) }
            item {
                ZdFootnote(status + " Apps keep their own encryption. Traffic is forwarded, and the PCAP stays on this phone.")
            }
        }
    }
}

@Composable
private fun ConnectionLine(row: ConnectionRow) {
    ZdListRow(
        title = row.host,
        titleMono = false,
        subtitle = "${row.app} · ${row.protocol} ${row.port} · up ${bytes(row.sent)} · down ${bytes(row.received)}"
    )
}

private fun bytes(value: Long): String = when {
    value < 1024 -> "$value B"
    value < 1024 * 1024 -> "${value / 1024} KB"
    else -> String.format(Locale.US, "%.1f MB", value / 1_048_576.0)
}
