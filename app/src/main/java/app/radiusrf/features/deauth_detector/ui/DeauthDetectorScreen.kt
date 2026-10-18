package app.radiusrf.features.deauth_detector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.radiusrf.core.lifecycle.HardwareLifecycleEffect
import app.radiusrf.core.ui.zd.ZdButton
import app.radiusrf.core.ui.zd.ZdButtonVariant
import app.radiusrf.core.ui.zd.ZdCard
import app.radiusrf.core.ui.zd.ZdCheckRow
import app.radiusrf.core.ui.zd.ZdCheckStatus
import app.radiusrf.core.ui.zd.ZdFootnote
import app.radiusrf.core.ui.zd.ZdIcons
import app.radiusrf.core.ui.zd.ZdListCard
import app.radiusrf.core.ui.zd.ZdSectionLabel
import app.radiusrf.core.ui.zd.ZdSeverity
import app.radiusrf.core.ui.zd.ZdSeverityBadge
import app.radiusrf.core.ui.zd.ZdStatePanel
import app.radiusrf.core.ui.zd.ZdToolScanBar
import app.radiusrf.features.deauth_detector.domain.AlertLevel
import app.radiusrf.features.deauth_detector.domain.AttackType
import app.radiusrf.features.deauth_detector.domain.DeauthEvent
import app.radiusrf.features.deauth_detector.viewmodel.DeauthDetectorViewModel
import app.radiusrf.ui.theme.ZdColors
import app.radiusrf.ui.theme.ZdType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DeauthDetectorScreen(
    viewModel: DeauthDetectorViewModel = hiltViewModel()
) {
    DeauthDetectorContent(viewModel = viewModel)
}

private fun AlertLevel.severity(): ZdSeverity = when (this) {
    AlertLevel.CRITICAL -> ZdSeverity.CRITICAL
    AlertLevel.HIGH -> ZdSeverity.HIGH
    AlertLevel.MEDIUM -> ZdSeverity.MEDIUM
    AlertLevel.LOW -> ZdSeverity.LOW
}

private val patterns = listOf(
    AttackType.FRAME_DEAUTH to ("Deauth frame" to "A deauthentication frame was heard"),
    AttackType.FRAME_DISASSOC to ("Disassoc frame" to "A disassociation frame was heard"),
    AttackType.DEAUTH_FLOOD to ("Repeated frames" to "5 or more in 2 seconds")
)

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

@Composable
private fun DeauthDetectorContent(viewModel: DeauthDetectorViewModel) {
    val state by viewModel.state.collectAsState()

    HardwareLifecycleEffect(
        isActive = state.isMonitoring,
        onPause = viewModel::pauseMonitoring,
        onResume = viewModel::resumeMonitoring,
        onStop = viewModel::stopMonitoring
    )

    Column(Modifier.fillMaxSize()) {
        ZdToolScanBar(
            running = state.isMonitoring,
            paused = state.isPaused,
            onStart = viewModel::startMonitoring,
            onPause = viewModel::pauseMonitoring,
            onResume = viewModel::resumeMonitoring,
            onStop = viewModel::stopMonitoring,
            verb = "Monitoring",
            runningNote = state.adapterTitle ?: "USB adapter",
            idleNote = if (state.events.isEmpty()) "USB adapter, receive only" else "Results kept"
        )

        if (!state.isMonitoring && state.events.isEmpty()) {
            ZdStatePanel(
                kicker = if (state.error != null) "Couldn’t start" else "Ready",
                kickerColor = if (state.error != null) ZdColors.Critical else ZdColors.Text3,
                icon = ZdIcons.Warning,
                iconTint = ZdColors.Accent,
                iconBackground = ZdColors.AccentBg,
                title = "Are deauth frames on the air?",
                body = state.error ?: "Plug in an RTL8821AU, RTL8812AU, RTL8814AU, MT7612U, or Panda PAU0A. This listens for deauthentication and disassociation frames. It does not transmit.",
                primaryAction = "Start monitoring" to viewModel::startMonitoring,
                primaryIcon = ZdIcons.Play
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ZdCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(state.adapterTitle ?: "No adapter yet", style = ZdType.Heading, color = ZdColors.Text)
                            Text(
                                "Channel ${state.listenChannel} · ${state.framesHeard} frames",
                                style = ZdType.Caption,
                                color = ZdColors.Text3
                            )
                        }
                        if (state.isUnderAttack) ZdSeverityBadge(ZdSeverity.HIGH, label = "FRAMES")
                    }
                    state.statusNote?.let { Text(it, style = ZdType.Caption, color = ZdColors.Text3) }
                }
            }

            if (state.events.isNotEmpty()) {
                item { ZdSectionLabel("Events", trailingText = "${state.events.size}") }
                items(state.events, key = { it.id }) { EventCard(it) }
            }

            item { ZdSectionLabel("Patterns watched") }
            item {
                ZdListCard(patterns) { (type, text) ->
                    val hits = state.events.count { it.type == type }
                    val worst = state.events.filter { it.type == type }.minOfOrNull { it.level.ordinal }
                    ZdCheckRow(
                        title = text.first,
                        detail = text.second,
                        status = when {
                            hits == 0 -> ZdCheckStatus.PASS
                            worst != null && worst <= AlertLevel.HIGH.ordinal -> ZdCheckStatus.FAIL
                            else -> ZdCheckStatus.WARN
                        }
                    )
                }
            }

            if (state.events.isNotEmpty()) {
                item { ZdButton("Clear events", onClick = viewModel::clearEvents, variant = ZdButtonVariant.Ghost, icon = ZdIcons.Trash) }
            }
            item { ZdFootnote("Frames are heard on the USB adapter. The phone Wi-Fi radio is not used, and nothing is transmitted.") }
        }
    }
}

@Composable
private fun EventCard(event: DeauthEvent) {
    val severity = event.level.severity()
    ZdCard(borderColor = severity.color.copy(alpha = 0.4f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZdSeverityBadge(severity, label = event.type.label.uppercase())
            Text(timeFormat.format(Date(event.timestamp)), style = ZdType.Path, color = ZdColors.Text3, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
        Text(event.title, style = ZdType.Label, color = ZdColors.Text)
        Text(event.detail, style = ZdType.BodySmall, color = ZdColors.Text2)
        event.affectedSsid?.let { Text("Network: $it", style = ZdType.Caption, color = ZdColors.Text3) }
    }
}
