package app.radiusrf.features.sdr.ui

import android.content.Intent
import android.content.res.Configuration
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.radiusrf.core.ui.zd.ExportNameDialog
import app.radiusrf.core.ui.zd.ZdButton
import app.radiusrf.core.ui.zd.ZdButtonVariant
import app.radiusrf.core.ui.zd.ZdFootnote
import app.radiusrf.core.ui.zd.ZdIcons
import app.radiusrf.core.ui.zd.ZdListCard
import app.radiusrf.core.ui.zd.ZdListRow
import app.radiusrf.core.ui.zd.ZdSectionLabel
import app.radiusrf.features.sdr.domain.DirectSampling
import app.radiusrf.features.sdr.domain.IqFileFormat
import app.radiusrf.features.sdr.domain.SdrBands
import app.radiusrf.features.sdr.domain.SdrBookmark
import app.radiusrf.features.sdr.domain.SdrLevel
import app.radiusrf.features.sdr.domain.SdrMode
import app.radiusrf.features.sdr.domain.SdrRadio
import app.radiusrf.features.sdr.domain.SdrDsp
import app.radiusrf.features.sdr.domain.SdrState
import app.radiusrf.features.sdr.viewmodel.SdrViewModel
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first

private enum class AnalyzerTab(val label: String) {
    SOURCE("Source"),
    DEMOD("Demodulation"),
    RECORD("Recording"),
    DISPLAY("Display"),
    SETTINGS("Settings"),
    ABOUT("About")
}

private enum class ColorMap(val label: String) { JET("Jet"), TURBO("Turbo"), GQRX("GQRX") }
private enum class DrawStyle(val label: String) { LINE("Line"), BARS("Bars") }
private enum class PlotFont(val label: String, val sp: Float) { SMALL("Small", 9f), MEDIUM("Medium", 11f), LARGE("Large", 14f) }

private val PlotBlack = Color(0xFF000000)
private val ChannelOrange = Color(0xFFFF9800)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SdrWorkbench(state: SdrState, viewModel: SdrViewModel, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var drawerDp by rememberSaveable { mutableFloatStateOf(if (landscape) 340f else 300f) }
    var drawerOnRight by rememberSaveable { mutableStateOf(true) }
    var spectrumShare by rememberSaveable { mutableFloatStateOf(0.42f) }
    var dbFloor by rememberSaveable { mutableFloatStateOf(-100f) }
    var dbCeil by rememberSaveable { mutableFloatStateOf(0f) }
    var fitted by rememberSaveable { mutableStateOf(false) }
    var peakEpoch by remember { mutableIntStateOf(0) }
    var fitRequest by remember { mutableIntStateOf(0) }
    var viewportZoom by rememberSaveable { mutableFloatStateOf(1f) }
    var autoRate by rememberSaveable { mutableStateOf(true) }
    var useRtlTcp by rememberSaveable { mutableStateOf(false) }
    var squelchOn by rememberSaveable { mutableStateOf(state.squelch > 0) }
    val bandwidthHz = state.channelBandwidthHz
    var converterHz by rememberSaveable { mutableLongStateOf(0L) }
    var useFile by rememberSaveable { mutableStateOf(false) }
    var relative by rememberSaveable { mutableStateOf(false) }
    var peakHold by rememberSaveable { mutableStateOf(false) }
    var averageCount by rememberSaveable { mutableIntStateOf(1) }
    var colorMap by rememberSaveable { mutableStateOf(ColorMap.JET) }
    var drawStyle by rememberSaveable { mutableStateOf(DrawStyle.LINE) }
    var font by rememberSaveable { mutableStateOf(PlotFont.MEDIUM) }
    var reverseWheel by rememberSaveable { mutableStateOf(false) }
    var keepCentered by rememberSaveable { mutableStateOf(false) }
    var showDebug by rememberSaveable { mutableStateOf(false) }
    var volume by rememberSaveable { mutableFloatStateOf(1f) }
    var muted by rememberSaveable { mutableStateOf(false) }
    var showBookmarks by rememberSaveable { mutableStateOf(false) }
    val displayedCenter = state.frequencyHz + converterHz
    val channelHz = displayedCenter + state.offsetHz

    LaunchedEffect(volume, muted) { viewModel.setAudioGain(if (muted) 0f else volume) }

    MaterialTheme(colorScheme = analyzerColors()) {
        val plots = @Composable { plotModifier: Modifier ->
            Box(plotModifier) {
                LiveAnalyzerPlots(
                    state = state,
                    viewModel = viewModel,
                    spectrumShare = spectrumShare,
                    dbFloor = dbFloor,
                    dbCeil = dbCeil,
                    zoom = viewportZoom,
                    bandwidthHz = bandwidthHz,
                    converterHz = converterHz,
                    relative = relative,
                    bars = drawStyle == DrawStyle.BARS,
                    colorMap = colorMap,
                    averageCount = averageCount,
                    peakHold = peakHold,
                    peakEpoch = peakEpoch,
                    fitRequest = fitRequest,
                    fitted = fitted,
                    onFit = { floor, ceil ->
                        dbFloor = floor
                        dbCeil = ceil
                        fitted = true
                    },
                    squelchOn = squelchOn,
                    fontSp = font.sp,
                    showDebug = showDebug,
                    keepCentered = keepCentered,
                    onShiftDb = { delta ->
                        dbFloor += delta
                        dbCeil += delta
                    },
                    onZoomDb = { factor ->
                        val mid = (dbFloor + dbCeil) / 2f
                        val half = ((dbCeil - dbFloor) / 2f / factor).coerceIn(4f, 80f)
                        dbFloor = mid - half
                        dbCeil = mid + half
                    },
                    onZoomView = { factor ->
                        if (autoRate) {
                            if (factor > 1.12f) viewModel.zoomSpan(true)
                            else if (factor < 0.88f) viewModel.zoomSpan(false)
                        } else {
                            viewportZoom = (viewportZoom * factor).coerceIn(1f, 16f)
                        }
                    },
                    onBandwidth = { viewModel.setChannelBandwidth(it) },
                    onSquelch = {
                        squelchOn = true
                        viewModel.setSquelch(it)
                    },
                    onViewportFit = { center, span ->
                        viewModel.retuneViewport(center, span)
                        viewportZoom = 1f
                    },
                    modifier = Modifier.fillMaxSize()
                )
                Column(
                    Modifier.align(Alignment.BottomEnd).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FloatingActionButton(onClick = { showBookmarks = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "Bookmarks")
                    }
                }
            }
        }
        val drawer = @Composable { drawerModifier: Modifier ->
            AnalyzerDrawer(
                tab = tab,
                onTab = { tab = it },
                landscape = landscape,
                drawerOnRight = drawerOnRight,
                onDrag = { drag ->
                    val next = drawerDp + drag / density.density
                    drawerDp = next.coerceIn(if (landscape) 240f else 150f, if (landscape) 520f else 560f)
                },
                modifier = drawerModifier
            ) {
                when (AnalyzerTab.entries[tab]) {
                    AnalyzerTab.SOURCE -> SourceTab(
                        state, viewModel, useFile, useRtlTcp, converterHz, autoRate, displayedCenter,
                        onUseFile = { useFile = it },
                        onUseRtlTcp = { useRtlTcp = it },
                        onConverter = { converterHz = it },
                        onAutoRate = { autoRate = it }
                    )
                    AnalyzerTab.DEMOD -> DemodTab(
                        state, viewModel, channelHz, bandwidthHz, viewportZoom, squelchOn, volume, muted,
                        reverseWheel, keepCentered, autoRate,
                        onBandwidth = { viewModel.setChannelBandwidth(it) },
                        onZoom = { viewportZoom = it },
                        onSquelchOn = {
                            squelchOn = it
                            viewModel.setSquelchDb(if (it) -80f else -120f, it)
                        },
                        onVolume = { volume = it },
                        onMuted = { muted = it },
                        onKeepCentered = { keepCentered = it },
                        onBookmarks = { showBookmarks = true }
                    )
                    AnalyzerTab.RECORD -> RecordTab(state, viewModel, squelchOn)
                    AnalyzerTab.DISPLAY -> DisplayTab(
                        state, viewModel, dbFloor, dbCeil, spectrumShare, averageCount, peakHold, relative,
                        colorMap, drawStyle,
                        onFloor = { dbFloor = it; fitted = true },
                        onCeil = { dbCeil = it; fitted = true },
                        onShare = { spectrumShare = it },
                        onAverage = { averageCount = it },
                        onPeak = {
                            peakHold = it
                            peakEpoch++
                        },
                        onRelative = { relative = it },
                        onMap = { colorMap = it },
                        onDraw = { drawStyle = it },
                        onAutoscale = { fitRequest++ },
                        onReset = {
                            dbFloor = -100f
                            dbCeil = 0f
                            viewportZoom = 1f
                            fitted = true
                        }
                    )
                    AnalyzerTab.SETTINGS -> SettingsTab(
                        state, viewModel, font, reverseWheel, drawerOnRight, showDebug,
                        onFont = { font = it },
                        onReverse = { reverseWheel = it },
                        onDrawerSide = { drawerOnRight = it },
                        onDebug = { showDebug = it }
                    )
                    AnalyzerTab.ABOUT -> AboutTab()
                }
            }
        }

        if (landscape) {
            Row(modifier.background(PlotBlack)) {
                if (!drawerOnRight) drawer(Modifier.width(drawerDp.dp).fillMaxHeight())
                plots(Modifier.weight(1f).fillMaxHeight())
                if (drawerOnRight) drawer(Modifier.width(drawerDp.dp).fillMaxHeight())
            }
        } else {
            Column(modifier.background(PlotBlack)) {
                plots(Modifier.weight(1f).fillMaxWidth())
                drawer(Modifier.fillMaxWidth().height(drawerDp.dp))
            }
        }
    }

    if (showBookmarks) {
        BookmarkSheet(state, viewModel, channelHz, onDismiss = { showBookmarks = false })
    }
}

