package app.radiusrf.features.locate.ui

import android.media.AudioManager
import android.media.ToneGenerator
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import app.radiusrf.core.permission.PermissionGate
import app.radiusrf.core.permission.PermissionUtils
import app.radiusrf.core.ui.zd.ZdButton
import app.radiusrf.core.ui.zd.ZdButtonVariant
import app.radiusrf.core.ui.zd.ZdFootnote
import app.radiusrf.core.ui.zd.ZdIcons
import app.radiusrf.core.ui.zd.ZdSwitchRow
import app.radiusrf.features.locate.domain.LocateCue
import app.radiusrf.features.locate.domain.LocateRadio
import app.radiusrf.features.locate.domain.LocateSample
import app.radiusrf.features.locate.domain.LocateSignals
import app.radiusrf.features.locate.viewmodel.LocateUiState
import app.radiusrf.features.locate.viewmodel.LocateViewModel
import app.radiusrf.features.wifi.ui.WifiNicPicker
import app.radiusrf.ui.theme.JetBrainsMono
import app.radiusrf.ui.theme.ZdColors
import app.radiusrf.ui.theme.ZdType
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.min

@Composable
fun LocateScreen(viewModel: LocateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val wifi = state.radio == LocateRadio.WIFI
    PermissionGate(
        permissions = if (wifi) PermissionUtils.wifiPermissions() else PermissionUtils.blePermissions(),
        rationale = if (wifi) {
            "Locate follows one access point. Android requires location before the phone can scan Wi-Fi."
        } else {
            "Locate follows one Bluetooth device’s signal strength as you move."
        }
    ) {
        LaunchedEffect(Unit) { viewModel.start() }
        Feedback(state)
        LocateContent(state, viewModel)
    }
}

/** Ticks faster as the signal gets louder, and stays silent when the target is quiet or gone. */
@Composable
private fun Feedback(state: LocateUiState) {
    val view = LocalView.current
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 60) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { tone?.release() } }
    val current by rememberUpdatedState(state)
    val active = state.running && (state.beep || state.haptics)
    LaunchedEffect(active) {
        var lastTick = 0L
        while (active && isActive) {
            val live = current
            val interval = LocateSignals.tickIntervalMs(live.rssi, live.cue)
            if (interval == null) {
                delay(200)
                continue
            }
            val wait = interval - (System.currentTimeMillis() - lastTick)
            if (wait > 0L) {
                delay(wait.coerceAtMost(80L))
                continue
            }
            if (live.beep) tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
            if (live.haptics) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            lastTick = System.currentTimeMillis()
        }
    }
}

