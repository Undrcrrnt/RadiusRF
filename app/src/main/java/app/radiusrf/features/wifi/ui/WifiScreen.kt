package app.radiusrf.features.wifi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.radiusrf.core.lifecycle.HardwareLifecycleEffect
import app.radiusrf.core.permission.PermissionGate
import app.radiusrf.core.permission.PermissionUtils
import app.radiusrf.core.ui.zd.ZdBar
import app.radiusrf.core.ui.zd.ZdBarChart
import app.radiusrf.core.ui.zd.ZdButton
import app.radiusrf.core.ui.zd.ZdCard
import app.radiusrf.core.ui.zd.ZdCheckRow
import app.radiusrf.core.ui.zd.ZdCheckStatus
import app.radiusrf.core.ui.zd.ZdChip
import app.radiusrf.core.ui.zd.ZdChipRow
import app.radiusrf.core.ui.zd.ZdDivider
import app.radiusrf.core.ui.zd.ZdFootnote
import app.radiusrf.core.ui.zd.ZdIcons
import app.radiusrf.core.ui.zd.ZdListCard
import app.radiusrf.core.ui.zd.ZdListRow
import app.radiusrf.core.ui.zd.ZdProportionRow
import app.radiusrf.core.ui.zd.ZdSectionLabel
import app.radiusrf.core.ui.zd.ZdSignal
import app.radiusrf.core.ui.zd.ZdStat
import app.radiusrf.core.ui.zd.ZdStatePanel
import app.radiusrf.core.ui.zd.ZdTabs
import app.radiusrf.core.ui.zd.ZdTag
import app.radiusrf.core.ui.zd.ZdToolScanBar
import app.radiusrf.core.ui.zd.congestionColor
import app.radiusrf.core.util.SecurityType
import app.radiusrf.core.util.WifiBand
import app.radiusrf.features.wifi.domain.ChannelLoad
import app.radiusrf.features.wifi.domain.CheckOutcome
import app.radiusrf.features.wifi.domain.WifiAccessPoint
import app.radiusrf.features.wifi.domain.WifiAssessment
import app.radiusrf.features.wifi.domain.WifiNicSource
import app.radiusrf.features.wifi.viewmodel.WifiViewModel
import app.radiusrf.ui.theme.ZdColors
import app.radiusrf.ui.theme.ZdType

private enum class NetworkFilter(val label: String) { ALL("All"), BAND_24("2.4 GHz"), BAND_5("5 GHz"), WEAK("Weak security") }

@Composable
fun WifiScreen(
    onLocate: (WifiAccessPoint) -> Unit = {},
    viewModel: WifiViewModel = hiltViewModel()
) {
    PermissionGate(
        permissions = PermissionUtils.wifiPermissions(),
        rationale = "Android needs location access before any app can list WiFi networks."
    ) {
        WifiContent(viewModel = viewModel, onLocate = onLocate)
    }
}

