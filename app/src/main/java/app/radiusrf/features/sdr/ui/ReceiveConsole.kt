package app.radiusrf.features.sdr.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.radiusrf.core.ui.zd.ZdButton
import app.radiusrf.core.ui.zd.ZdButtonVariant
import app.radiusrf.core.ui.zd.ZdChip
import app.radiusrf.core.ui.zd.ZdChipRow
import app.radiusrf.core.ui.zd.ZdFootnote
import app.radiusrf.core.ui.zd.ZdSectionLabel
import app.radiusrf.features.sdr.domain.AisDecoder
import app.radiusrf.features.sdr.domain.IsmBands
import app.radiusrf.features.sdr.domain.SdrBands
import app.radiusrf.features.sdr.domain.SdrLevel
import app.radiusrf.features.sdr.domain.SdrMode
import app.radiusrf.features.sdr.domain.SdrRadio
import app.radiusrf.features.sdr.domain.SdrState
import app.radiusrf.features.sdr.viewmodel.ReceiveApp
import app.radiusrf.features.sdr.viewmodel.ReceiveHit
import app.radiusrf.features.sdr.viewmodel.ReceiveViewModel
import app.radiusrf.features.sdr.viewmodel.SdrViewModel
import app.radiusrf.features.sdr.viewmodel.readsIq
import app.radiusrf.features.sdr.viewmodel.tune
import app.radiusrf.ui.theme.ZdColors
import app.radiusrf.ui.theme.ZdType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import android.graphics.Bitmap

@Composable
fun ReceiveConsole(
    radio: SdrViewModel,
    modifier: Modifier = Modifier,
    decode: ReceiveViewModel = hiltViewModel()
) {
    val state by radio.state.collectAsState()
    val ui by decode.state.collectAsState()

    LaunchedEffect(Unit) { radio.adoptAttached() }
    LaunchedEffect(state.listening, ui.app) {
        if (state.listening && ui.app.readsIq()) decode.arm() else decode.disarm()
    }
    DisposableEffect(Unit) {
        onDispose { decode.disarm() }
    }
    LaunchedEffect(ui.app) {
        val preset = ui.app.tune() ?: return@LaunchedEffect
        radio.setMode(preset.mode)
        radio.setSampleRate(preset.rate)
        radio.setChannelBandwidth(preset.bandwidth)
        radio.setFrequency(preset.hz)
        radio.setAmp(preset.amp && state.radio == SdrRadio.HACKRF)
        if (state.radio == SdrRadio.HACKRF && preset.amp) {
            radio.setLna(32)
            radio.setVga(32)
        }
    }
    LaunchedEffect(ui.scanning, state.bookmarks) {
        if (!ui.scanning) return@LaunchedEffect
        val marks = state.bookmarks.ifEmpty { SdrBands.stationPlans }
        while (true) {
            for (mark in marks) {
                radio.tuneBookmark(mark)
                delay(400)
                if (SdrLevel.levelDb.value > -50f) delay(2_500)
            }
        }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(mhz(state.frequencyHz), style = ZdType.Heading, color = ZdColors.Text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        Text(
            listOfNotNull(state.listenStatus, ui.note).joinToString(" · "),
            style = ZdType.Caption,
            color = ZdColors.Text3,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZdButton(
                if (state.listening) "Stop" else "Listen",
                onClick = {
                    if (state.listening) radio.stopListening()
                    else if (state.connectedVidPid == null) state.devices.firstOrNull { it.canListen }?.let { radio.connect(it) }
                    else radio.listen()
                },
                modifier = Modifier.weight(1f)
            )
            ZdButton("Step", onClick = { radio.nudgeFrequency(state.channelBandwidthHz.toLong()) }, variant = ZdButtonVariant.Secondary, modifier = Modifier.weight(1f))
        }
        if (state.devices.isNotEmpty()) {
            ZdChipRow {
                state.devices.filter { it.canListen }.forEach { device ->
                    ZdChip(device.chipset, device.vidPid == state.connectedVidPid, onClick = { radio.connect(device) })
                }
            }
        }
        ZdChipRow {
            ReceiveApp.entries.forEach { app ->
                ZdChip(app.label, ui.app == app, onClick = { decode.select(app) })
            }
        }
        Waterfall(
            radio = radio,
            modifier = Modifier.fillMaxWidth().height(220.dp).padding(horizontal = 12.dp)
        ) { fraction ->
            val span = state.sampleRate.toLong()
            radio.setFrequency(state.frequencyHz + ((fraction - 0.5f) * span).toLong())
        }
        when (ui.app) {
            ReceiveApp.AUDIO -> AudioPane(state, radio)
            ReceiveApp.SCAN -> ScanPane(ui.scanning, decode)
            ReceiveApp.GLASS -> Column(Modifier.padding(horizontal = 16.dp)) {
                ZdButton(if (state.sweeping) "Stop sweep" else "Sweep bands", onClick = { radio.toggleSweep() })
                ZdFootnote("Each stop is a band from the list. The waterfall keeps the latest span.")
            }
            ReceiveApp.SHIPS -> ZdChipRow {
                ZdChip("AIS B", state.frequencyHz == AisDecoder.CHANNEL_B, onClick = { radio.setFrequency(AisDecoder.CHANNEL_B) })
                ZdChip("AIS A", state.frequencyHz == AisDecoder.CHANNEL_A, onClick = { radio.setFrequency(AisDecoder.CHANNEL_A) })
            }
            ReceiveApp.PAGER -> PagerPane(ui.pagerBaud, state.frequencyHz, decode, radio)
            ReceiveApp.SUBGHZ -> ZdChipRow {
                IsmBands.all.forEach { band ->
                    ZdChip(band.name, abs(state.frequencyHz - band.hz) < 500_000, onClick = { radio.setFrequency(band.hz) })
                }
            }
            ReceiveApp.FT8 -> ZdChipRow {
                listOf(7_074_000L to "40 m", 14_074_000L to "20 m", 21_074_000L to "15 m").forEach { (hz, label) ->
                    ZdChip(label, state.frequencyHz == hz, onClick = {
                        radio.setMode(SdrMode.USB)
                        radio.setFrequency(hz)
                    })
                }
            }
            ReceiveApp.DRONES -> Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ZdButton(if (state.remoteScanning) "Stop Remote ID" else "Listen for Remote ID", onClick = { radio.toggleRemoteId() })
                state.remoteIds.forEach { sight ->
                    Text("${sight.id}  ${sight.rssi} dBm", style = ZdType.Body, color = ZdColors.Text)
                }
            }
            ReceiveApp.CAPTURE -> CapturePane(state.recording, state.recordings.map { it.name to it.path }, radio)
            else -> Unit
        }
        if (ui.hits.isNotEmpty()) {
            ZdSectionLabel("Heard", trailingText = ui.hits.size.toString())
            Plot(ui.hits)
            ui.hits.take(12).forEach { hit ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(hit.title, style = ZdType.Body, color = ZdColors.Text)
                    Text(hit.detail, style = ZdType.Caption, color = ZdColors.Text3)
                }
            }
        }
        state.connectionError?.let { ZdFootnote(it) }
    }
}

