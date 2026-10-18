package app.radiusrf.features.privacy_score.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
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
import app.radiusrf.core.ui.zd.ZdProportionRow
import app.radiusrf.core.ui.zd.ZdScanControlBar
import app.radiusrf.core.ui.zd.ZdSectionLabel
import app.radiusrf.core.ui.zd.ZdSeverity
import app.radiusrf.core.ui.zd.ZdSeverityBadge
import app.radiusrf.core.ui.zd.ZdStatePanel
import app.radiusrf.core.util.formatAgo
import app.radiusrf.features.privacy_score.domain.CheckCategory
import app.radiusrf.features.privacy_score.domain.CheckStatus
import app.radiusrf.features.privacy_score.domain.PrivacyCheck
import app.radiusrf.features.privacy_score.domain.PrivacyScoreState
import app.radiusrf.features.privacy_score.viewmodel.PrivacyScoreViewModel
import app.radiusrf.ui.theme.ZdColors
import app.radiusrf.ui.theme.ZdType

@Composable
fun PrivacyScoreScreen(
    viewModel: PrivacyScoreViewModel = hiltViewModel()
) {
    PermissionGate(
        permissions = PermissionUtils.privacyScorePermissions(),
        rationale = "Some checks look at nearby WiFi and Bluetooth, which Android only shares with permission."
    ) {
        PrivacyScoreContent(viewModel)
    }
}

private fun gradeColor(score: Int): Color = when {
    score >= 85 -> ZdColors.Accent
    score >= 70 -> ZdColors.Info
    score >= 50 -> ZdColors.Medium
    else -> ZdColors.Critical
}

private fun verdict(score: Int): String = when {
    score >= 85 -> "Excellent"
    score >= 70 -> "Good"
    score >= 50 -> "Fair"
    else -> "Needs work"
}

@Composable
private fun PrivacyScoreContent(viewModel: PrivacyScoreViewModel) {
    val state by viewModel.state.collectAsState()

    Column(Modifier.fillMaxSize()) {
        ZdScanControlBar(
            running = state.isScanning,
            paused = state.isPaused,
            onStart = { if (state.isPaused) viewModel.resumeScan() else if (state.score >= 0) viewModel.rescan() else viewModel.startScan() },
            onPause = viewModel::pauseScan,
            onResume = viewModel::resumeScan,
            onStop = viewModel::stopScan,
            runningLabel = "Checking",
            runningDetail = "Reading ${CheckCategory.entries.size} areas of this phone",
            idleLabel = if (state.score >= 0) "Checked" else "Not checked yet",
            idleDetail = state.lastScanTime?.let { "Last run ${formatAgo(it)} · ${state.checks.size} checks" } ?: "Takes about 15 seconds",
            startLabel = if (state.score >= 0) "Re-run" else "Start"
        )

        if (state.score < 0) {
            ZdStatePanel(
                kicker = if (state.isScanning) "Checking" else state.error?.let { "Couldn’t finish" } ?: "Ready",
                kickerColor = if (state.error != null) ZdColors.Critical else ZdColors.Text3,
                icon = ZdIcons.ShieldCheck,
                iconTint = ZdColors.Accent,
                iconBackground = ZdColors.AccentBg,
                title = "How exposed is this phone?",
                body = state.error ?: "16+ checks across WiFi, Bluetooth, device, network and physical security, weighted by how much each one exposes you. Nothing leaves the phone.",
                primaryAction = if (state.isScanning) null else "Check my phone" to viewModel::startScan,
                primaryIcon = ZdIcons.Play
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { ScoreCard(state) }
            item { ZdSectionLabel("By area") }
            item {
                ZdCard(verticalSpacing = 10.dp) {
                    CheckCategory.entries.forEach { c ->
                        val score = state.categoryScores[c] ?: 0
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row {
                                Text(c.label, style = ZdType.Label, color = ZdColors.Text, modifier = Modifier.weight(1f))
                                Text("weight ${c.weightPercent}%", style = ZdType.Path, color = ZdColors.Text3)
                            }
                            ZdProportionRow(label = "$score", count = score, total = 100, color = gradeColor(score))
                        }
                    }
                }
            }
            val fixes = state.checks
                .filter { it.status != CheckStatus.PASS }
                .sortedWith(compareBy<PrivacyCheck> { it.status != CheckStatus.FAIL }.thenByDescending { it.weight })
            if (fixes.isNotEmpty()) {
                item { ZdSectionLabel("Fix first", trailingText = "${fixes.size}") }
                item {
                    ZdListCard(fixes) { check ->
                        ZdListRow(
                            title = check.name,
                            titleMono = false,
                            subtitle = check.recommendation ?: check.detail,
                            leading = {
                                ZdSeverityBadge(
                                    when {
                                        check.status == CheckStatus.FAIL && check.weight >= 7 -> ZdSeverity.HIGH
                                        check.status == CheckStatus.FAIL -> ZdSeverity.MEDIUM
                                        else -> ZdSeverity.LOW
                                    },
                                    modifier = Modifier.width(88.dp)
                                )
                            }
                        )
                    }
                }
            }
            val passed = state.checks.filter { it.status == CheckStatus.PASS }
            if (passed.isNotEmpty()) {
                item { ZdSectionLabel("Passed", trailingText = "${passed.size}") }
                item {
                    ZdListCard(passed) { ZdCheckRow(title = it.name, detail = it.detail, status = ZdCheckStatus.PASS) }
                }
            }
            state.error?.let { item { ZdFootnote(it, icon = ZdIcons.Warning) } }
            item { ZdButton("Run again", onClick = viewModel::rescan, variant = ZdButtonVariant.Secondary, icon = ZdIcons.Refresh, enabled = !state.isScanning) }
        }
    }
}

@Composable
private fun ScoreCard(state: PrivacyScoreState) {
    val color = gradeColor(state.score)
    val fixes = state.failCount + state.warnCount
    ZdCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(state.grade, style = ZdType.Display.copy(fontSize = ZdType.Display.fontSize.times(1.6f)), color = color)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${state.score}/100", style = ZdType.Heading, color = ZdColors.Text)
                Text(
                    if (fixes == 0) "${verdict(state.score)}, nothing to fix" else "${verdict(state.score)}, $fixes fix${if (fixes > 1) "es" else ""} left",
                    style = ZdType.BodySmall,
                    color = ZdColors.Text2
                )
                Text("${state.passCount} pass · ${state.warnCount} warn · ${state.failCount} fail", style = ZdType.Caption, color = ZdColors.Text3)
            }
        }
    }
}