@Composable
private fun WifiContent(viewModel: WifiViewModel, onLocate: (WifiAccessPoint) -> Unit) {
    val accessPoints by viewModel.accessPoints.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val isPaused by viewModel.isPaused.collectAsState()
    val scanError by viewModel.error.collectAsState()
    val nic by viewModel.nic.source.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableStateOf(NetworkFilter.ALL) }
    var layout by rememberSaveable { mutableStateOf(NetworkLayout.CARDS) }
    var selectedBssid by rememberSaveable { mutableStateOf<String?>(null) }

    HardwareLifecycleEffect(
        isActive = isScanning,
        onPause = viewModel::pauseScan,
        onResume = viewModel::resumeScan,
        onStop = viewModel::stopScan
    )

    val sorted = accessPoints.sortedByDescending { it.rssi }

    Column(Modifier.fillMaxSize()) {
        ZdToolScanBar(
            running = isScanning,
            paused = isPaused,
            onStart = viewModel::startScan,
            onPause = viewModel::pauseScan,
            onResume = viewModel::resumeScan,
            onStop = viewModel::stopScan,
            backgroundLabel = "Wi-Fi scan",
            onBackgroundChange = viewModel::setRunInBackground,
            compactControls = true,
            idleNote = if (nic == WifiNicSource.PHONE) {
                if (accessPoints.isEmpty()) "Phone radio" else "Results kept"
            } else {
                "${nic.label} · beacons"
            }
        )
        WifiNicPicker(source = nic, onSelect = viewModel::selectSource)

        if (accessPoints.isEmpty() && !isScanning) {
            ZdStatePanel(
                kicker = if (scanError != null) "Scan failed" else "Ready",
                kickerColor = if (scanError != null) ZdColors.Critical else ZdColors.Text3,
                icon = ZdIcons.Wifi,
                iconTint = ZdColors.Accent,
                iconBackground = ZdColors.AccentBg,
                title = "See the WiFi around you",
                body = scanError ?: "Find crowded channels, weak security and networks you don’t recognise. Tap Start to scan.",
                primaryAction = "Start scan" to viewModel::startScan,
                primaryIcon = ZdIcons.Play
            )
            return@Column
        }

        val weak = accessPoints.count { WifiAssessment.isWeak(it.security) }
        ZdTabs(
            tabs = listOf("Networks ${accessPoints.size}", "Channels", "Security"),
            selectedIndex = tab,
            onSelect = { tab = it },
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            scanError?.let { message ->
                item { ZdFootnote(message, icon = ZdIcons.Warning) }
            }
            when (tab) {
                0 -> networksTab(sorted, filter, weak, layout, onFilter = { filter = it }, onLayout = { layout = it }, onOpen = { selectedBssid = it.bssid })
                1 -> channelsTab(accessPoints)
                else -> securityTab(sorted, weak, onOpen = { selectedBssid = it.bssid })
            }
        }
    }

    val selected = accessPoints.firstOrNull { it.bssid == selectedBssid }
    if (selected != null) {
        NetworkDetailSheet(
            ap = selected,
            all = accessPoints,
            onDismiss = { selectedBssid = null },
            onLocate = {
                onLocate(it)
                selectedBssid = null
            }
        )
    }
}

private enum class NetworkLayout(val label: String) { CARDS("Cards"), COMPACT("Compact"), LINKS("Links") }