@Composable
private fun AudioPane(state: SdrState, radio: SdrViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ZdChipRow {
            listOf(SdrMode.WFM, SdrMode.NFM, SdrMode.AM, SdrMode.USB, SdrMode.LSB, SdrMode.CW).forEach { item ->
                ZdChip(item.label, state.mode == item, onClick = { radio.setMode(item) })
            }
        }
        ZdChipRow {
            ZdChip("Squelch off", state.squelch == 0, onClick = { radio.setSquelch(0) })
            ZdChip("Squelch", state.squelch > 0, onClick = { radio.setSquelch(3500) })
            ZdChip("AGC", state.agc, onClick = { radio.setAgc(!state.agc) })
            ZdChip("Gain +", false, onClick = { radio.setGain(state.gainDb + 2) })
            ZdChip("Gain −", false, onClick = { radio.setGain(state.gainDb - 2) })
            ZdChip("Bias tee", state.biasTee, onClick = { radio.setBiasTee(!state.biasTee) })
        }
        if (state.radio == SdrRadio.HACKRF) {
            ZdChipRow {
                ZdChip("LNA ${state.lna}", false, onClick = { radio.setLna(if (state.lna >= 40) 0 else state.lna + 8) })
                ZdChip("VGA ${state.vga}", false, onClick = { radio.setVga(if (state.vga >= 62) 0 else state.vga + 2) })
                ZdChip("Amp", state.amp, onClick = { radio.setAmp(!state.amp) })
            }
        }
        FrequencyField { radio.setFrequency(it) }
        ZdFootnote("Drag the waterfall to retune. Audio follows the mode chip. Gain, bias tee, and the HackRF amp stay on this page.")
    }
}

@Composable
private fun ScanPane(scanning: Boolean, decode: ReceiveViewModel) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        ZdButton(if (scanning) "Stop scan" else "Scan", onClick = { decode.setScanning(!scanning) })
        ZdFootnote("Uses saved bookmarks, or the built-in band list when none are saved. It holds on a signal above the noise.")
    }
}