@Composable
private fun analyzerColors() = darkColorScheme(
    primary = Color(0xFF9FC9FF),
    onPrimary = Color(0xFF00315B),
    primaryContainer = Color(0xFF0D497C),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFFBBC7DB),
    surface = Color(0xFF10141A),
    onSurface = Color(0xFFDFE2EB),
    surfaceVariant = Color(0xFF1C222B),
    onSurfaceVariant = Color(0xFFC3C6CF),
    outline = Color(0xFF8D9199),
    background = Color(0xFF10141A)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnalyzerDrawer(
    tab: Int,
    onTab: (Int) -> Unit,
    landscape: Boolean,
    drawerOnRight: Boolean,
    onDrag: (Float) -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    val body = @Composable { bodyModifier: Modifier ->
        Column(bodyModifier) {
            PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                AnalyzerTab.entries.forEachIndexed { index, item ->
                    Tab(selected = tab == index, onClick = { onTab(index) }, text = { Text(item.label, maxLines = 1) })
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = { content() }
            )
        }
    }
    if (landscape) {
        Row(modifier.background(MaterialTheme.colorScheme.surface)) {
            if (drawerOnRight) DrawerHandle(horizontal = true, growWhenNegative = true, onDrag = onDrag)
            body(Modifier.weight(1f).fillMaxHeight())
            if (!drawerOnRight) DrawerHandle(horizontal = true, growWhenNegative = false, onDrag = onDrag)
        }
    } else {
        Column(modifier.background(MaterialTheme.colorScheme.surface)) {
            DrawerHandle(horizontal = false, growWhenNegative = false, onDrag = onDrag)
            body(Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun DrawerHandle(horizontal: Boolean, growWhenNegative: Boolean, onDrag: (Float) -> Unit) {
    Box(
        Modifier
            .then(if (horizontal) Modifier.fillMaxHeight().width(22.dp) else Modifier.fillMaxWidth().height(22.dp))
            .pointerInput(horizontal, growWhenNegative) {
                if (horizontal) {
                    detectHorizontalDragGestures { change, drag ->
                        change.consume()
                        onDrag(if (growWhenNegative) -drag else drag)
                    }
                } else {
                    detectVerticalDragGestures { change, drag ->
                        change.consume()
                        onDrag(-drag)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .then(if (horizontal) Modifier.width(4.dp).height(36.dp) else Modifier.width(36.dp).height(4.dp))
                .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))
        )
    }
}

@Composable
private fun SourceTab(
    state: SdrState,
    viewModel: SdrViewModel,
    useFile: Boolean,
    useRtlTcp: Boolean,
    converterHz: Long,
    autoRate: Boolean,
    displayedHz: Long,
    onUseFile: (Boolean) -> Unit,
    onUseRtlTcp: (Boolean) -> Unit,
    onConverter: (Long) -> Unit,
    onAutoRate: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else "import.iq"
            } ?: "import.iq"
        viewModel.importIq(uri, name)
    }
    val sources = listOf("RTL-SDR", "HackRF", "Airspy", "Airspy HF+", "HydraSDR", "File")
    val selected = if (useFile) "File" else when (state.radio) {
        SdrRadio.HACKRF -> "HackRF"
        SdrRadio.AIRSPY -> "Airspy"
        SdrRadio.AIRSPYHF -> "Airspy HF+"
        SdrRadio.HYDRA -> "HydraSDR"
        else -> "RTL-SDR"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AnalyzerMenu(
            label = "Source",
            value = selected,
            options = sources,
            modifier = Modifier.weight(1f),
            onSelect = { name ->
                onUseFile(name == "File")
                onUseRtlTcp(false)
                val radio = when (name) {
                    "HackRF" -> SdrRadio.HACKRF
                    "Airspy" -> SdrRadio.AIRSPY
                    "Airspy HF+" -> SdrRadio.AIRSPYHF
                    "HydraSDR" -> SdrRadio.HYDRA
                    "RTL-SDR" -> SdrRadio.RTL
                    else -> null
                }
                if (radio != null) viewModel.selectRadio(radio)
            }
        )
        FilledIconButton(onClick = {
            if (state.listening) viewModel.stopListening()
            else if (useFile) viewModel.playRecording()
            else if (useRtlTcp) viewModel.listenRtlTcp()
            else viewModel.adoptAttached()
        }) {
            Icon(
                if (state.listening) ZdIcons.Stop else ZdIcons.Play,
                contentDescription = if (state.listening) "Stop" else "Start"
            )
        }
    }
    state.listenStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    state.connectionError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    if (useFile) {
        Text("Replay a recorded IQ file. Frequency, sample rate, and format cannot change while it is playing.", style = MaterialTheme.typography.bodySmall)
        ZdButton("Open file", onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth())
        AnalyzerMenu(
            "File format",
            state.fileFormat.label,
            IqFileFormat.entries,
            optionLabel = { it.label },
            onSelect = viewModel::setFileFormat
        )
        FrequencyField("Tune frequency", displayedHz) { hz -> viewModel.setFrequency(hz - converterHz) }
        AnalyzerSwitch("Repeat", state.fileRepeat, viewModel::setFileRepeat)
        Text(if (state.hasRecording) "${state.recordings.size} recording(s) ready." else "No capture yet.", style = MaterialTheme.typography.bodySmall)
    } else {
        FrequencyField("Tune frequency", displayedHz) { hz -> viewModel.setFrequency(hz - converterHz) }
        AnalyzerMenu(
            label = "Sample rate",
            value = rateLabel(state.sampleRate),
            options = SdrBands.ratesFor(state.radio).toList(),
            optionLabel = { rateLabel(it) },
            onSelect = viewModel::setSampleRate
        )
        AnalyzerSwitch("Automatic sample rate", autoRate, onAutoRate)
        Text("When this is on, pinch-to-zoom changes the receiver sample rate so the plot matches the captured span.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        GainBlock(state, viewModel)
        if (state.radio == SdrRadio.RTL || state.radio == SdrRadio.NONE) {
            AnalyzerSwitch("External RTL-SDR server", useRtlTcp, onUseRtlTcp)
            if (useRtlTcp) {
                RtlTcpFields(state, viewModel)
            }
            AnalyzerMenu(
                "Direct sampling",
                state.directSampling.label,
                DirectSampling.entries,
                optionLabel = { it.label },
                onSelect = viewModel::setDirectSampling
            )
        }
        if (state.radio != SdrRadio.NONE) {
            AnalyzerSwitch("Antenna port power (bias-tee)", state.biasTee, viewModel::setBiasTee)
        }
        FrequencyField("Frequency converter offset", converterHz, onConverter)
    }
}

@Composable
private fun RtlTcpFields(state: SdrState, viewModel: SdrViewModel) {
    var host by remember(state.rtlTcpHost) { mutableStateOf(state.rtlTcpHost) }
    var port by remember(state.rtlTcpPort) { mutableStateOf(state.rtlTcpPort.toString()) }
    OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Server host") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(
        value = port,
        onValueChange = { port = it.filter { ch -> ch.isDigit() } },
        label = { Text("TCP port") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
    TextButton(onClick = { viewModel.setRtlTcp(host, port.toIntOrNull() ?: 1234) }) { Text("Apply server") }
    Text("Stream IQ from rtl_tcp over Wi-Fi. This uses several megabytes per second.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun GainBlock(state: SdrState, viewModel: SdrViewModel) {
    when (state.radio) {
        SdrRadio.HACKRF -> {
            Text("LNA ${state.lna} dB")
            Slider(value = state.lna.toFloat(), onValueChange = { viewModel.setLna(it.toInt()) }, valueRange = 0f..40f, steps = 4)
            Text("VGA ${state.vga} dB")
            Slider(value = state.vga.toFloat(), onValueChange = { viewModel.setVga(it.toInt()) }, valueRange = 0f..62f, steps = 30)
            AnalyzerSwitch("Amplifier", state.amp, viewModel::setAmp)
        }
        SdrRadio.AIRSPY, SdrRadio.HYDRA -> {
            val linearity = state.gainDb.coerceIn(0, 21)
            Text(if (state.agc) "Automatic gain" else "Linearity $linearity")
            Slider(value = linearity.toFloat(), onValueChange = { viewModel.setGain(it.toInt()); viewModel.setAgc(false) }, valueRange = 0f..21f)
            AnalyzerSwitch("Automatic gain", state.agc, viewModel::setAgc)
        }
        SdrRadio.AIRSPYHF -> {
            val attenuation = (state.gainDb.coerceIn(0, 48) / 6) * 6
            AnalyzerSwitch("Automatic gain", state.agc, viewModel::setAgc)
            if (!state.agc) {
                Text("Attenuation $attenuation dB")
                Slider(value = attenuation.toFloat(), onValueChange = { viewModel.setGain(it.toInt()) }, valueRange = 0f..48f, steps = 7)
            }
            AnalyzerSwitch("LNA preamp", state.amp, viewModel::setAmp)
        }
        else -> {
            val gain = state.gainDb.coerceIn(0, 50)
            AnalyzerSwitch("Hardware AGC", state.agc, viewModel::setAgc)
            if (!state.agc) {
                Text("Gain $gain dB")
                Slider(value = gain.toFloat(), onValueChange = { viewModel.setGain(it.toInt()) }, valueRange = 0f..50f)
            }
            Text("Frequency correction ${state.ppm} ppm")
            Slider(value = state.ppm.toFloat(), onValueChange = { viewModel.setPpm(it.toInt()) }, valueRange = -100f..100f)
        }
    }
}

@Composable
private fun DemodTab(
    state: SdrState,
    viewModel: SdrViewModel,
    channelHz: Long,
    bandwidthHz: Int,
    zoom: Float,
    squelchOn: Boolean,
    volume: Float,
    muted: Boolean,
    reverseWheel: Boolean,
    keepCentered: Boolean,
    autoRate: Boolean,
    onBandwidth: (Int) -> Unit,
    onZoom: (Float) -> Unit,
    onSquelchOn: (Boolean) -> Unit,
    onVolume: (Float) -> Unit,
    onMuted: (Boolean) -> Unit,
    onKeepCentered: (Boolean) -> Unit,
    onBookmarks: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(modeName(state.mode), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        AnalyzerMenu(
            label = "Mode",
            value = modeName(state.mode),
            options = listOf(SdrMode.AM, SdrMode.NFM, SdrMode.WFM, SdrMode.LSB, SdrMode.USB, SdrMode.CW),
            optionLabel = { modeName(it) },
            onSelect = viewModel::setMode,
            modifier = Modifier.weight(1f)
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledIconButton(onClick = { onMuted(!muted) }) {
            Icon(
                if (muted) ZdIcons.VolumeOff else ZdIcons.Volume,
                contentDescription = if (muted) "Unmute" else "Mute"
            )
        }
        Text(if (muted) "Muted" else "${(volume * 100).roundToInt()}%", modifier = Modifier.padding(start = 8.dp))
        TextButton(onClick = onBookmarks) { Text("Bookmarks") }
    }
    if (!muted) {
        Slider(value = volume, onValueChange = onVolume, valueRange = 0f..1f)
    }
    FrequencyField("Channel frequency", channelHz) { viewModel.tuneChannel(it, keepCentered) }
    Text("Tuning wheel", style = MaterialTheme.typography.labelLarge)
    TuningWheel(reverse = reverseWheel) { steps ->
        viewModel.tuneChannel(channelHz + steps * wheelStep(state.mode), keepCentered)
    }
    Text(if (autoRate) "Zoom  ${"%.1f".format(Locale.US, zoom)}×  ·  pinch also changes sample rate" else "Zoom  ${"%.1f".format(Locale.US, zoom)}×")
    Slider(value = zoom, onValueChange = onZoom, valueRange = 1f..16f)
    Text("Channel bandwidth  ${formatHzPlain(bandwidthHz.toLong())}")
    Slider(
        value = bandwidthHz.toFloat().coerceIn(100f, (state.sampleRate / 2f).coerceAtLeast(200f)),
        onValueChange = { onBandwidth(it.toInt()) },
        valueRange = 100f..(state.sampleRate / 2f).coerceAtLeast(200f)
    )
    AnalyzerSwitch("Squelch", squelchOn, onSquelchOn)
    if (squelchOn) {
        val db = -120f + (state.squelch / 8000f) * 120f
        Text("Squelch  ${db.toInt()} dB")
        Slider(
            value = db,
            onValueChange = { viewModel.setSquelchDb(it, true) },
            valueRange = -120f..0f
        )
        Text("The indicator on the channel marker is green when audio is open.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    AnalyzerSwitch("Keep channel centered", keepCentered, onKeepCentered)
    Text(modeHelp(state.mode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RecordTab(state: SdrState, viewModel: SdrViewModel, squelchOn: Boolean) {
    val context = LocalContext.current
    var stopAfter by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(state.recording, stopAfter) {
        if (state.recording && stopAfter > 0) {
            kotlinx.coroutines.delay(stopAfter * 1000L)
            if (state.recording) viewModel.toggleRecord()
        }
    }
    Text(if (state.recording) "Recording" else if (state.hasRecording) "Recordings" else "No capture yet", style = MaterialTheme.typography.titleMedium)
    ZdButton(
        if (state.recording) "Stop recording" else "Start recording",
        onClick = viewModel::toggleRecord,
        enabled = state.listening || state.recording,
        modifier = Modifier.fillMaxWidth()
    )
    AnalyzerMenu(
        label = "Stop after",
        value = if (stopAfter == 0) "Disabled" else "$stopAfter seconds",
        options = listOf(0, 10, 30, 60, 120, 300),
        optionLabel = { if (it == 0) "Disabled" else "$it seconds" },
        onSelect = { stopAfter = it }
    )
    AnalyzerSwitch("Record only when squelch is open", state.recordOnSquelch && squelchOn, {
        viewModel.setRecordOnSquelch(it)
    })
    Text(
        "Raw IQ is stored in the app. File names include frequency and sample rate so they can be reopened later.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    state.recordings.forEach { recording ->
        ZdListCard(listOf(recording)) {
            ZdListRow(
                title = recording.name,
                subtitle = "${formatHzPlain(recording.frequencyHz)} · ${rateLabel(recording.sampleRate)} · ${recording.bytes / 1024} KB"
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { viewModel.playRecording(recording.path) }, enabled = !state.listening) { Text("Play") }
                TextButton(onClick = {
                    viewModel.shareRecording(recording.path) { uri ->
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "application/octet-stream"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                },
                                "Share recording"
                            )
                        )
                    }
                }) { Text("Share") }
                TextButton(onClick = { viewModel.deleteRecording(recording.path) }) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun DisplayTab(
    state: SdrState,
    viewModel: SdrViewModel,
    dbFloor: Float,
    dbCeil: Float,
    spectrumShare: Float,
    averageCount: Int,
    peakHold: Boolean,
    relative: Boolean,
    colorMap: ColorMap,
    drawStyle: DrawStyle,
    onFloor: (Float) -> Unit,
    onCeil: (Float) -> Unit,
    onShare: (Float) -> Unit,
    onAverage: (Int) -> Unit,
    onPeak: (Boolean) -> Unit,
    onRelative: (Boolean) -> Unit,
    onMap: (ColorMap) -> Unit,
    onDraw: (DrawStyle) -> Unit,
    onAutoscale: () -> Unit,
    onReset: () -> Unit
) {
    Text("Vertical scale  ${dbFloor.toInt()} to ${dbCeil.toInt()} dB")
    Text("Lower limit ${dbFloor.toInt()} dB")
    Slider(value = dbFloor, onValueChange = { if (it < dbCeil - 4f) onFloor(it) }, valueRange = -140f..0f)
    Text("Upper limit ${dbCeil.toInt()} dB")
    Slider(value = dbCeil, onValueChange = { if (it > dbFloor + 4f) onCeil(it) }, valueRange = -80f..20f)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onAutoscale) { Text("Autoscale") }
        TextButton(onClick = onReset) { Text("Reset scale") }
    }
    AnalyzerMenu(
        label = "FFT size",
        value = state.fftSize.toString(),
        options = SdrBands.fftSizes.toList(),
        optionLabel = { it.toString() },
        onSelect = viewModel::setFftSize
    )
    Text("Waterfall speed  ${state.waterfallFps} fps")
    Slider(
        value = state.waterfallFps.toFloat(),
        onValueChange = { viewModel.setWaterfallFps(it.roundToInt()) },
        valueRange = 1f..30f,
        steps = 28
    )
    Text(if (averageCount <= 1) "Averaging off" else "Averaging $averageCount frames")
    Slider(value = averageCount.toFloat(), onValueChange = { onAverage(it.roundToInt()) }, valueRange = 1f..30f, steps = 28)
    AnalyzerSwitch("Peak hold", peakHold, onPeak)
    AnalyzerSwitch("Relative frequency", relative, onRelative)
    Text("Spectrum / waterfall")
    Slider(value = spectrumShare, onValueChange = onShare, valueRange = 0.2f..0.8f)
    AnalyzerMenu("Waterfall color map", colorMap.label, ColorMap.entries, optionLabel = { it.label }, onSelect = onMap)
    AnalyzerMenu("FFT drawing", drawStyle.label, DrawStyle.entries, optionLabel = { it.label }, onSelect = onDraw)
    Text("Drag the dB scale to slide it. Pinch that scale to zoom it. The waterfall uses the same range.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SettingsTab(
    state: SdrState,
    viewModel: SdrViewModel,
    font: PlotFont,
    reverseWheel: Boolean,
    drawerOnRight: Boolean,
    showDebug: Boolean,
    onFont: (PlotFont) -> Unit,
    onReverse: (Boolean) -> Unit,
    onDrawerSide: (Boolean) -> Unit,
    onDebug: (Boolean) -> Unit
) {
    AnalyzerMenu("Font size (FFT plot)", font.label, PlotFont.entries, optionLabel = { it.label }, onSelect = onFont)
    AnalyzerSwitch("Reverse tuning wheel", reverseWheel, onReverse)
    AnalyzerMenu(
        "Control drawer side (landscape)",
        if (drawerOnRight) "Right" else "Left",
        listOf("Left", "Right"),
        onSelect = { onDrawerSide(it == "Right") }
    )
    AnalyzerSwitch("Allow out-of-bound frequency", state.allowOutOfBand, viewModel::setAllowOutOfBand)
    AnalyzerSwitch("Low performance mode", state.fftSize <= 1_024, { on ->
        viewModel.setFftSize(if (on) 1_024 else 4_096)
        viewModel.setWaterfallFps(if (on) 6 else 12)
    })
    AnalyzerSwitch("Show debug information", showDebug, onDebug)
    MoreTools(state, viewModel)
}

@Composable
private fun AboutTab() {
    Text("RF Analyzer", style = MaterialTheme.typography.titleMedium)
    Text(
        "Live FFT and waterfall, analog demodulation, IQ recording, and bookmarks — the same workflow as RF Analyzer by Dennis Mantz.",
        style = MaterialTheme.typography.bodyMedium
    )
    Text("Sources: RTL-SDR (USB or rtl_tcp), HackRF, Airspy, Airspy HF+, HydraSDR, and IQ file playback.", style = MaterialTheme.typography.bodyMedium)
    Text("Demodulation: AM, nFM, wFM, LSB, USB, and CW. Drag the orange channel selector, pinch to zoom, double-tap to retune the receiver to the current view.", style = MaterialTheme.typography.bodyMedium)
    Text("This screen receives only. It does not transmit.", style = MaterialTheme.typography.bodyMedium)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarkSheet(state: SdrState, viewModel: SdrViewModel, channelHz: Long, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Bookmark manager", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    viewModel.addBookmark(name, band = false)
                    name = ""
                }) { Text("Save station") }
                TextButton(onClick = {
                    viewModel.addBookmark(name, band = true)
                    name = ""
                }) { Text("Save band") }
            }
            TextButton(onClick = viewModel::addBandPlans) { Text("Add amateur / ISM / air / marine plans") }
            Text("${formatHzPlain(channelHz)}  ${modeName(state.mode)}", style = MaterialTheme.typography.bodySmall)
            state.bookmarks.forEach { bookmark ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { viewModel.tuneBookmark(bookmark); onDismiss() }, modifier = Modifier.weight(1f)) {
                        Text(
                            "${if (bookmark.isBand) "Band" else "Station"}  ${bookmark.name}  ${formatHzPlain(bookmark.hz)}  ${modeName(bookmark.mode)}",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    TextButton(onClick = { viewModel.deleteBookmark(bookmark) }) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
private fun MoreTools(state: SdrState, viewModel: SdrViewModel) {
    val context = LocalContext.current
    var namingCot by remember { mutableStateOf(false) }
    if (namingCot) {
        ExportNameDialog(
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
    val level by SdrLevel.levelDb.collectAsState()
    ZdSectionLabel("More tools")
    Text("${level.toInt()} dB at the tuned frequency", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        ZdButton(if (state.sweeping) "Stop sweep" else "Sweep bands", onClick = viewModel::toggleSweep, variant = ZdButtonVariant.Secondary, modifier = Modifier.weight(1f), height = 40.dp)
        ZdButton("GPS L1", onClick = viewModel::checkGnss, variant = ZdButtonVariant.Secondary, modifier = Modifier.weight(1f), height = 40.dp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        ZdButton(if (state.remoteScanning) "Stop Remote ID" else "Remote ID", onClick = viewModel::toggleRemoteId, variant = ZdButtonVariant.Secondary, modifier = Modifier.weight(1f), height = 40.dp)
        ZdButton("Export CoT", onClick = { namingCot = true }, icon = ZdIcons.Share, variant = ZdButtonVariant.Secondary, modifier = Modifier.weight(1f), height = 40.dp)
    }
    ZdButton(if (state.gnssWatch == null) "Phone GNSS" else "Stop phone GNSS", onClick = viewModel::toggleGnssWatch, variant = ZdButtonVariant.Secondary, modifier = Modifier.fillMaxWidth())
    state.gnssWatch?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (state.remoteIds.isNotEmpty()) {
        ZdListCard(state.remoteIds.take(8)) { sighting ->
            val where = if (sighting.lat != null && sighting.lon != null) String.format(Locale.US, "%.5f, %.5f", sighting.lat, sighting.lon) else sighting.address
            ZdListRow(title = sighting.id, subtitle = "$where · ${sighting.rssi} dBm")
        }
    }
    if (state.detections.isNotEmpty()) {
        ZdSectionLabel("Heard", trailingText = "${state.detections.size}")
        ZdListCard(state.detections.take(8)) { detection ->
            ZdListRow(title = "${formatMhz(detection.hz)} MHz", subtitle = "${detection.mode.label} · ${detection.db.toInt()} dB")
        }
    }
}

@Composable
private fun LiveAnalyzerPlots(
    state: SdrState,
    viewModel: SdrViewModel,
    spectrumShare: Float,
    dbFloor: Float,
    dbCeil: Float,
    zoom: Float,
    bandwidthHz: Int,
    converterHz: Long,
    relative: Boolean,
    bars: Boolean,
    colorMap: ColorMap,
    averageCount: Int,
    peakHold: Boolean,
    peakEpoch: Int,
    fitRequest: Int,
    fitted: Boolean,
    onFit: (Float, Float) -> Unit,
    squelchOn: Boolean,
    fontSp: Float,
    showDebug: Boolean,
    keepCentered: Boolean,
    onShiftDb: (Float) -> Unit,
    onZoomDb: (Float) -> Unit,
    onZoomView: (Float) -> Unit,
    onBandwidth: (Int) -> Unit,
    onSquelch: (Int) -> Unit,
    onViewportFit: (Long, Int) -> Unit,
    modifier: Modifier
) {
    val spectrum by viewModel.liveSpectrum.collectAsState()
    val waterfall by viewModel.liveWaterfall.collectAsState()
    val history = remember(averageCount, state.radio, state.fftSize, state.sampleRate, state.frequencyHz, state.listening) {
        ArrayDeque<FloatArray>()
    }
    val displayBins = remember(spectrum, averageCount) {
        if (averageCount <= 1 || spectrum.isEmpty()) spectrum
        else {
            history.addLast(spectrum.copyOf())
            while (history.size > averageCount) history.removeFirst()
            FloatArray(spectrum.size) { index ->
                var sum = 0f
                history.forEach { row -> if (index < row.size) sum += row[index] }
                sum / history.size
            }
        }
    }
    var peaks by remember { mutableStateOf(FloatArray(0)) }
    LaunchedEffect(displayBins, peakHold) {
        if (!peakHold || displayBins.isEmpty()) return@LaunchedEffect
        val current = peaks
        if (current.size != displayBins.size) {
            peaks = displayBins.copyOf()
            return@LaunchedEffect
        }
        val next = current.copyOf()
        var changed = false
        for (i in displayBins.indices) if (displayBins[i] > next[i]) {
            next[i] = displayBins[i]
            changed = true
        }
        if (changed) peaks = next
    }
    LaunchedEffect(state.listening) {
        if (!state.listening) return@LaunchedEffect
        if (fitted && dbFloor < -15f) return@LaunchedEffect
        viewModel.liveSpectrum.first { bins ->
            if (!SdrDsp.isMeasuredSpectrum(bins)) return@first false
            onFit(bins.min() - 6f, bins.max() + 8f)
            true
        }
    }
    LaunchedEffect(fitRequest) {
        if (fitRequest > 0 && SdrDsp.isMeasuredSpectrum(displayBins)) {
            onFit(displayBins.min() - 6f, displayBins.max() + 8f)
        }
    }
    LaunchedEffect(peakEpoch) { peaks = FloatArray(0) }
    AnalyzerPlots(
        bins = displayBins,
        peaks = if (peakHold) peaks else null,
        rows = waterfall,
        state = state,
        viewModel = viewModel,
        spectrumShare = spectrumShare,
        dbFloor = dbFloor,
        dbCeil = dbCeil,
        zoom = zoom,
        bandwidthHz = bandwidthHz,
        converterHz = converterHz,
        relative = relative,
        bars = bars,
        colorMap = colorMap,
        squelchOn = squelchOn,
        fontSp = fontSp,
        showDebug = showDebug,
        keepCentered = keepCentered,
        onShiftDb = onShiftDb,
        onZoomDb = onZoomDb,
        onZoomView = onZoomView,
        onBandwidth = onBandwidth,
        onSquelch = onSquelch,
        onViewportFit = onViewportFit,
        modifier = modifier
    )
}

@Composable
private fun AnalyzerPlots(
    bins: FloatArray,
    peaks: FloatArray?,
    rows: List<FloatArray>,
    state: SdrState,
    viewModel: SdrViewModel,
    spectrumShare: Float,
    dbFloor: Float,
    dbCeil: Float,
    zoom: Float,
    bandwidthHz: Int,
    converterHz: Long,
    relative: Boolean,
    bars: Boolean,
    colorMap: ColorMap,
    squelchOn: Boolean,
    fontSp: Float,
    showDebug: Boolean,
    keepCentered: Boolean,
    onShiftDb: (Float) -> Unit,
    onZoomDb: (Float) -> Unit,
    onZoomView: (Float) -> Unit,
    onBandwidth: (Int) -> Unit,
    onSquelch: (Int) -> Unit,
    onViewportFit: (Long, Int) -> Unit,
    modifier: Modifier
) {
    Column(modifier) {
        Box(Modifier.fillMaxWidth().weight(spectrumShare)) {
            SpectrumPlot(
                bins = bins,
                peaks = peaks,
                sampleRate = state.sampleRate,
                centerHz = state.frequencyHz,
                offsetHz = state.offsetHz,
                bandwidthHz = bandwidthHz,
                converterHz = converterHz,
                zoom = zoom,
                relative = relative,
                bars = bars,
                dbFloor = dbFloor,
                dbCeil = dbCeil,
                squelchOn = squelchOn,
                squelch = state.squelch,
                fontSp = fontSp,
                showDebug = showDebug,
                debug = "${rateLabel(state.sampleRate)} · FFT ${state.fftSize} · ${state.waterfallFps} fps",
                recording = state.recording,
                bookmarks = state.bookmarks,
                onTune = viewModel::nudgeFrequency,
                onChannel = { viewModel.tuneChannel(state.frequencyHz + it, keepCentered) },
                onBandwidth = onBandwidth,
                onSquelch = onSquelch,
                onZoom = onZoomView,
                onViewportFit = onViewportFit,
                modifier = Modifier.fillMaxSize().padding(start = 44.dp)
            )
            DbAxis(dbFloor, dbCeil, fontSp, onShiftDb, onZoomDb, Modifier.width(44.dp).fillMaxSize())
        }
        WaterfallPlot(
            rows = rows,
            dbFloor = dbFloor,
            dbCeil = dbCeil,
            colorMap = colorMap,
            offsetHz = state.offsetHz,
            sampleRate = state.sampleRate,
            zoom = zoom,
            modifier = Modifier.fillMaxWidth().weight((1f - spectrumShare).coerceAtLeast(0.15f)).padding(start = 44.dp)
        )
    }
}

private enum class DragTarget { TUNE, CHANNEL, BANDWIDTH, SQUELCH }

@Composable
private fun SpectrumPlot(
    bins: FloatArray,
    peaks: FloatArray?,
    sampleRate: Int,
    centerHz: Long,
    offsetHz: Long,
    bandwidthHz: Int,
    converterHz: Long,
    zoom: Float,
    relative: Boolean,
    bars: Boolean,
    dbFloor: Float,
    dbCeil: Float,
    squelchOn: Boolean,
    squelch: Int,
    fontSp: Float,
    showDebug: Boolean,
    debug: String,
    recording: Boolean,
    bookmarks: List<SdrBookmark>,
    onTune: (Long) -> Unit,
    onChannel: (Long) -> Unit,
    onBandwidth: (Int) -> Unit,
    onSquelch: (Int) -> Unit,
    onZoom: (Float) -> Unit,
    onViewportFit: (Long, Int) -> Unit,
    modifier: Modifier
) {
    val line = Color(0xFF9FC9FF)
    var origin by remember { mutableStateOf(0f) }
    var dragged by remember { mutableStateOf(0f) }
    var target by remember { mutableStateOf(DragTarget.TUNE) }
    var edge by remember { mutableIntStateOf(1) }
    Canvas(
        modifier
            .pointerInput(autoZoomKey(sampleRate, zoom)) {
                var bucket = 1f
                detectTransformGestures { _, _, zoomFactor, _ ->
                    if (zoomFactor == 1f) return@detectTransformGestures
                    bucket *= zoomFactor
                    if (bucket >= 1.12f || bucket <= 0.88f) {
                        onZoom(bucket)
                        bucket = 1f
                    }
                }
            }
            .pointerInput(sampleRate, offsetHz, zoom, centerHz) {
                detectTapGestures(
                    onDoubleTap = {
                        val window = viewWindow(sampleRate, offsetHz, zoom)
                        val span = (window.end - window.start).toInt().coerceAtLeast(2_000)
                        onViewportFit(centerHz + (window.start + window.end) / 2, span)
                    }
                )
            }
            .pointerInput(sampleRate, offsetHz, bandwidthHz, zoom, squelch) {
                detectDragGestures(
                    onDragStart = { start ->
                        origin = start.x
                        dragged = 0f
                        val width = size.width.toFloat()
                        val window = viewWindow(sampleRate, offsetHz, zoom)
                        val marker = xOf(offsetHz, window, width)
                        val left = xOf(offsetHz - bandwidthHz / 2L, window, width)
                        val right = xOf(offsetHz + bandwidthHz / 2L, window, width)
                        val plotBottom = size.height.toFloat() - 18.dp.toPx()
                        val squelchY = squelchY(squelch, dbFloor, dbCeil, plotBottom)
                        target = when {
                            squelchOn && abs(start.x - marker) < 28.dp.toPx() && abs(start.y - squelchY) < 28.dp.toPx() -> DragTarget.SQUELCH
                            abs(start.x - left) < 22.dp.toPx() || abs(start.x - right) < 22.dp.toPx() -> {
                                edge = if (abs(start.x - right) <= abs(start.x - left)) 1 else -1
                                DragTarget.BANDWIDTH
                            }
                            abs(start.x - marker) < 28.dp.toPx() -> DragTarget.CHANNEL
                            else -> DragTarget.TUNE
                        }
                    },
                    onDragEnd = {
                        if (target == DragTarget.TUNE && abs(dragged) < 12f) {
                            val window = viewWindow(sampleRate, offsetHz, zoom)
                            val frac = (origin / size.width.toFloat()).coerceIn(0f, 1f)
                            val rel = window.start + ((window.end - window.start) * frac).toLong()
                            onChannel(rel)
                        }
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        dragged += amount.x
                        val window = viewWindow(sampleRate, offsetHz, zoom)
                        val span = (window.end - window.start).coerceAtLeast(1L)
                        val width = size.width.toFloat()
                        when (target) {
                            DragTarget.CHANNEL -> {
                                val delta = (amount.x / width * span).toLong()
                                if (delta != 0L) onChannel(offsetHz + delta)
                            }
                            DragTarget.BANDWIDTH -> {
                                val delta = (amount.x / width * span).toInt()
                                if (delta != 0) onBandwidth(bandwidthHz + delta * edge)
                            }
                            DragTarget.SQUELCH -> {
                                val plotBottom = size.height.toFloat() - 18.dp.toPx()
                                val next = squelch - (amount.y / plotBottom * 8000f).toInt()
                                onSquelch(next.coerceIn(0, 8000))
                            }
                            DragTarget.TUNE -> {
                                val delta = (-amount.x / width * span).toLong()
                                if (delta != 0L) onTune(delta)
                            }
                        }
                    }
                )
            }
    ) {
        drawRect(PlotBlack)
        val plotBottom = size.height - 18.dp.toPx()
        val spanDb = (dbCeil - dbFloor).coerceAtLeast(1f)
        val window = viewWindow(sampleRate, offsetHz, zoom)
        val grid = Color(0xFF3A4250)
        val label = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(220, 195, 198, 207)
            textSize = fontSp.sp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        for (step in 1..3) {
            val y = plotBottom * step / 4f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        for (tick in 0..4) {
            val x = size.width * tick / 4f
            val rel = window.start + (window.end - window.start) * tick / 4L
            val shown = if (relative) rel else centerHz + converterHz + rel
            drawLine(grid, Offset(x, 0f), Offset(x, plotBottom), strokeWidth = 1f)
            drawContext.canvas.nativeCanvas.drawText(
                if (relative) formatOffset(shown) else formatMhz(shown),
                x.coerceIn(24f, size.width - 24f),
                size.height - 3.dp.toPx(),
                label
            )
        }
        if (SdrDsp.isMeasuredSpectrum(bins)) {
            val half = sampleRate / 2f
            fun point(index: Int, value: Float): Offset {
                val rel = -half + (index + 0.5f) * sampleRate / bins.size
                val x = ((rel - window.start) / (window.end - window.start).coerceAtLeast(1L)) * size.width
                val y = plotBottom * (1f - ((value - dbFloor) / spanDb).coerceIn(0f, 1f))
                return Offset(x, y)
            }
            val step = (bins.size / 1_024).coerceAtLeast(1)
            if (bars) {
                var index = 0
                while (index < bins.size) {
                    val p = point(index, bins[index])
                    if (p.x >= -2f && p.x <= size.width + 2f) {
                        drawLine(line, Offset(p.x, p.y), Offset(p.x, plotBottom), strokeWidth = 2f)
                    }
                    index += step
                }
            } else {
                var previous: Offset? = null
                var index = 0
                while (index < bins.size) {
                    val p = point(index, bins[index])
                    val prior = previous
                    if (prior != null && p.x >= 0f && prior.x <= size.width) drawLine(line, prior, p, strokeWidth = 2f)
                    previous = p
                    index += step
                }
            }
            peaks?.let { held ->
                held.forEachIndexed { index, value ->
                    val p = point(index, value)
                    if (p.x in 0f..size.width) drawCircle(Color(0xFFFFE082), 1.6.dp.toPx(), p)
                }
            }
        }
        val left = xOf(offsetHz - bandwidthHz / 2L, window, size.width)
        val right = xOf(offsetHz + bandwidthHz / 2L, window, size.width)
        val marker = xOf(offsetHz, window, size.width)
        drawRect(ChannelOrange.copy(alpha = 0.16f), topLeft = Offset(left, 0f), size = Size((right - left).coerceAtLeast(2f), plotBottom))
        drawLine(Color(0xFF9AA3B2), Offset(left, 0f), Offset(left, plotBottom), strokeWidth = 3f)
        drawLine(Color(0xFF9AA3B2), Offset(right, 0f), Offset(right, plotBottom), strokeWidth = 3f)
        drawRoundRect(Color(0xFF9AA3B2), topLeft = Offset(left - 5.dp.toPx(), plotBottom / 2f - 12.dp.toPx()), size = Size(10.dp.toPx(), 24.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
        drawRoundRect(Color(0xFF9AA3B2), topLeft = Offset(right - 5.dp.toPx(), plotBottom / 2f - 12.dp.toPx()), size = Size(10.dp.toPx(), 24.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
        drawLine(Color.White, Offset(marker, 0f), Offset(marker, plotBottom), strokeWidth = 2f)
        drawRoundRect(ChannelOrange, topLeft = Offset(marker - 9.dp.toPx(), 4.dp.toPx()), size = Size(18.dp.toPx(), 10.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
        if (squelchOn) {
            val y = squelchY(squelch, dbFloor, dbCeil, plotBottom)
            val open = channelOpen(bins, sampleRate, offsetHz, bandwidthHz, dbFloor, dbCeil, squelch)
            val tint = if (open) Color(0xFF66BB6A) else Color(0xFFEF5350)
            drawCircle(tint, 7.dp.toPx(), Offset(marker, y))
        }
        bookmarks.forEach { bookmark ->
            val rel = bookmark.hz - centerHz
            val x = xOf(rel, window, size.width)
            if (x in 0f..size.width) {
                drawContext.canvas.nativeCanvas.drawText(bookmark.name, x, 14.dp.toPx(), label)
            }
        }
        val hud = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(230, 223, 226, 235)
            textSize = fontSp.sp.toPx()
            isAntiAlias = true
        }
        if (recording) drawContext.canvas.nativeCanvas.drawText("REC", 6.dp.toPx(), 16.dp.toPx(), hud)
        if (showDebug) {
            hud.textAlign = android.graphics.Paint.Align.RIGHT
            drawContext.canvas.nativeCanvas.drawText(debug, size.width - 6.dp.toPx(), 16.dp.toPx(), hud)
        }
    }
}

@Composable
private fun DbAxis(
    floor: Float,
    ceil: Float,
    fontSp: Float,
    onShift: (Float) -> Unit,
    onZoom: (Float) -> Unit,
    modifier: Modifier
) {
    val span = (ceil - floor).coerceAtLeast(1f)
    val paint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.argb(220, 195, 198, 207)
            textAlign = android.graphics.Paint.Align.RIGHT
            isAntiAlias = true
        }
    }
    Canvas(
        modifier.pointerInput(floor, ceil) {
            detectTransformGestures { _, pan, zoom, _ ->
                if (pan.y != 0f) onShift(pan.y / size.height * span)
                if (zoom != 1f) onZoom(zoom)
            }
        }
    ) {
        paint.textSize = fontSp.sp.toPx()
        for (step in 0..4) {
            val fraction = step / 4f
            val db = ceil - span * fraction
            drawContext.canvas.nativeCanvas.drawText(db.toInt().toString(), size.width - 4.dp.toPx(), size.height * fraction + 12.dp.toPx(), paint)
        }
    }
}

@Composable
private fun WaterfallPlot(
    rows: List<FloatArray>,
    dbFloor: Float,
    dbCeil: Float,
    colorMap: ColorMap,
    offsetHz: Long,
    sampleRate: Int,
    zoom: Float,
    modifier: Modifier
) {
    val lut = remember(colorMap) { waterfallLut(colorMap) }
    val buffer = remember { WaterfallBuffer() }
    Canvas(modifier) {
        drawRect(PlotBlack)
        if (rows.isEmpty()) return@Canvas
        val width = size.width.toInt().coerceIn(8, 640)
        val height = size.height.toInt().coerceIn(8, 240)
        var bmp = buffer.bitmap
        if (bmp == null || bmp.width != width || bmp.height != height) {
            bmp?.recycle()
            bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            buffer.bitmap = bmp
            buffer.pixels = IntArray(width * height)
        }
        rasterWaterfall(buffer.pixels, width, height, rows, dbFloor, dbCeil, sampleRate, offsetHz, zoom, lut)
        bmp.setPixels(buffer.pixels, 0, width, 0, 0, width, height)
        drawContext.canvas.nativeCanvas.drawBitmap(bmp, null, android.graphics.RectF(0f, 0f, size.width, size.height), null)
    }
}

private class WaterfallBuffer {
    var bitmap: android.graphics.Bitmap? = null
    var pixels = IntArray(0)
}

private fun rasterWaterfall(
    pixels: IntArray,
    width: Int,
    height: Int,
    rows: List<FloatArray>,
    dbFloor: Float,
    dbCeil: Float,
    sampleRate: Int,
    offsetHz: Long,
    zoom: Float,
    lut: IntArray
) {
    val span = (dbCeil - dbFloor).coerceAtLeast(1f)
    val window = viewWindow(sampleRate, offsetHz, zoom)
    val half = sampleRate / 2f
    val rowCount = rows.size
    var y = 0
    while (y < height) {
        val row = rows[(y * rowCount / height).coerceIn(0, rowCount - 1)]
        val last = row.lastIndex
        var x = 0
        val dest = y * width
        if (last < 0) {
            while (x < width) {
                pixels[dest + x] = lut[0]
                x++
            }
        } else {
            while (x < width) {
                val rel = window.start + (window.end - window.start) * (x + 0.5f) / width
                val index = (((rel + half) / sampleRate.coerceAtLeast(1)) * row.size).toInt().coerceIn(0, last)
                val level = ((row[index] - dbFloor) / span).coerceIn(0f, 1f)
                pixels[dest + x] = lut[(level * 255f).toInt().coerceIn(0, 255)]
                x++
            }
        }
        y++
    }
}

private fun waterfallLut(map: ColorMap): IntArray = IntArray(256) { index ->
    val color = waterfallColor(index / 255f, map)
    android.graphics.Color.argb(
        (color.alpha * 255f).toInt(),
        (color.red * 255f).toInt(),
        (color.green * 255f).toInt(),
        (color.blue * 255f).toInt()
    )
}

@Composable
private fun TuningWheel(reverse: Boolean, onStep: (Int) -> Unit) {
    var shift by remember { mutableFloatStateOf(0f) }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .pointerInput(reverse) {
                detectHorizontalDragGestures { change, drag ->
                    change.consume()
                    val directed = if (reverse) -drag else drag
                    shift += directed
                    val stepPx = 28.dp.toPx()
                    while (shift >= stepPx) {
                        shift -= stepPx
                        onStep(1)
                    }
                    while (shift <= -stepPx) {
                        shift += stepPx
                        onStep(-1)
                    }
                }
            }
    ) {
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(180, 159, 201, 255)
            strokeWidth = 2f
        }
        val gap = 14.dp.toPx()
        var x = (shift % gap) - gap
        while (x < size.width + gap) {
            val tall = abs((x / gap).roundToInt()) % 5 == 0
            val h = if (tall) size.height * 0.72f else size.height * 0.4f
            drawLine(Color(paint.color), Offset(x, (size.height - h) / 2f), Offset(x, (size.height + h) / 2f), strokeWidth = if (tall) 2.5f else 1.4f)
            x += gap
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> AnalyzerMenu(
    label: String,
    value: String,
    options: List<T>,
    modifier: Modifier = Modifier,
    optionLabel: (T) -> String = { it.toString() },
    onSelect: (T) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelect(option)
                        open = false
                    }
                )
            }
        }
    }
}

@Composable
private fun AnalyzerSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun FrequencyField(label: String, hz: Long, onApply: (Long) -> Unit) {
    var unit by rememberSaveable(label) { mutableStateOf("Hz") }
    val shown = when (unit) {
        "MHz" -> (hz / 1_000_000.0).toString()
        "kHz" -> (hz / 1_000.0).toString()
        else -> hz.toString()
    }
    var text by remember(hz, unit) { mutableStateOf(shown) }
    var editing by remember { mutableStateOf(false) }
    if (!editing && text != shown) text = shown
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                editing = true
                text = it.filter { ch -> ch == '-' || ch == '.' || ch.isDigit() }
            },
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f)
        )
        AnalyzerMenu("Unit", unit, listOf("Hz", "kHz", "MHz"), modifier = Modifier.width(108.dp), onSelect = {
            unit = it
            editing = false
        })
        TextButton(onClick = {
            val value = text.toDoubleOrNull() ?: return@TextButton
            val next = when (unit) {
                "MHz" -> (value * 1_000_000.0).toLong()
                "kHz" -> (value * 1_000.0).toLong()
                else -> value.toLong()
            }
            onApply(next)
            editing = false
        }) { Text("Apply") }
    }
}

@Composable
private fun HzField(label: String, hz: Long, onApply: (Long) -> Unit) {
    var text by remember(hz) { mutableStateOf(hz.toString()) }
    var editing by remember { mutableStateOf(false) }
    if (!editing && text != hz.toString()) text = hz.toString()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                editing = true
                text = it.filter { ch -> ch == '-' || ch.isDigit() }
            },
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = {
            text.toLongOrNull()?.let(onApply)
            editing = false
        }) { Text("Apply") }
    }
}

private data class ViewWindow(val start: Long, val end: Long)

private fun viewWindow(sampleRate: Int, offsetHz: Long, zoom: Float): ViewWindow {
    val half = sampleRate / 2f
    val span = (sampleRate / zoom.coerceAtLeast(1f)).coerceAtLeast(2_000f)
    var start = offsetHz - span / 2f
    var end = offsetHz + span / 2f
    if (start < -half) {
        end -= start + half
        start = -half
    }
    if (end > half) {
        start -= end - half
        end = half
    }
    return ViewWindow(start.toLong(), end.toLong().coerceAtLeast(start.toLong() + 1L))
}

private fun xOf(relHz: Long, window: ViewWindow, width: Float): Float {
    val span = (window.end - window.start).coerceAtLeast(1L).toFloat()
    return ((relHz - window.start) / span * width).coerceIn(0f, width)
}

private fun squelchY(squelch: Int, floor: Float, ceil: Float, plotBottom: Float): Float {
    val db = floor + (ceil - floor) * (squelch / 8000f)
    return plotBottom * (1f - ((db - floor) / (ceil - floor).coerceAtLeast(1f)))
}

private fun channelOpen(bins: FloatArray, sampleRate: Int, offsetHz: Long, bandwidthHz: Int, floor: Float, ceil: Float, squelch: Int): Boolean {
    if (bins.isEmpty()) return false
    val half = sampleRate / 2f
    val low = offsetHz - bandwidthHz / 2f
    val high = offsetHz + bandwidthHz / 2f
    var peak = Float.NEGATIVE_INFINITY
    bins.forEachIndexed { index, value ->
        val rel = -half + (index + 0.5f) * sampleRate / bins.size
        if (rel in low..high && value > peak) peak = value
    }
    val gate = floor + (ceil - floor) * (squelch / 8000f)
    return peak >= gate
}

private fun modeHelp(mode: SdrMode): String = when (mode) {
    SdrMode.WFM -> "Wide FM is used for broadcast radio. Typical bandwidth 180 kHz."
    SdrMode.NFM -> "Narrow FM is used for ham, marine, and public-safety voice. Typical bandwidth 12.5 kHz."
    SdrMode.AM -> "AM is used for shortwave, airband, and some CB. Typical bandwidth 6–10 kHz."
    SdrMode.USB -> "USB is used on ham bands above 10 MHz and for many digital modes."
    SdrMode.LSB -> "LSB is used on ham bands below 10 MHz."
    SdrMode.CW -> "CW is a Morse carrier. Keep the bandwidth near 200 Hz."
}

private fun wheelStep(mode: SdrMode): Long = when (mode) {
    SdrMode.WFM -> 100_000L
    SdrMode.NFM -> 12_500L
    SdrMode.AM -> 5_000L
    SdrMode.USB, SdrMode.LSB -> 500L
    SdrMode.CW -> 100L
}

private fun modeName(mode: SdrMode): String = when (mode) {
    SdrMode.WFM -> "wFM"
    SdrMode.NFM -> "nFM"
    SdrMode.AM -> "AM"
    SdrMode.LSB -> "LSB"
    SdrMode.USB -> "USB"
    SdrMode.CW -> "CW"
}

private fun waterfallColor(level: Float, map: ColorMap): Color {
    val t = level.coerceIn(0f, 1f)
    val stops = when (map) {
        ColorMap.JET -> arrayOf(Color(0xFF00007F), Color(0xFF0000FF), Color(0xFF00FFFF), Color(0xFF00FF00), Color(0xFFFFFF00), Color(0xFFFF0000), Color(0xFF7F0000))
        ColorMap.TURBO -> arrayOf(Color(0xFF30123B), Color(0xFF4145AB), Color(0xFF4675ED), Color(0xFF1BD0D5), Color(0xFF61E45A), Color(0xFFF2E945), Color(0xFFFE9B2D), Color(0xFFD93806), Color(0xFF7A0403))
        ColorMap.GQRX -> arrayOf(Color.Black, Color(0xFF0000A0), Color(0xFF00FFFF), Color(0xFF00FF00), Color(0xFFFFFF00), Color(0xFFFF0000), Color.White)
    }
    val scaled = t * (stops.size - 1)
    val index = scaled.toInt().coerceIn(0, stops.size - 2)
    return lerp(stops[index], stops[index + 1], scaled - index)
}

private fun rateLabel(rate: Int): String =
    if (rate >= 1_000_000) "${rate / 1_000_000.0} MHz" else "${rate / 1_000} kHz"

private fun formatMhz(hz: Long): String =
    String.format(Locale.US, "%.4f", hz / 1_000_000.0).trimEnd('0').trimEnd('.')

private fun formatHzPlain(hz: Long): String = "%,d Hz".format(Locale.US, hz)

private fun formatOffset(hz: Long): String {
    val sign = if (hz < 0) "−" else if (hz > 0) "+" else ""
    val absHz = abs(hz)
    return if (absHz >= 1_000_000) "$sign${"%.3f".format(Locale.US, absHz / 1_000_000.0)} M"
    else if (absHz >= 1_000) "$sign${absHz / 1_000} k"
    else "$sign$absHz"
}

private fun autoZoomKey(sampleRate: Int, zoom: Float): String = "$sampleRate:$zoom"
