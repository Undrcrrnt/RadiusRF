package app.radiusrf.features.sdr.ui

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.radiusrf.core.ui.zd.ZdButton
import app.radiusrf.core.ui.zd.ZdButtonVariant
import app.radiusrf.core.ui.zd.ZdCard
import app.radiusrf.core.ui.zd.ZdIcons
import app.radiusrf.core.ui.zd.ZdListCard
import app.radiusrf.core.ui.zd.ZdListRow
import app.radiusrf.core.ui.zd.ZdSectionLabel
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import app.radiusrf.features.sdr.domain.SdrBands
import app.radiusrf.features.sdr.domain.SdrMode
import app.radiusrf.features.sdr.domain.SdrRadio
import app.radiusrf.features.sdr.domain.SdrState
import app.radiusrf.features.sdr.viewmodel.SdrViewModel
import app.radiusrf.ui.theme.ZdColors
import app.radiusrf.ui.theme.ZdType
import java.util.Locale

@Composable
fun SdrAnalyzerPanel(state: SdrState, viewModel: SdrViewModel) {
    val context = LocalContext.current
    var mhz by remember(state.frequencyHz) {
        mutableStateOf(String.format(Locale.US, "%.4f", state.frequencyHz / 1_000_000.0))
    }
    var bookmarkName by remember { mutableStateOf("") }
    var namingCot by remember { mutableStateOf(false) }
    if (namingCot) {
        app.radiusrf.core.ui.zd.ExportNameDialog(
            initialName = "sdr_detections.cot.xml",
            onDismiss = { namingCot = false },
            onConfirm = { name ->
                namingCot = false
                viewModel.exportCot(name) { uri ->
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/xml"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, name)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Export CoT"))
                }
            }
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ZdCard {
            Text(
                if (state.paused) "Paused" else if (state.listening) state.mode.label else "Ready",
                style = ZdType.Heading,
                color = ZdColors.Accent
            )
            state.listenStatus?.let {
                Text(it, style = ZdType.BodySmall, color = ZdColors.Text2, modifier = Modifier.padding(top = 4.dp))
            }
            OutlinedTextField(
                value = mhz,
                onValueChange = { mhz = it },
                label = { Text("MHz") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            ZdButton(
                "Tune",
                onClick = {
                    mhz.toDoubleOrNull()?.let { viewModel.setFrequency((it * 1_000_000.0).toLong()) }
                },
                variant = ZdButtonVariant.Secondary,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SdrMode.entries.forEach { mode ->
                    ZdButton(
                        mode.label,
                        onClick = { viewModel.setMode(mode) },
                        variant = if (mode == state.mode) ZdButtonVariant.Primary else ZdButtonVariant.Secondary,
                        height = 36.dp
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SdrBands.all.forEach { band ->
                    ZdButton(
                        band.name,
                        onClick = { viewModel.tuneBand(band) },
                        variant = ZdButtonVariant.Secondary,
                        height = 36.dp
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SdrBands.ratesFor(state.radio).forEach { rate ->
                    ZdButton(
                        "${rate / 1_000_000.0} MHz",
                        onClick = { viewModel.setSampleRate(rate) },
                        variant = if (rate == state.sampleRate) ZdButtonVariant.Primary else ZdButtonVariant.Secondary,
                        height = 36.dp
                    )
                }
                SdrBands.fftSizes.forEach { size ->
                    ZdButton(
                        "FFT $size",
                        onClick = { viewModel.setFftSize(size) },
                        variant = if (size == state.fftSize) ZdButtonVariant.Primary else ZdButtonVariant.Secondary,
                        height = 36.dp
                    )
                }
            }
            if (state.radio == SdrRadio.HACKRF) {
                Text("LNA ${state.lna} dB", style = ZdType.BodySmall, color = ZdColors.Text2, modifier = Modifier.padding(top = 8.dp))
                Slider(value = state.lna.toFloat(), onValueChange = { viewModel.setLna(it.toInt()) }, valueRange = 0f..40f, steps = 4)
                Text("VGA ${state.vga} dB", style = ZdType.BodySmall, color = ZdColors.Text2)
                Slider(value = state.vga.toFloat(), onValueChange = { viewModel.setVga(it.toInt()) }, valueRange = 0f..62f, steps = 30)
                ZdButton(
                    if (state.amp) "RF amp on" else "RF amp",
                    onClick = { viewModel.setAmp(!state.amp) },
                    variant = ZdButtonVariant.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                    height = 40.dp
                )
            } else {
                Text(
                    if (state.agc) "AGC" else "Gain ${state.gainDb} dB",
                    style = ZdType.BodySmall,
                    color = ZdColors.Text2,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Slider(
                    value = state.gainDb.toFloat(),
                    onValueChange = { viewModel.setGain(it.toInt()); viewModel.setAgc(false) },
                    valueRange = 0f..50f
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ZdButton(
                        if (state.agc) "AGC on" else "AGC",
                        onClick = { viewModel.setAgc(!state.agc) },
                        variant = ZdButtonVariant.Secondary,
                        modifier = Modifier.weight(1f),
                        height = 40.dp
                    )
                    ZdButton(
                        "PPM ${state.ppm}",
                        onClick = { viewModel.setPpm(state.ppm + 1) },
                        variant = ZdButtonVariant.Secondary,
                        modifier = Modifier.weight(1f),
                        height = 40.dp
                    )
                }
            }
            Text(
                if (state.squelch == 0) "Squelch off" else "Squelch ${state.squelch}",
                style = ZdType.BodySmall,
                color = ZdColors.Text2,
                modifier = Modifier.padding(top = 8.dp)
            )
            Slider(
                value = state.squelch.toFloat(),
                onValueChange = { viewModel.setSquelch(it.toInt()) },
                valueRange = 0f..8000f
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ZdButton(
                    if (state.sweeping) "Stop sweep" else "Sweep bands",
                    onClick = viewModel::toggleSweep,
                    variant = ZdButtonVariant.Secondary,
                    modifier = Modifier.weight(1f),
                    height = 40.dp
                )
                when {
                    state.paused -> {
                        ZdButton("Continue", onClick = viewModel::resumeListen, icon = ZdIcons.Play, modifier = Modifier.weight(1f), height = 40.dp)
                        ZdButton("Stop", onClick = viewModel::stopListening, icon = ZdIcons.Stop, variant = ZdButtonVariant.Danger, modifier = Modifier.weight(1f), height = 40.dp)
                    }
                    state.listening -> {
                        ZdButton("Pause", onClick = viewModel::pauseListen, icon = ZdIcons.Pause, variant = ZdButtonVariant.Secondary, modifier = Modifier.weight(1f), height = 40.dp)
                        ZdButton("Stop", onClick = viewModel::stopListening, icon = ZdIcons.Stop, variant = ZdButtonVariant.Danger, modifier = Modifier.weight(1f), height = 40.dp)
                    }
                    else -> ZdButton("Listen", onClick = viewModel::listen, icon = ZdIcons.Play, modifier = Modifier.weight(1f), height = 40.dp)
                }
            }
        }
        Spectrum(
            bins = state.spectrum,
            sampleRate = state.sampleRate,
            offsetHz = state.offsetHz,
            modifier = Modifier.fillMaxWidth().height(88.dp),
            onScroll = viewModel::nudgeFrequency,
            onListenAt = viewModel::setOffset
        )
        Waterfall(state.waterfall, Modifier.fillMaxWidth().height(140.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ZdButton(
                if (state.recording) "Recording" else "Record IQ",
                onClick = viewModel::toggleRecord,
                variant = ZdButtonVariant.Secondary,
                enabled = state.listening,
                modifier = Modifier.weight(1f),
                height = 40.dp
            )
            ZdButton(
                "Play IQ",
                onClick = viewModel::playRecording,
                variant = ZdButtonVariant.Secondary,
                enabled = state.hasRecording && !state.listening,
                modifier = Modifier.weight(1f),
                height = 40.dp
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ZdButton(
                "GPS L1",
                onClick = viewModel::checkGnss,
                variant = ZdButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
                height = 40.dp
            )
            ZdButton(
                if (state.remoteScanning) "Stop Remote ID" else "Remote ID",
                onClick = viewModel::toggleRemoteId,
                variant = ZdButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
                height = 40.dp
            )
            ZdButton(
                "Export CoT",
                onClick = { namingCot = true },
                icon = ZdIcons.Share,
                variant = ZdButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
                height = 40.dp
            )
        }
        ZdButton(
            if (state.gnssWatch == null) "Phone GNSS" else "Stop phone GNSS",
            onClick = viewModel::toggleGnssWatch,
            variant = ZdButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth(),
            height = 40.dp
        )
        state.gnssWatch?.let {
            Text(it, style = ZdType.BodySmall, color = ZdColors.Text2)
        }
        if (state.remoteIds.isNotEmpty()) {
            ZdSectionLabel("Remote ID", trailingText = "${state.remoteIds.size}")
            ZdListCard(state.remoteIds.take(8)) { sighting ->
                val where = if (sighting.lat != null && sighting.lon != null) {
                    String.format(Locale.US, "%.5f, %.5f", sighting.lat, sighting.lon)
                } else {
                    sighting.address
                }
                ZdListRow(title = sighting.id, subtitle = "$where · ${sighting.rssi} dBm")
            }
        }
        OutlinedTextField(
            value = bookmarkName,
            onValueChange = { bookmarkName = it },
            label = { Text("Bookmark name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        ZdButton(
            "Save bookmark",
            onClick = {
                viewModel.addBookmark(bookmarkName)
                bookmarkName = ""
            },
            variant = ZdButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth()
        )
        if (state.bookmarks.isNotEmpty()) {
            ZdSectionLabel("Bookmarks")
            ZdListCard(state.bookmarks) { bookmark ->
                ZdListRow(
                    title = bookmark.name,
                    subtitle = "${formatMhz(bookmark.hz)} MHz · ${bookmark.mode.label}",
                    onClick = { viewModel.tuneBookmark(bookmark) }
                )
            }
        }
        if (state.detections.isNotEmpty()) {
            ZdSectionLabel("Heard", trailingText = "${state.detections.size}")
            ZdListCard(state.detections.take(8)) { detection ->
                ZdListRow(
                    title = "${formatMhz(detection.hz)} MHz",
                    subtitle = "${detection.mode.label} · ${detection.db.toInt()} dB"
                )
            }
        }
    }
}

@Composable
private fun Spectrum(
    bins: FloatArray,
    sampleRate: Int,
    offsetHz: Long,
    modifier: Modifier,
    onScroll: (Long) -> Unit,
    onListenAt: (Long) -> Unit
) {
    val color = ZdColors.Accent
    val grid = ZdColors.Border
    var origin by remember { mutableStateOf(0f) }
    var dragged by remember { mutableStateOf(0f) }
    Canvas(
        modifier.pointerInput(sampleRate) {
            detectDragGestures(
                onDragStart = {
                    origin = it.x
                    dragged = 0f
                },
                onDragEnd = {
                    if (kotlin.math.abs(dragged) < 12f) {
                        val frac = (origin / size.width).coerceIn(0f, 1f)
                        onListenAt((sampleRate * (frac - 0.5f)).toLong())
                    }
                },
                onDrag = { change, amount ->
                    change.consume()
                    dragged += amount.x
                    val delta = (-amount.x / size.width * sampleRate).toLong()
                    if (delta != 0L) onScroll(delta)
                }
            )
        }
    ) {
        drawRect(grid.copy(alpha = 0.35f))
        if (bins.isEmpty()) return@Canvas
        val shown = if (bins.size <= 512) bins else FloatArray(512) { index ->
            val start = index * bins.size / 512
            val end = ((index + 1) * bins.size / 512).coerceAtMost(bins.size)
            var peak = bins[start]
            for (i in start until end) if (bins[i] > peak) peak = bins[i]
            peak
        }
        val min = shown.min()
        val span = (shown.max() - min).coerceAtLeast(1f)
        val step = size.width / (shown.size - 1).coerceAtLeast(1)
        var previous = Offset(0f, size.height)
        shown.forEachIndexed { index, value ->
            val y = size.height - ((value - min) / span) * size.height
            val point = Offset(index * step, y)
            if (index > 0) drawLine(color, previous, point, strokeWidth = 2f)
            previous = point
        }
        val marker = (size.width * (0.5f + offsetHz.toFloat() / sampleRate.coerceAtLeast(1))).coerceIn(0f, size.width)
        drawLine(Color.White, Offset(marker, 0f), Offset(marker, size.height), strokeWidth = 2f)
    }
}

@Composable
private fun Waterfall(rows: List<FloatArray>, modifier: Modifier) {
    Canvas(modifier) {
        drawRect(Color.Black)
        if (rows.isEmpty()) return@Canvas
        val rowHeight = size.height / rows.size
        val sample = rows.last()
        val columns = sample.size.coerceAtMost(256)
        val min = rows.minOf { it.min() }
        val span = (rows.maxOf { it.max() } - min).coerceAtLeast(1f)
        val colWidth = size.width / columns
        rows.forEachIndexed { y, row ->
            for (x in 0 until columns) {
                val start = x * row.size / columns
                val end = ((x + 1) * row.size / columns).coerceAtMost(row.size)
                var peak = row[start]
                for (i in start until end) if (row[i] > peak) peak = row[i]
                val level = ((peak - min) / span).coerceIn(0f, 1f)
                drawRect(
                    color = waterfallColor(level),
                    topLeft = Offset(x * colWidth, y * rowHeight),
                    size = Size(colWidth + 0.5f, rowHeight + 0.5f)
                )
            }
        }
    }
}

private fun waterfallColor(level: Float): Color {
    val t = level.coerceIn(0f, 1f)
    return Color(red = t, green = t * 0.45f, blue = 0.05f * (1f - t), alpha = 1f)
}

private fun formatMhz(hz: Long): String =
    String.format(Locale.US, "%.4f", hz / 1_000_000.0).trimEnd('0').trimEnd('.')