@Composable
private fun LocateContent(state: LocateUiState, viewModel: LocateViewModel) {
    val cueColor = cueColor(state.cue)
    val wifi = state.radio == LocateRadio.WIFI
    val hint = LocateSignals.hint(state.cue, wifi, state.sweepPace)
    val heardAgo = if (state.lastSeen > 0L) {
        ((System.currentTimeMillis() - state.lastSeen) / 1000L).coerceAtLeast(0L)
    } else {
        null
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            LocateSignals.label(state.cue),
            color = cueColor,
            style = ZdType.Title.copy(fontSize = 28.sp, lineHeight = 34.sp),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = "${LocateSignals.label(state.cue)}. $hint"
                }
        )
        Spacer(Modifier.height(8.dp))
        Text(
            hint,
            style = ZdType.BodySmall,
            color = ZdColors.Text2,
            textAlign = TextAlign.Center,
            minLines = if (wifi) 3 else 2,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
        if (wifi) {
            val channel = state.channel.takeIf { it > 0 }?.let { "ch $it" } ?: "channel —"
            Text(
                "Locked ${state.address.ifBlank { "—" }} · $channel",
                style = ZdType.Mono,
                color = ZdColors.Text3,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
            WifiNicPicker(
                source = state.nic,
                onSelect = viewModel::selectSource,
                horizontalPadding = 0.dp
            )
        }
        Text(
            state.rssi?.toString() ?: "—",
            style = ZdType.Display.copy(fontSize = 64.sp, lineHeight = 68.sp),
            color = cueColor
        )
        Text(
            state.rssi?.let { "${LocateSignals.band(it)} · not a distance" } ?: "no live RSSI",
            style = ZdType.Heading,
            color = ZdColors.Text2
        )
        Text(
            if (state.peakRssi > -127) "Loudest this locate  ${state.peakRssi} dBm" else "Loudest this locate  —",
            style = ZdType.Mono,
            color = ZdColors.Text
        )
        Text(
            when {
                heardAgo == null -> "last heard  —"
                heardAgo < 60L -> "last heard ${heardAgo}s ago"
                else -> "last heard ${heardAgo / 60L}m ago"
            },
            style = ZdType.Path,
            color = ZdColors.Text3
        )
        LocateNeedle(
            cue = state.cue,
            color = cueColor,
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
        )
        Sparkline(state.samples, cueColor, Modifier.fillMaxWidth().height(56.dp))
        Spacer(Modifier.height(8.dp))
        state.error?.let { ZdFootnote(it, icon = ZdIcons.Warning) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZdSwitchRow(
                label = "Beep",
                description = "Faster when louder",
                checked = state.beep,
                onCheckedChange = { viewModel.toggleBeep() },
                modifier = Modifier.weight(1f)
            )
        }
        ZdSwitchRow(
            label = "Vibrate",
            description = "A tick with each beep",
            checked = state.haptics,
            onCheckedChange = { viewModel.toggleHaptics() }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            ZdButton(
                "Reset this locate",
                onClick = viewModel::reset,
                variant = ZdButtonVariant.Secondary,
                enabled = state.running,
                modifier = Modifier.weight(1f)
            )
            if (state.running) {
                ZdButton("Stop", onClick = viewModel::stop, variant = ZdButtonVariant.Danger, icon = ZdIcons.Stop, modifier = Modifier.weight(1f))
            } else {
                ZdButton("Locate again", onClick = viewModel::start, icon = ZdIcons.Play, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun cueColor(cue: LocateCue): Color = when (cue) {
    LocateCue.VERY_CLOSE, LocateCue.CLOSER -> ZdColors.Accent
    LocateCue.FURTHER, LocateCue.GONE -> ZdColors.Critical
    LocateCue.QUIET -> ZdColors.Medium
    LocateCue.SAME -> ZdColors.Text
    LocateCue.WAITING -> ZdColors.Text2
}

@Composable
private fun LocateNeedle(cue: LocateCue, color: Color, modifier: Modifier = Modifier) {
    val period = when (cue) {
        LocateCue.VERY_CLOSE -> 700
        LocateCue.CLOSER -> 900
        LocateCue.FURTHER -> 1100
        LocateCue.SAME -> 2200
        LocateCue.WAITING -> 1800
        LocateCue.QUIET, LocateCue.GONE -> 2400
    }
    val t by rememberInfiniteTransition(label = "locateNeedle").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(period, easing = LinearEasing), RepeatMode.Restart),
        label = "locateNeedleT"
    )
    val youColor = ZdColors.Text
    val ringIdle = ZdColors.Border
    val measurer = rememberTextMeasurer()
    val youStyle = TextStyle(color = youColor, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = JetBrainsMono)
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val maxR = min(size.width, size.height) * 0.42f
        if (maxR < 8f) return@Canvas
        for (i in 1..3) {
            drawCircle(ringIdle, radius = maxR * i / 3f, center = c, style = Stroke(1.4f))
        }
        fun pulse(frac: Float, alpha: Float, width: Float) {
            drawCircle(
                color.copy(alpha = alpha.coerceIn(0f, 1f)),
                radius = (maxR * frac).coerceAtLeast(2f),
                center = c,
                style = Stroke(width)
            )
        }
        when (cue) {
            LocateCue.CLOSER, LocateCue.VERY_CLOSE -> {
                repeat(3) { i ->
                    val frac = ((1f - t) + i / 3f).mod(1f)
                    pulse(frac, (1f - frac) * 0.85f, if (cue == LocateCue.VERY_CLOSE) 5.5f else 4f)
                }
                if (cue == LocateCue.VERY_CLOSE) {
                    drawCircle(color.copy(alpha = 0.22f + 0.18f * (1f - t)), radius = maxR * 0.22f, center = c)
                }
            }
            LocateCue.FURTHER -> {
                repeat(3) { i ->
                    val frac = (t + i / 3f).mod(1f)
                    pulse(frac, (1f - frac) * 0.85f, 4f)
                }
            }
            LocateCue.SAME -> pulse(0.55f, 0.55f, 3.2f)
            LocateCue.WAITING -> pulse(0.4f + 0.2f * t, 0.35f, 2.6f)
            LocateCue.QUIET -> pulse(0.72f, 0.4f, 2.8f)
            LocateCue.GONE -> pulse(0.88f, 0.28f, 2.2f)
        }
        drawCircle(color.copy(alpha = 0.95f), radius = 5.5f, center = c)
        val you = measurer.measure("YOU", youStyle)
        drawText(you, topLeft = Offset(c.x - you.size.width / 2f, c.y + 10f))
    }
}

private const val SPARKLINE_SLOTS = 48

@Composable
private fun Sparkline(samples: List<LocateSample>, color: Color, modifier: Modifier = Modifier) {
    val track = ZdColors.Surface3
    Canvas(modifier) {
        if (samples.isEmpty()) return@Canvas
        val shown = samples.takeLast(SPARKLINE_SLOTS)
        val step = size.width / SPARKLINE_SLOTS
        val stroke = (step * 0.45f).coerceAtLeast(2f)
        val origin = SPARKLINE_SLOTS - shown.size
        shown.forEachIndexed { i, sample ->
            val x = (origin + i) * step + step / 2f
            val p = ((sample.rssi + 100) / 70f).coerceIn(0.05f, 1f)
            val h = size.height * p
            drawLine(
                track,
                Offset(x, size.height),
                Offset(x, 0f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            drawLine(
                color,
                Offset(x, size.height),
                Offset(x, size.height - h),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}