@Composable
private fun PagerPane(baud: Int, hz: Long, decode: ReceiveViewModel, radio: SdrViewModel) {
    Column {
        ZdChipRow {
            listOf(512, 1200, 2400).forEach { rate ->
                ZdChip("$rate", baud == rate, onClick = { decode.setPagerBaud(rate) })
            }
        }
        ZdChipRow {
            listOf(152_180_000L to "152.180", 454_000_000L to "454.000", 929_612_500L to "929.6125").forEach { (freq, label) ->
                ZdChip(label, hz == freq, onClick = { radio.setFrequency(freq) })
            }
        }
    }
}

@Composable
private fun CapturePane(recording: Boolean, files: List<Pair<String, String>>, radio: SdrViewModel) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ZdButton(if (recording) "Stop recording" else "Record IQ", onClick = { radio.toggleRecord() })
        files.forEach { (name, path) ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZdButton(name, onClick = { radio.playRecording(path) }, variant = ZdButtonVariant.Secondary, modifier = Modifier.weight(1f))
                ZdButton("Delete", onClick = { radio.deleteRecording(path) }, variant = ZdButtonVariant.Ghost)
            }
        }
    }
}

@Composable
private fun FrequencyField(onTune: (Long) -> Unit) {
    var text by remember { androidx.compose.runtime.mutableStateOf("") }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        label = { Text("MHz") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        trailingIcon = {
            ZdButton("Tune", onClick = {
                val mhz = text.toDoubleOrNull() ?: return@ZdButton
                onTune((mhz * 1_000_000.0).toLong())
            }, variant = ZdButtonVariant.Ghost, height = 36.dp)
        }
    )
}

@Composable
private fun Waterfall(radio: SdrViewModel, modifier: Modifier, onRetune: (Float) -> Unit) {
    val rows by radio.liveWaterfall.collectAsState()
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(rows) {
        frame = if (rows.isEmpty()) null else withContext(Dispatchers.Default) { paintWaterfall(rows) }
    }
    var drag by remember { mutableFloatStateOf(0f) }
    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTapGestures { offset -> onRetune(offset.x / size.width) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, amount ->
                    drag += amount
                    if (abs(drag) > 24f) {
                        onRetune(0.5f + drag / size.width)
                        drag = 0f
                    }
                    change.consume()
                }
            }
    ) {
        val image = frame
        if (image == null) {
            drawRect(Color(0xFF101820))
        } else {
            drawImage(
                image,
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
            )
        }
    }
}

private fun paintWaterfall(rows: List<FloatArray>): ImageBitmap {
    val width = rows.firstOrNull()?.size?.coerceIn(1, 320) ?: 1
    val height = rows.size.coerceIn(1, 48)
    val pixels = IntArray(width * height)
    var y = 0
    while (y < height && y < rows.size) {
        val row = rows[y]
        var x = 0
        while (x < width) {
            val db = if (x < row.size) row[x] else -120f
            pixels[y * width + x] = heatArgb(db)
            x++
        }
        y++
    }
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap.asImageBitmap()
}

@Composable
private fun Plot(hits: List<ReceiveHit>) {
    val placed = hits.filter { it.lat != null && it.lon != null }
    if (placed.isEmpty()) return
    Canvas(Modifier.fillMaxWidth().height(140.dp).padding(horizontal = 16.dp)) {
        drawRect(Color(0xFF101820))
        val lats = placed.map { it.lat!! }
        val lons = placed.map { it.lon!! }
        val south = lats.min()
        val north = lats.max()
        val west = lons.min()
        val east = lons.max()
        val latSpan = (north - south).takeIf { it > 0.0001 } ?: 0.01
        val lonSpan = (east - west).takeIf { it > 0.0001 } ?: 0.01
        placed.forEach { hit ->
            val x = ((hit.lon!! - west) / lonSpan).toFloat() * size.width
            val y = ((north - hit.lat!!) / latSpan).toFloat() * size.height
            drawCircle(Color(0xFFE2B15A), 6.dp.toPx(), Offset(x, y))
        }
    }
}

private fun heatArgb(db: Float): Int {
    val t = ((db + 90f) / 70f).coerceIn(0f, 1f)
    val r = (20 + 220 * t).toInt()
    val g = (24 + 140 * t * t).toInt()
    val b = (32 * (1f - t)).toInt()
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

private fun mhz(hz: Long): String = String.format(Locale.US, "%.5f MHz", hz / 1_000_000.0)