private fun LazyListScope.networksTab(
    sorted: List<WifiAccessPoint>,
    filter: NetworkFilter,
    weak: Int,
    layout: NetworkLayout,
    onFilter: (NetworkFilter) -> Unit,
    onLayout: (NetworkLayout) -> Unit,
    onOpen: (WifiAccessPoint) -> Unit
) {
    item {
        val five = filter == NetworkFilter.BAND_5
        val bandPoints = sorted.filter { it.band == if (five) WifiBand.BAND_5GHZ else WifiBand.BAND_2_4GHZ }
        val channels = if (five) WifiAssessment.CHANNELS_5 else WifiAssessment.CHANNELS_24
        val title = if (five) "5 GHz congestion" else "2.4 GHz congestion"
        val load = WifiAssessment.load(bandPoints, channels)
        val best = if (five) {
            if (bandPoints.isEmpty()) null else channels.minByOrNull { channel -> bandPoints.count { it.channel == channel } }
        } else {
            WifiAssessment.best24Channel(sorted)
        }
        ZdCard(verticalSpacing = 8.dp, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = ZdType.Mono.copy(fontWeight = ZdType.Label.fontWeight), color = ZdColors.Text2, modifier = Modifier.weight(1f))
                best?.let {
                    Text("best: ch $it", style = ZdType.Mono.copy(fontWeight = ZdType.Label.fontWeight), color = ZdColors.Accent)
                }
            }
            ZdBarChart(
                bars = load.map { ZdBar(it.channel.toString(), it.networks.toFloat(), congestionColor(it.networks)) },
                contentDescription = "Networks per ${if (five) "5" else "2.4"} GHz channel"
            )
        }
    }
    item {
        ZdChipRow(Modifier.padding(horizontal = 0.dp), contentPadding = 0.dp) {
            NetworkFilter.entries.forEach { f ->
                val count = when (f) {
                    NetworkFilter.ALL -> sorted.size
                    NetworkFilter.BAND_24 -> sorted.count { it.band == WifiBand.BAND_2_4GHZ }
                    NetworkFilter.BAND_5 -> sorted.count { it.band == WifiBand.BAND_5GHZ }
                    NetworkFilter.WEAK -> weak
                }
                ZdChip("${f.label} $count", selected = filter == f, onClick = { onFilter(f) })
            }
        }
    }
    item {
        ZdChipRow(Modifier.padding(horizontal = 0.dp), contentPadding = 0.dp) {
            NetworkLayout.entries.forEach { choice ->
                ZdChip(choice.label, selected = layout == choice, onClick = { onLayout(choice) })
            }
        }
    }
    val shown = sorted.filter {
        when (filter) {
            NetworkFilter.ALL -> true
            NetworkFilter.BAND_24 -> it.band == WifiBand.BAND_2_4GHZ
            NetworkFilter.BAND_5 -> it.band == WifiBand.BAND_5GHZ
            NetworkFilter.WEAK -> WifiAssessment.isWeak(it.security)
        }
    }
    item {
        if (shown.isEmpty()) {
            ZdFootnote("No networks match this filter.")
        } else {
            when (layout) {
                NetworkLayout.CARDS -> ZdListCard(shown) { ap -> NetworkRow(ap, sorted, onClick = { onOpen(ap) }) }
                NetworkLayout.COMPACT -> ZdListCard(shown) { ap ->
                    ZdListRow(
                        title = WifiAssessment.displayName(ap),
                        subtitle = buildString {
                            append("Access point · ${ap.bssid} · ${ap.rssi} dBm · ch ${ap.channel}")
                            if (ap.clients.isNotEmpty()) append(" · ${clientLine(ap)}")
                        },
                        onClick = { onOpen(ap) }
                    )
                }
                NetworkLayout.LINKS -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    shown.forEach { ap ->
                        ZdCard {
                            Text(WifiAssessment.displayName(ap), style = ZdType.Label, color = ZdColors.Text)
                            Text("Access point · ${ap.bssid}", style = ZdType.Caption, color = ZdColors.Text3)
                            if (ap.clients.isEmpty()) {
                                Text("No associated client in this scan.", style = ZdType.BodySmall, color = ZdColors.Text2, modifier = Modifier.padding(top = 4.dp))
                            } else {
                                ap.clients.forEach { client ->
                                    Text(
                                        "${client.role} ${client.mac} · linked to this access point · ${client.rssi} dBm",
                                        style = ZdType.BodySmall,
                                        color = ZdColors.Text2,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.channelsTab(accessPoints: List<WifiAccessPoint>) {
    val on24 = accessPoints.filter { it.band == WifiBand.BAND_2_4GHZ }
    val on5 = accessPoints.filter { it.band == WifiBand.BAND_5GHZ }
    val best = WifiAssessment.best24Channel(accessPoints)
    val busy24 = WifiAssessment.busiestChannel(accessPoints, WifiBand.BAND_2_4GHZ)
    val busy5 = WifiAssessment.busiestChannel(accessPoints, WifiBand.BAND_5GHZ)

    item {
        ChannelCard(
            title = "2.4 GHz",
            trailing = "${on24.size} networks",
            bars = WifiAssessment.load(on24, WifiAssessment.CHANNELS_24),
            note = buildString {
                append("Channels 1, 6 and 11 don’t overlap.")
                if (busy24 != null) append(" Channel ${busy24.channel} is crowded here")
                if (best != null && busy24 != null) append("; a router would do better on $best.") else if (busy24 != null) append(".")
            }
        )
    }
    item {
        ChannelCard(
            title = "5 GHz",
            trailing = "${on5.size} networks",
            bars = WifiAssessment.load(on5, WifiAssessment.CHANNELS_5),
            note = if (busy5 == null) "Plenty of room. 5 GHz has many non-overlapping channels, so it rarely gets crowded."
            else "Channel ${busy5.channel} is shared by ${busy5.networks} networks. 5 GHz reaches less far through walls, which also keeps it quieter."
        )
    }
    if (best != null && busy24 != null) {
        item {
            ZdCard(background = ZdColors.AccentBg, borderColor = ZdColors.AccentBorder) {
                Text(
                    "Recommendation: move your 2.4 GHz router to channel $best. Change it in your router’s admin page.",
                    style = ZdType.BodySmall,
                    color = ZdColors.Text
                )
            }
        }
    }
}

@Composable
private fun ChannelCard(title: String, trailing: String, bars: List<ChannelLoad>, note: String) {
    ZdCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = ZdType.Label, color = ZdColors.Text, modifier = Modifier.weight(1f))
            Text(trailing, style = ZdType.Mono, color = ZdColors.Text3)
        }
        ZdBarChart(
            bars = bars.map { ZdBar(it.channel.toString(), it.networks.toFloat(), congestionColor(it.networks)) },
            height = 96.dp,
            contentDescription = "Networks per $title channel"
        )
        Text(note, style = ZdType.BodySmall, color = ZdColors.Text2)
    }
}

private fun LazyListScope.securityTab(
    sorted: List<WifiAccessPoint>,
    weak: Int,
    onOpen: (WifiAccessPoint) -> Unit
) {
    item {
        ZdCard {
            Text(
                if (weak == 0) "All ${sorted.size} networks are encrypted" else "$weak of ${sorted.size} need attention",
                style = ZdType.Heading,
                color = if (weak == 0) ZdColors.Accent else ZdColors.Text
            )
            Text("Open and WEP networks send data others nearby can read.", style = ZdType.BodySmall, color = ZdColors.Text2)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                listOf(SecurityType.WPA3, SecurityType.WPA2, SecurityType.WPA, SecurityType.WEP, SecurityType.OPEN).forEach { type ->
                    ZdProportionRow(
                        label = type.label.uppercase(),
                        count = sorted.count { it.security == type },
                        total = sorted.size,
                        color = securityColor(type)
                    )
                }
            }
        }
    }
    val risky = sorted.filter { WifiAssessment.isWeak(it.security) }
    if (risky.isNotEmpty()) {
        item { ZdSectionLabel("Risky networks") }
        item { ZdListCard(risky) { ap -> NetworkRow(ap, sorted, onClick = { onOpen(ap) }) } }
    }
}

private fun clientLine(ap: WifiAccessPoint): String {
    val shown = ap.clients.take(3).joinToString(" · ") { it.mac }
    val extra = ap.clients.size - 3
    return if (extra > 0) "Clients $shown · +$extra" else "Clients $shown"
}

@Composable
private fun NetworkRow(ap: WifiAccessPoint, all: List<WifiAccessPoint>, onClick: () -> Unit) {
    val twin = !WifiAssessment.isHidden(ap) && all.any {
        it.ssid == ap.ssid && it.bssid != ap.bssid && WifiAssessment.ouiOf(it.bssid) != WifiAssessment.ouiOf(ap.bssid)
    }
    ZdListRow(
        title = WifiAssessment.displayName(ap),
        leading = { ZdSignal(ap.rssi) },
        showChevron = true,
        onClick = onClick,
        supporting = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SecurityTag(ap.security)
                    Text("ch ${ap.channel} · ${WifiAssessment.hardwareLabel(ap.bssid)}", style = ZdType.Caption, color = ZdColors.Text3)
                }
                if (ap.clients.isNotEmpty()) {
                    Text(clientLine(ap), style = ZdType.Caption, color = ZdColors.Text2)
                }
                if (twin) {
                    ZdTag("Same name, other hardware", color = ZdColors.Medium, background = ZdColors.MediumBg, border = ZdColors.MediumBorder)
                }
            }
        }
    )
}

@Composable
private fun SecurityTag(type: SecurityType) {
    val color = securityColor(type)
    val bg = when (type) {
        SecurityType.WPA3, SecurityType.WPA2 -> ZdColors.AccentBg
        SecurityType.WPA -> ZdColors.MediumBg
        SecurityType.WEP, SecurityType.OPEN -> ZdColors.CriticalBg
        SecurityType.UNKNOWN -> ZdColors.Surface2
    }
    ZdTag(type.label.uppercase(), color = color, background = bg, border = bg)
}

private fun securityColor(type: SecurityType): Color = when (type) {
    SecurityType.WPA3, SecurityType.WPA2 -> ZdColors.Accent
    SecurityType.WPA -> ZdColors.Medium
    SecurityType.WEP, SecurityType.OPEN -> ZdColors.Critical
    SecurityType.UNKNOWN -> ZdColors.Text3
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkDetailSheet(
    ap: WifiAccessPoint,
    all: List<WifiAccessPoint>,
    onDismiss: () -> Unit,
    onLocate: (WifiAccessPoint) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ZdColors.Surface,
        scrimColor = ZdColors.Scrim,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    ) {
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ZdSignal(ap.rssi)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(WifiAssessment.displayName(ap), style = ZdType.Title, color = ZdColors.Text)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SecurityTag(ap.security)
                        ZdTag(ap.band.label)
                    }
                }
                Text(
                    WifiAssessment.grade(ap),
                    style = ZdType.Display.copy(fontSize = ZdType.Title.fontSize.times(1.4f)),
                    color = securityColor(ap.security)
                )
            }
            ZdCard {
                Row {
                    ZdStat("BSSID", ap.bssid, Modifier.weight(1f))
                    ZdStat("Hardware", WifiAssessment.hardwareLabel(ap.bssid).removePrefix("OUI "), Modifier.weight(1f))
                }
                Row {
                    ZdStat("Channel", "${ap.channel} (${ap.frequency} MHz)", Modifier.weight(1f))
                    ZdStat("Width", if (ap.channelWidth > 0) "${ap.channelWidth} MHz" else "—", Modifier.weight(1f))
                }
                Row {
                    ZdStat("Signal", "${ap.rssi} dBm · ${ap.signalPercent}%", Modifier.weight(1f))
                    ZdStat("Co-channel APs", WifiAssessment.coChannel(ap, all).toString(), Modifier.weight(1f))
                }
                Row {
                    ZdStat("Role", "Access point", Modifier.weight(1f))
                    ZdStat("Clients", ap.clients.size.toString(), Modifier.weight(1f))
                }
            }
            if (ap.clients.isNotEmpty()) {
                ZdSectionLabel("Associated clients")
                ap.clients.forEach { client ->
                    ZdListRow(title = client.mac, subtitle = "${client.role} of ${WifiAssessment.displayName(ap)} · ${client.rssi} dBm")
                }
            }
            ZdSectionLabel("Security rating")
            ZdListCard(WifiAssessment.checks(ap, all)) { check ->
                ZdCheckRow(
                    title = check.title,
                    detail = check.detail,
                    status = when (check.outcome) {
                        CheckOutcome.PASS -> ZdCheckStatus.PASS
                        CheckOutcome.WARN -> ZdCheckStatus.WARN
                        CheckOutcome.FAIL -> ZdCheckStatus.FAIL
                    }
                )
            }
            ZdButton("Locate", onClick = { onLocate(ap) }, icon = ZdIcons.Crosshair, modifier = Modifier.fillMaxWidth())
            ZdDivider()
            ZdFootnote("Grades weigh the encryption first, then extras like WPS. A = WPA3 without WPS; F = open or WEP. Locate follows this access point’s signal. It does not join the network.")
        }
    }
}
