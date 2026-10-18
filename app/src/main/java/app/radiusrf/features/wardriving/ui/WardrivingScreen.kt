package app.radiusrf.features.wardriving.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.radiusrf.core.permission.PermissionGate
import app.radiusrf.core.permission.PermissionUtils
import app.radiusrf.core.ui.zd.ExportNameDialog
import app.radiusrf.core.ui.zd.ZdButton
import app.radiusrf.core.ui.zd.ZdButtonVariant
import app.radiusrf.core.ui.zd.ZdCard
import app.radiusrf.core.ui.zd.ZdFootnote
import app.radiusrf.core.ui.zd.ZdIcons
import app.radiusrf.core.ui.zd.ZdListCard
import app.radiusrf.core.ui.zd.ZdListRow
import app.radiusrf.core.ui.zd.ZdMetric
import app.radiusrf.core.ui.zd.ZdProportionRow
import app.radiusrf.core.ui.zd.ZdScanControlBar
import app.radiusrf.core.ui.zd.ZdSectionLabel
import app.radiusrf.core.ui.zd.ZdSignal
import app.radiusrf.core.ui.zd.ZdStatePanel
import app.radiusrf.core.ui.zd.ZdTag
import app.radiusrf.core.util.FrequencyUtils
import app.radiusrf.core.util.SecurityType
import androidx.compose.runtime.LaunchedEffect
import app.radiusrf.features.wardriving.domain.ExportFormat
import app.radiusrf.features.wardriving.domain.SurveyMode
import app.radiusrf.features.wardriving.domain.SurveyRadio
import app.radiusrf.features.wardriving.domain.WardrivingRecord
import app.radiusrf.features.wardriving.viewmodel.WardrivingViewModel
import app.radiusrf.ui.theme.ZdColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun WardrivingScreen(
    mode: SurveyMode,
    viewModel: WardrivingViewModel = hiltViewModel()
) {
    LaunchedEffect(mode) { viewModel.attach(mode) }
    PermissionGate(
        permissions = PermissionUtils.wardrivingPermissions(mode.bluetooth, mode.cellular),
        rationale = "Logging a route needs GPS. Bluetooth and cellular logging ask for those radios too, and the background logger needs a notification."
    ) {
        WardrivingContent(mode, viewModel)
    }
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)

