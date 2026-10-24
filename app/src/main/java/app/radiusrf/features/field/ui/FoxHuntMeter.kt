package app.radiusrf.features.field.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.radiusrf.core.ui.zd.ZdButton
import app.radiusrf.core.ui.zd.ZdButtonVariant
import app.radiusrf.core.ui.zd.ZdCard
import app.radiusrf.core.ui.zd.ZdFootnote
import app.radiusrf.core.ui.zd.ZdSectionLabel
import app.radiusrf.core.ui.zd.ZdTextField
import app.radiusrf.features.field.domain.FoxHuntState
import app.radiusrf.features.field.domain.FoxHuntViewModel
import app.radiusrf.ui.theme.ZdColors
import app.radiusrf.ui.theme.ZdType
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun FoxHuntMeter(state: FoxHuntState, viewModel: FoxHuntViewModel) {
    var mhz by remember(state.frequencyHz) {
        mutableStateOf(String.format(Locale.US, "%.3f", state.frequencyHz / 1_000_000.0))
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ZdSectionLabel("Frequency")
        ZdCard {
            ZdTextField(
                value = mhz,
                onValueChange = { mhz = it },
                label = "WATCH FREQUENCY (MHZ)",
                placeholder = "446.000",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ZdButton("−1 MHz", onClick = { viewModel.stepSdr(-1_000_000L) }, variant = ZdButtonVariant.Secondary, height = 36.dp)
                ZdButton("−100 kHz", onClick = { viewModel.stepSdr(-100_000L) }, variant = ZdButtonVariant.Secondary, height = 36.dp)
                ZdButton("−10 kHz", onClick = { viewModel.stepSdr(-10_000L) }, variant = ZdButtonVariant.Secondary, height = 36.dp)
                ZdButton(
                    "Tune",
                    onClick = { mhz.toDoubleOrNull()?.let { viewModel.tuneSdr((it * 1_000_000.0).toLong()) } },
                    height = 36.dp
                )
                ZdButton("+10 kHz", onClick = { viewModel.stepSdr(10_000L) }, variant = ZdButtonVariant.Secondary, height = 36.dp)
                ZdButton("+100 kHz", onClick = { viewModel.stepSdr(100_000L) }, variant = ZdButtonVariant.Secondary, height = 36.dp)
                ZdButton("+1 MHz", onClick = { viewModel.stepSdr(1_000_000L) }, variant = ZdButtonVariant.Secondary, height = 36.dp)
            }
            Text(
                "Live strength at this frequency only. Closer to zero is stronger.",
                style = ZdType.Caption,
                color = ZdColors.Text3
            )
        }

        ZdSectionLabel("Level")
        ZdCard {
            Text(state.cue, style = ZdType.BodySmall, color = ZdColors.Accent)
            if (state.measuringBackground) {
                Text("Measuring background — the scale then stays put while you sweep.", style = ZdType.Caption, color = ZdColors.Text3)
            }
            SegmentMeter(
                live = state.liveDb,
                peak = state.peakDb,
                floor = state.floorDb,
                ceiling = state.ceilingDb
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("LIVE", style = ZdType.Path, color = ZdColors.Text3)
                    Text(
                        String.format(Locale.US, "%.0f dBm (dBFS)", state.liveDb),
                        style = ZdType.Heading,
                        color = ZdColors.Accent
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("PEAK HOLD", style = ZdType.Path, color = ZdColors.Text3)
                    Text(
                        String.format(Locale.US, "%.0f dBm (dBFS)", state.peakDb),
                        style = ZdType.Heading,
                        color = ZdColors.Text
                    )
                }
            }
            Text(
                "The fill is what the radio hears this instant. The pale line is peak hold: the strongest level seen recently, so a short burst still leaves a mark.",
                style = ZdType.Caption,
                color = ZdColors.Text3,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        ZdSectionLabel("Heading")
        ZdCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("PHONE", style = ZdType.Path, color = ZdColors.Text3)
                    Text("${state.heading.roundToInt()}°", style = ZdType.Heading, color = ZdColors.Text)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("PEAK BEARING", style = ZdType.Path, color = ZdColors.Text3)
                    Text(
                        state.bearing?.let { "${it.roundToInt()}°" } ?: "—",
                        style = ZdType.Heading,
                        color = if (state.bearing != null) ZdColors.Accent else ZdColors.Text3
                    )
                }
            }
            Text(
                "Sweep a directional antenna slowly. The bearing is the heading where the signal peaked — a direction, not a location.",
                style = ZdType.Caption,
                color = ZdColors.Text3
            )
        }

        ZdSectionLabel("Sweep")
        ZdCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZdButton(
                    "Set Ref",
                    onClick = viewModel::setRef,
                    variant = ZdButtonVariant.Primary,
                    modifier = Modifier.weight(1f),
                    enabled = !state.measuringBackground && state.sdrListening
                )
                ZdButton(
                    "Reset Peak",
                    onClick = viewModel::resetPeak,
                    variant = ZdButtonVariant.Secondary,
                    modifier = Modifier.weight(1f),
                    enabled = !state.measuringBackground
                )
            }
            Text(
                "Set Ref makes the current peak the top of the scale, then work either side of it. Reset Peak clears the pale line so you can sweep again.",
                style = ZdType.Caption,
                color = ZdColors.Text3
            )
        }

        if (state.sdrPaused) {
            ZdFootnote("Paused. The level holds until you resume.")
        }
    }
}

@Composable
private fun SegmentMeter(live: Float, peak: Float, floor: Float, ceiling: Float) {
    val count = 20
    val span = (ceiling - floor).coerceAtLeast(8f)
    fun fraction(db: Float) = ((db - floor) / span).coerceIn(0f, 1f)
    val liveFraction = fraction(live)
    val peakFraction = fraction(peak)
    val lit = ((liveFraction * count).toInt() - 1).coerceIn(-1, count - 1)
    val shape = RoundedCornerShape(3.dp)
    val description = String.format(
        Locale.US,
        "Signal meter %.0f dBm live, peak hold %.0f, scale %.0f to %.0f",
        live,
        peak,
        floor,
        ceiling
    )
    Row(
        Modifier
            .fillMaxWidth()
            .height(320.dp)
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                for (band in count - 1 downTo 0) {
                    val on = band <= lit
                    val color = when {
                        !on -> ZdColors.Surface3
                        band >= count * 3 / 4 -> ZdColors.Critical
                        band >= count / 2 -> ZdColors.High
                        else -> ZdColors.Accent
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(shape)
                            .background(color)
                    )
                }
            }
            if (peakFraction > 0f) {
                val fromTop = (maxHeight * (1f - peakFraction)).coerceAtMost(maxHeight - 3.dp)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .align(Alignment.TopStart)
                        .padding(top = fromTop)
                        .clip(shape)
                        .background(Color.White.copy(alpha = 0.85f))
                        .border(1.dp, Color.White, shape)
                )
            }
        }
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
            Text(String.format(Locale.US, "%.0f", ceiling), style = ZdType.Mono, color = ZdColors.Text3)
            Text(String.format(Locale.US, "%.0f", floor + span / 2f), style = ZdType.Mono, color = ZdColors.Text3)
            Text(String.format(Locale.US, "%.0f", floor), style = ZdType.Mono, color = ZdColors.Text3)
        }
    }
}
