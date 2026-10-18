package app.radiusrf.features.bluetooth_tracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.radiusrf.core.lifecycle.HardwareLifecycleEffect
import app.radiusrf.core.permission.PermissionGate
import app.radiusrf.core.permission.PermissionUtils
import app.radiusrf.core.ui.zd.ZdButton
import app.radiusrf.core.ui.zd.ZdButtonVariant
import app.radiusrf.core.ui.zd.ZdCard
import app.radiusrf.core.ui.zd.ZdCheckRow
import app.radiusrf.core.ui.zd.ZdCheckStatus
import app.radiusrf.core.ui.zd.ZdFootnote
import app.radiusrf.core.ui.zd.ZdIcons
import app.radiusrf.core.ui.zd.ZdListCard
import app.radiusrf.core.ui.zd.ZdListRow
import app.radiusrf.core.ui.zd.ZdSectionLabel
import app.radiusrf.core.ui.zd.ZdSeverity
import app.radiusrf.core.ui.zd.ZdSeverityBadge
import app.radiusrf.core.ui.zd.ZdSignal
import app.radiusrf.core.ui.zd.ZdStatePanel
import app.radiusrf.core.ui.zd.ZdToolScanBar
import app.radiusrf.core.util.formatSpan
import app.radiusrf.features.ble.domain.BleDistanceEstimator
import app.radiusrf.features.bluetooth_tracker.domain.DetectedTracker
import app.radiusrf.features.bluetooth_tracker.domain.TrackingRisk
import app.radiusrf.features.bluetooth_tracker.viewmodel.BluetoothTrackerViewModel
import app.radiusrf.ui.theme.ZdColors
import app.radiusrf.ui.theme.ZdType

@Composable
fun BluetoothTrackerScreen(
    onOpenDevice: (address: String, name: String) -> Unit = { _, _ -> },
    viewModel: BluetoothTrackerViewModel = hiltViewModel()
) {
    PermissionGate(
        permissions = (PermissionUtils.blePermissions() + PermissionUtils.locationPermissions()).distinct(),
        rationale = "Bluetooth reads the advertisement. Location is how this phone tells that the same tracker showed up in more than one place."
    ) {
        BluetoothTrackerContent(viewModel = viewModel, onOpenDevice = onOpenDevice)
    }
}

private fun TrackingRisk.severity(): ZdSeverity = when (this) {
    TrackingRisk.HIGH -> ZdSeverity.HIGH
    TrackingRisk.MEDIUM -> ZdSeverity.MEDIUM
    TrackingRisk.LOW -> ZdSeverity.LOW
    TrackingRisk.NONE -> ZdSeverity.CLEAN
}

@Composable
private fun BluetoothTrackerContent(viewModel: BluetoothTrackerViewModel, onOpenDevice: (String, String) -> Unit) {
    val state by viewModel.state.collectAsState()

    HardwareLifecycleEffect(
        isActive = state.isScanning,
        onPause = viewModel::pauseScan,
        onResume = viewModel::resumeScan,
        onStop = viewModel::stopScan
    )

    Column(Modifier.fillMaxSize()) {
        ZdToolScanBar(
            running = state.isScanning,
            paused = state.isPaused,
            onStart = viewModel::startScan,
            onPause = viewModel::pauseScan,
            onResume = viewModel::resumeScan,
            onStop = viewModel::stopScan,
            runningNote = "Find My, Tile, Chipolo · ${state.totalDevicesScanned} devices checked",
            idleNote = if (state.trackers.isEmpty()) "Keep it running while you move" else "Results kept"
        )

        if (state.trackers.isEmpty()) {
            ZdStatePanel(
                kicker = when {
                    state.error != null -> "Scan failed"
                    state.isScanning -> "Watching"
                    else -> "Ready"
                },
                kickerColor = if (state.error != null) ZdColors.Critical else ZdColors.Text3,
                icon = ZdIcons.Tracker,
                iconTint = ZdColors.Accent,
                iconBackground = ZdColors.AccentBg,
                title = if (state.isScanning) "No trackers so far" else "Is something following you?",
                body = state.error ?: if (state.isScanning) {
                    "Keep the phone with you and move. A Find My, Tile, or Chipolo advertisement is listed when it is heard. It is marked after it shows up in 3 places."
                } else {
                    "Reads Find My advertisements, Tile’s service, and Chipolo’s company ID. The same tracker is marked once it has been heard in 3 places at least 250 m apart."
                },
                primaryAction = if (state.isScanning) null else "Start scan" to viewModel::startScan,
                primaryIcon = ZdIcons.Play
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val following = state.trackers.filter { it.risk == TrackingRisk.HIGH }
            if (following.isNotEmpty()) {
                item { FollowingCard(following) }
            }
            item { ZdSectionLabel("Nearby trackers", trailingText = "${state.trackers.size}") }
            item {
                ZdListCard(state.trackers) { tracker -> TrackerRow(tracker, onClick = { onOpenDevice(tracker.address, tracker.displayName) }) }
            }
            item { ZdSectionLabel("How risk is decided") }
            item {
                ZdListCard(
                    listOf(
                        Triple("High", "Heard in 3 places, 250 m apart", ZdCheckStatus.WARN),
                        Triple("Medium", "Advertisement heard again, not yet in 3 places", ZdCheckStatus.WARN),
                        Triple("Low", "Heard once", ZdCheckStatus.NA)
                    )
                ) { (title, detail, status) -> ZdCheckRow(title = title, detail = detail, status = status) }
            }
            item {
                ZdButton(
                    "Clear list",
                    onClick = viewModel::clearTrackers,
                    variant = ZdButtonVariant.Ghost,
                    icon = ZdIcons.Trash
                )
            }
            item {
                ZdFootnote("Find My devices are matched on the advertisement key, so a rotating address stays one entry. Your own tags show up too.")
            }
        }
    }
}

@Composable
private fun FollowingCard(following: List<DetectedTracker>) {
    val first = following.first()
    ZdCard(background = ZdColors.HighBg, borderColor = ZdColors.High.copy(alpha = 0.45f)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(ZdIcons.Warning, contentDescription = null, tint = ZdColors.High, modifier = Modifier.size(20.dp))
            Text(
                if (following.size == 1) "1 tracker seen in 3 places" else "${following.size} trackers seen in 3 places",
                style = ZdType.Heading,
                color = ZdColors.Text,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            "${first.displayName} · ${first.places} places · seen ${first.seenCount}× over ${formatSpan(first.lastSeen - first.firstSeen)}.",
            style = ZdType.BodySmall,
            color = ZdColors.Text2
        )
        Text(
            "If it isn’t yours: check bags, pockets and your car. Most trackers can also make a sound from their owner’s app.",
            style = ZdType.Caption,
            color = ZdColors.Text3
        )
    }
}

@Composable
private fun TrackerRow(tracker: DetectedTracker, onClick: () -> Unit) {
    ZdListRow(
        onClick = onClick,
        title = tracker.displayName,
        subtitle = "${BleDistanceEstimator.rangeLabel(tracker.rssi)} · ${tracker.places} places · seen ${tracker.seenCount}×",
        leading = { ZdSignal(tracker.rssi) },
        trailing = { ZdSeverityBadge(tracker.risk.severity(), label = if (tracker.risk == TrackingRisk.NONE) "SEEN" else tracker.risk.label) }
    )
}