@Composable
private fun WardrivingContent(mode: SurveyMode, viewModel: WardrivingViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val stats = state.stats
    var pendingExport by remember { mutableStateOf<ExportFormat?>(null) }

    pendingExport?.let { format ->
        ExportNameDialog(
            initialName = viewModel.suggestedExportName(format),
            onDismiss = { pendingExport = null },
            onConfirm = { name ->
                pendingExport = null
                viewModel.export(format, name) { uri ->
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = format.mime
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, name)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, format.label))
                }
            }
        )
    }

    Column(Modifier.fillMaxSize()) {
        ZdScanControlBar(
            running = state.isScanning,
            paused = state.isPaused,
            onStart = viewModel::startSession,
            onPause = viewModel::pauseSession,
            onResume = viewModel::resumeSession,
            onStop = viewModel::stopSession,
            runningLabel = "Logging · ${stats?.formattedDuration ?: "00:00"}",
            runningDetail = "${mode.radiosLabel} · keeps running with the screen off",
            idleDetail = when {
                state.otherSurvey != null -> "${state.otherSurvey} is logging · start replaces it"
                state.records.isEmpty() -> "Walk, cycle or drive a route"
                else -> "Session kept · ready to export"
            },
            showBackground = false
        )

        if (state.records.isEmpty() && !state.isScanning && !state.isPaused) {
            ZdStatePanel(
                kicker = if (state.error != null) "Couldn’t start" else "Ready",
                kickerColor = if (state.error != null) ZdColors.Critical else ZdColors.Text3,
                icon = ZdIcons.Map,
                iconTint = ZdColors.Accent,
                iconBackground = ZdColors.AccentBg,
                title = "Map ${mode.radiosLabel} along a route",
                body = state.error ?: state.otherSurvey?.let {
                    "$it is already logging. Start replaces that survey. Each sighting keeps the GPS fix. Export writes WiGLE, Kismet, airodump-ng, PCAPNG, and a plain CSV."
                } ?: "Each sighting keeps the GPS fix from where it was heard. Export writes WiGLE, Kismet, airodump-ng, PCAPNG, and a plain CSV. It only listens.",
                primaryAction = "Start logging" to viewModel::startSession,
                primaryIcon = ZdIcons.Play
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (stats != null) {
                item {
                    val wifi = state.records.filter { it.isWifi }.map { it.bssid }.distinct().size
                    val bluetooth = state.records.filter { it.isBluetooth }.map { it.bssid }.distinct().size
                    val cells = state.records.filter { it.isCell }.map { it.bssid }.distinct().size
                    ZdCard {
                        Row {
                            when (mode) {
                                SurveyMode.WIFI -> {
                                    ZdMetric("$wifi", "Networks", Modifier.weight(1f))
                                    ZdMetric("${stats.openCount}", "Open", Modifier.weight(1f), valueColor = if (stats.openCount > 0) ZdColors.Critical else ZdColors.Text)
                                }
                                SurveyMode.PLUS -> {
                                    ZdMetric("$wifi", "Wi-Fi", Modifier.weight(1f))
                                    ZdMetric("$bluetooth", "Bluetooth", Modifier.weight(1f))
                                }
                                SurveyMode.PLUS_PLUS -> {
                                    ZdMetric("$wifi", "Wi-Fi", Modifier.weight(1f))
                                    ZdMetric("$bluetooth", "Bluetooth", Modifier.weight(1f))
                                    ZdMetric("$cells", "Cells", Modifier.weight(1f))
                                }
                            }
                            ZdMetric("${stats.totalRecords}", "Records", Modifier.weight(1f))
                            ZdMetric(stats.formattedDuration, "Time", Modifier.weight(1f))
                        }
                        if (mode == SurveyMode.WIFI) {
                            val total = stats.openCount + stats.securedCount
                            ZdProportionRow("SECURED", stats.securedCount, total, ZdColors.Accent)
                            ZdProportionRow("OPEN", stats.openCount, total, ZdColors.Critical)
                        }
                    }
                }
            }
            state.error?.let { item { ZdFootnote(it, icon = ZdIcons.Warning) } }
            state.exportStatus?.let { item { ZdFootnote(it, icon = ZdIcons.Check) } }
            if (!state.isScanning && state.session != null && state.records.isNotEmpty()) {
                item { ZdSectionLabel("Export") }
                items(ExportFormat.entries.size) { index ->
                    val format = ExportFormat.entries[index]
                    ZdButton(
                        format.label,
                        onClick = { pendingExport = format },
                        icon = ZdIcons.Share,
                        variant = ZdButtonVariant.Secondary,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    ZdFootnote("WiGLE, Kismet, and airodump-ng files open in Netseer. PCAPNG opens in Wireshark and Netseer. The PCAPNG is rebuilt from the log, not a raw capture.")
                }
            }
            if (state.records.isNotEmpty()) {
                item { ZdSectionLabel("Latest", trailingText = "${state.records.size}") }
                item { ZdListCard(state.records.takeLast(50).reversed()) { RecordRow(it) } }
            }
            item { ZdFootnote("Runs as a foreground service with a notification, so it keeps logging with the screen off.") }
        }
    }
}

@Composable
private fun RecordRow(record: WardrivingRecord) {
    val security = SecurityType.fromCapabilities(record.capabilities.orEmpty())
    val weak = record.isWifi && (security == SecurityType.OPEN || security == SecurityType.WEP)
    val tag = if (record.isWifi) security.label.uppercase() else record.radio
    val channel = when {
        record.radio == SurveyRadio.WIFI && record.frequency > 0 ->
            "ch ${FrequencyUtils.frequencyToChannel(record.frequency)}"
        record.isCell && record.frequency > 0 -> "arfcn ${record.frequency}"
        else -> null
    }
    ZdListRow(
        title = record.ssid?.takeIf { it.isNotBlank() } ?: if (record.isWifi) "[hidden]" else record.bssid,
        subtitle = listOfNotNull(
            channel,
            "%.4f, %.4f".format(Locale.US, record.lat, record.lng),
            timeFormat.format(Date(record.timestamp))
        ).joinToString(" · "),
        leading = { ZdSignal(record.rssi) },
        trailing = {
            ZdTag(
                tag,
                color = if (weak) ZdColors.Critical else ZdColors.Accent,
                background = if (weak) ZdColors.CriticalBg else ZdColors.AccentBg,
                border = if (weak) ZdColors.CriticalBg else ZdColors.AccentBg
            )
        }
    )
}
