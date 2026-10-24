package app.radiusrf.features.sdr.viewmodel

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radiusrf.core.debug.DemoData
import app.radiusrf.core.debug.DemoDataBus
import app.radiusrf.core.debug.observeDemoRequests
import app.radiusrf.core.hardware.HardwareChecker
import app.radiusrf.core.usb.UsbConnectionManager
import app.radiusrf.core.usb.UsbConnectionResult
import app.radiusrf.features.sdr.domain.GnssInterference
import app.radiusrf.features.sdr.domain.RemoteIdWatch
import app.radiusrf.features.sdr.domain.SdrBands
import app.radiusrf.features.sdr.domain.SdrBand
import app.radiusrf.features.sdr.domain.SdrBookmark
import app.radiusrf.features.sdr.domain.SdrDetection
import app.radiusrf.features.sdr.domain.SdrDetector
import app.radiusrf.features.sdr.domain.SdrDeviceInfo
import app.radiusrf.features.sdr.domain.DirectSampling
import app.radiusrf.features.sdr.domain.IqFileFormat
import app.radiusrf.features.sdr.domain.SdrDsp
import app.radiusrf.features.sdr.domain.SdrLevel
import app.radiusrf.features.sdr.domain.SdrMode
import app.radiusrf.features.sdr.domain.SdrRadio
import app.radiusrf.features.sdr.domain.SdrRecording
import app.radiusrf.features.sdr.domain.SdrSession
import app.radiusrf.features.sdr.domain.SdrState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

@HiltViewModel
class SdrViewModel @Inject constructor(
    private val sdrDetector: SdrDetector,
    private val usbConnectionManager: UsbConnectionManager,
    hardwareChecker: HardwareChecker,
    private val demoBus: DemoDataBus,
    private val session: SdrSession,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val engine = session.engine
    val liveSpectrum: StateFlow<FloatArray> get() = engine.spectrum
    val liveWaterfall: StateFlow<List<FloatArray>> get() = engine.waterfall
    private val remoteId = RemoteIdWatch(appContext)
    private val gnssWatch = GnssInterference(appContext)
    private var sweepJob: Job? = null
    private var opening = false
    private var usbReceiver: BroadcastReceiver? = null
    private val _state = MutableStateFlow(SdrState(hasUsbHost = hardwareChecker.hasUsbHost()))
    val state: StateFlow<SdrState> = _state.asStateFlow()

    private val bookmarksFile = File(appContext.filesDir, "sdr_bookmarks.txt")
    private val captureFile = File(appContext.cacheDir, "sdr/capture.cu8")
    private val recordingsDir = File(appContext.filesDir, "sdr/recordings")

    init {
        refresh()
        _state.update {
            it.copy(
                bookmarks = loadBookmarks(),
                hasRecording = captureFile.exists() || recordingsDir.listFiles()?.isNotEmpty() == true,
                recordings = listRecordings()
            )
        }
        if (engine.isRunning()) {
            _state.update {
                it.copy(
                    listening = true,
                    connectedVidPid = session.connectedVidPid,
                    radio = session.radio,
                    frequencyHz = engine.frequencyHz,
                    sampleRate = engine.sampleRate,
                    fftSize = engine.fftSize,
                    offsetHz = engine.offsetHz.toLong(),
                    squelch = engine.squelch,
                    ppm = engine.ppm,
                    lna = engine.lna,
                    vga = engine.vga,
                    amp = engine.amp,
                    biasTee = engine.biasTee,
                    directSampling = engine.directSampling,
                    allowOutOfBand = engine.allowOutOfBand,
                    channelBandwidthHz = engine.channelBandwidthHz,
                    waterfallFps = engine.waterfallFps,
                    fileRepeat = engine.repeatPlayback,
                    fileFormat = engine.fileFormat,
                    recordOnSquelch = engine.recordOnSquelch,
                    paused = session.paused
                )
            }
        }
        viewModelScope.launch {
            SdrLevel.listening.collect { on ->
                _state.update { it.copy(listening = on, paused = if (on) session.paused else false) }
            }
        }
        viewModelScope.launch {
            session.pausedFlow.collect { held -> _state.update { it.copy(paused = held) } }
        }
        viewModelScope.launch { engine.status.collect { text -> _state.update { s -> s.copy(listenStatus = text) } } }
        viewModelScope.launch {
            SdrLevel.frequencyHz.collect { hz ->
                _state.update { s -> if (s.frequencyHz == hz) s else s.copy(frequencyHz = hz, offsetHz = 0L) }
            }
        }
        viewModelScope.launch {
            engine.detections.collect { found ->
                _state.update { s -> s.copy(detections = found) }
            }
        }
        watchUsb()
        if (!engine.isRunning()) adoptAttached()
    }

    fun refresh() {
        _state.update {
            it.copy(
                devices = sdrDetector.detect(),
                hasRecording = captureFile.exists() || recordingsDir.listFiles()?.isNotEmpty() == true,
                recordings = listRecordings()
            )
        }
    }

    fun connect(device: SdrDeviceInfo) {
        if (opening) return
        opening = true
        viewModelScope.launch {
            try {
            _state.update { it.copy(connectingVidPid = device.vidPid, connectionError = null) }
            val usbDevice = usbConnectionManager.findDevice(device.vendorId, device.productId)
            if (usbDevice == null) {
                _state.update { it.copy(connectingVidPid = null, connectionError = "${device.chipset} is no longer connected") }
                return@launch
            }
            when (val result = usbConnectionManager.requestPermissionAndOpen(
                usbDevice,
                claim = !device.canListen
            )) {
                is UsbConnectionResult.Success -> {
                    stopListening()
                    session.connection?.close()
                    session.connection = result.connection
                    session.connectedVidPid = device.vidPid
                    val radio = device.radioKind()
                    val rate = SdrBands.nearestRate(radio, SdrBands.preferredRate(radio))
                    engine.sampleRate = rate
                    engine.signedIq = radio != SdrRadio.RTL && radio != SdrRadio.OTHER
                    session.radio = radio
                    _state.update {
                        it.copy(
                            connectingVidPid = null,
                            connectedVidPid = device.vidPid,
                            connectionError = null,
                            radio = radio,
                            sampleRate = rate,
                            listenStatus = when (radio) {
                                SdrRadio.RTL -> "RTL-SDR"
                                SdrRadio.HACKRF -> "HackRF"
                                SdrRadio.AIRSPY -> "Airspy"
                                SdrRadio.AIRSPYHF -> "Airspy HF+"
                                SdrRadio.HYDRA -> "HydraSDR"
                                else -> "Recognised. This receiver does not listen in this build."
                            }
                        )
                    }
                    if (device.canListen) listen()
                }
                is UsbConnectionResult.Failure -> {
                    _state.update { it.copy(connectingVidPid = null, connectionError = result.reason) }
                }
            }
            } finally {
                opening = false
            }
        }
    }

    /** Opens a dongle that is already plugged in and starts the spectrum. */
    fun adoptAttached() {
        refresh()
        val state = _state.value
        if (state.connectingVidPid != null || opening) return
        if (engine.isRunning()) return
        val usable = state.devices.filter { it.canListen }
        val current = usable.find { it.vidPid == state.connectedVidPid }
        if (current != null) {
            if (!state.listening) listen()
            return
        }
        val device = usable.firstOrNull() ?: return
        connect(device)
    }

    fun zoomSpan(narrower: Boolean) {
        val rates = SdrBands.ratesFor(_state.value.radio).toList()
        if (rates.isEmpty()) return
        val index = rates.indexOf(_state.value.sampleRate).let { if (it < 0) 0 else it }
        val next = if (narrower) (index - 1).coerceAtLeast(0) else (index + 1).coerceAtMost(rates.lastIndex)
        if (next != index) setSampleRate(rates[next])
    }

    fun disconnect() {
        stopListening()
        session.connection?.close()
        session.connection = null
        session.connectedVidPid = null
        session.radio = SdrRadio.NONE
        _state.update { it.copy(connectedVidPid = null, listening = false, radio = SdrRadio.NONE) }
    }

    fun setFrequency(hz: Long) {
        if (configurationLocked()) return
        session.tune(hz)
        _state.update { it.copy(frequencyHz = engine.frequencyHz, offsetHz = 0L) }
    }

    fun retuneViewport(centerHz: Long, spanHz: Int) {
        if (configurationLocked()) return
        setSampleRate(spanHz)
        setFrequency(centerHz)
    }

    fun nudgeFrequency(deltaHz: Long) {
        if (deltaHz == 0L) return
        setFrequency(_state.value.frequencyHz + deltaHz)
    }

    fun setOffset(hz: Long) {
        if (configurationLocked()) return
        val span = _state.value.sampleRate / 2
        val offset = hz.coerceIn(-span.toLong(), span.toLong())
        engine.offsetHz = offset.toDouble()
        _state.update { it.copy(offsetHz = offset) }
    }

    /** Moves the demodulator to an absolute frequency. [keepCentered] retunes the receiver so the channel stays in the middle. */
    fun tuneChannel(hz: Long, keepCentered: Boolean) {
        val state = _state.value
        val half = state.sampleRate / 2L
        val center = state.frequencyHz
        if (!keepCentered && hz in (center - half)..(center + half)) setOffset(hz - center)
        else setFrequency(hz)
    }

    fun setAudioGain(gain: Float) {
        engine.audioGain = gain.coerceIn(0f, 1f)
    }

    fun selectRadio(radio: SdrRadio) {
        val device = _state.value.devices.firstOrNull { it.radioKind() == radio && it.canListen }
        if (device == null) {
            _state.update { it.copy(listenStatus = "That receiver is not plugged in") }
            return
        }
        if (device.vidPid != _state.value.connectedVidPid) connect(device)
    }

    fun setSampleRate(rate: Int) {
        if (configurationLocked()) return
        val chosen = SdrBands.nearestRate(_state.value.radio, rate)
        engine.sampleRate = chosen
        _state.update { it.copy(sampleRate = chosen) }
    }

    fun setFftSize(size: Int) {
        val chosen = SdrBands.fftSizes.minBy { kotlin.math.abs(it - size) }
        engine.fftSize = chosen
        _state.update { it.copy(fftSize = chosen, spectrum = FloatArray(chosen), waterfall = emptyList()) }
    }

    fun setSquelch(level: Int) {
        val enabled = level > 0
        engine.squelch = level.coerceIn(0, 8000)
        engine.squelchEnabled = enabled
        engine.squelchDb = if (enabled) -120f + (level.coerceIn(0, 8000) / 8000f) * 120f else -120f
        _state.update { it.copy(squelch = engine.squelch) }
    }

    fun setSquelchDb(db: Float, enabled: Boolean) {
        engine.squelchEnabled = enabled
        engine.squelchDb = db.coerceIn(-140f, 0f)
        engine.squelch = if (enabled) ((db + 140f) / 140f * 8000f).toInt().coerceIn(1, 8000) else 0
        _state.update { it.copy(squelch = engine.squelch) }
    }

    fun setChannelBandwidth(hz: Int) {
        val value = hz.coerceIn(50, _state.value.sampleRate / 2)
        engine.channelBandwidthHz = value
        _state.update { it.copy(channelBandwidthHz = value) }
    }

    fun setWaterfallFps(fps: Int) {
        val value = fps.coerceIn(1, 30)
        engine.waterfallFps = value
        _state.update { it.copy(waterfallFps = value) }
    }

    fun setBiasTee(on: Boolean) {
        engine.biasTee = on
        _state.update { it.copy(biasTee = on) }
    }

    fun setDirectSampling(mode: DirectSampling) {
        engine.directSampling = mode
        _state.update { it.copy(directSampling = mode) }
    }

    fun setAllowOutOfBand(on: Boolean) {
        engine.allowOutOfBand = on
        _state.update { it.copy(allowOutOfBand = on) }
    }

    fun setFileRepeat(on: Boolean) {
        engine.repeatPlayback = on
        _state.update { it.copy(fileRepeat = on) }
    }

    fun setFileFormat(format: IqFileFormat) {
        engine.fileFormat = format
        engine.signedIq = format != IqFileFormat.UNSIGNED8
        _state.update { it.copy(fileFormat = format) }
    }

    fun setRtlTcp(host: String, port: Int) {
        _state.update { it.copy(rtlTcpHost = host.trim(), rtlTcpPort = port.coerceIn(1, 65535)) }
    }

    fun setRecordOnSquelch(on: Boolean) {
        engine.recordOnSquelch = on
        _state.update { it.copy(recordOnSquelch = on) }
    }

    fun listenRtlTcp() {
        val state = _state.value
        applyEngineSettings()
        val started = engine.listenRtlTcp(state.rtlTcpHost, state.rtlTcpPort, captureFile)
        _state.update { it.copy(listening = started, paused = false, recording = false, radio = SdrRadio.RTL) }
    }

    fun setPpm(ppm: Int) {
        val value = ppm.coerceIn(-100, 100)
        engine.ppm = value
        _state.update { it.copy(ppm = value) }
    }

    fun setLna(db: Int) {
        val value = (db.coerceIn(0, 40) / 8) * 8
        engine.lna = value
        _state.update { it.copy(lna = value) }
    }

    fun setVga(db: Int) {
        val value = (db.coerceIn(0, 62) / 2) * 2
        engine.vga = value
        _state.update { it.copy(vga = value) }
    }

    fun setAmp(on: Boolean) {
        engine.amp = on
        _state.update { it.copy(amp = on) }
    }

    fun tuneBand(band: SdrBand) {
        setMode(band.mode)
        val rate = SdrBands.nearestRate(_state.value.radio, SdrBands.bandRate(_state.value.radio))
        setSampleRate(rate)
        setFrequency(band.centerHz)
    }

    fun setMode(mode: SdrMode) {
        if (configurationLocked()) return
        engine.mode = mode
        val bandwidth = SdrBands.defaultBandwidth(mode)
        engine.channelBandwidthHz = bandwidth
        _state.update { it.copy(mode = mode, channelBandwidthHz = bandwidth) }
    }

    fun setGain(db: Int) {
        val radio = _state.value.radio
        val max = when (radio) {
            SdrRadio.AIRSPY, SdrRadio.HYDRA -> 21
            SdrRadio.AIRSPYHF -> 48
            else -> 50
        }
        var gain = db.coerceIn(0, max)
        if (radio == SdrRadio.AIRSPYHF) gain = (gain / 6) * 6
        engine.gainTenths = if (_state.value.agc) -1 else gain * 10
        _state.update { it.copy(gainDb = gain) }
    }

    fun setAgc(on: Boolean) {
        engine.gainTenths = if (on) -1 else _state.value.gainDb * 10
        _state.update { it.copy(agc = on) }
    }

    fun listen() {
        val connection = session.connection
        val device = _state.value.devices.find { it.vidPid == _state.value.connectedVidPid }
        if (connection == null || device?.canListen != true) {
            _state.update { it.copy(listenStatus = "Plug in an RTL-SDR, HackRF, Airspy, Airspy HF+, or HydraSDR to listen.") }
            return
        }
        val requestedHz = _state.value.frequencyHz
        val range = SdrBands.tuneRange(session.radio)
        val hz = if (_state.value.allowOutOfBand) requestedHz else requestedHz.coerceIn(range.first, range.last)
        if (hz != requestedHz) session.tune(hz)
        engine.frequencyHz = hz
        engine.mode = _state.value.mode
        engine.sampleRate = _state.value.sampleRate
        engine.fftSize = _state.value.fftSize
        engine.offsetHz = if (hz == requestedHz) _state.value.offsetHz.toDouble() else 0.0
        engine.squelch = _state.value.squelch
        applyEngineSettings()
        val fd = connection.connection.fileDescriptor
        val started = when (device.radioKind()) {
            SdrRadio.HACKRF -> engine.listenHackrf(fd, captureFile)
            SdrRadio.AIRSPY -> engine.listenAirspy(fd, captureFile)
            SdrRadio.AIRSPYHF -> engine.listenAirspyHf(fd, captureFile)
            SdrRadio.HYDRA -> engine.listenHydra(fd, captureFile)
            else -> engine.listenRtl(fd, captureFile)
        }
        if (started) session.resume()
        _state.update { it.copy(listening = started, paused = false, recording = false) }
    }

    fun pauseListen() {
        if (!_state.value.listening) return
        engine.record = false
        session.pause()
        _state.update { it.copy(paused = true, recording = false) }
    }

    fun resumeListen() {
        if (!_state.value.listening) return
        session.resume()
        _state.update { it.copy(paused = false) }
    }

    fun stopListening() {
        session.stop()
        archiveCapture()
        _state.update {
            it.copy(
                listening = false,
                paused = false,
                recording = false,
                hasRecording = captureFile.exists() || recordingsDir.listFiles()?.isNotEmpty() == true,
                recordings = listRecordings()
            )
        }
    }

    fun toggleRecord() {
        val next = !_state.value.recording
        engine.record = next && _state.value.listening
        if (!next) archiveCapture()
        _state.update {
            it.copy(
                recording = next && it.listening,
                hasRecording = captureFile.exists() || recordingsDir.listFiles()?.isNotEmpty() == true,
                recordings = listRecordings()
            )
        }
    }

    private fun configurationLocked(): Boolean {
        if (!_state.value.recording) return false
        _state.update { it.copy(listenStatus = "Stop recording before changing frequency, rate, or mode") }
        return true
    }

    fun playRecording(path: String? = null) {
        val file = path?.let { File(it) }?.takeIf { it.exists() }
            ?: _state.value.recordings.firstOrNull()?.let { File(it.path) }
            ?: captureFile
        engine.fileFormat = _state.value.fileFormat
        engine.repeatPlayback = _state.value.fileRepeat
        val started = engine.play(file)
        _state.update { it.copy(listening = started, recording = false) }
    }

    fun importIq(uri: Uri, name: String) {
        recordingsDir.mkdirs()
        val parsed = SdrBands.parseIqName(name)
        val dest = File(recordingsDir, name.ifBlank { "import.iq" })
        runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
        }
        parsed.first?.let { setFrequency(it) }
        parsed.second?.let { engine.sampleRate = it; _state.update { s -> s.copy(sampleRate = it) } }
        parsed.third?.let { setFileFormat(it) }
        _state.update { it.copy(hasRecording = true, recordings = listRecordings()) }
    }

    fun shareRecording(path: String, onReady: (Uri) -> Unit) {
        val source = File(path)
        if (!source.exists()) return
        val dest = File(appContext.cacheDir, "exports/${source.name}").apply {
            parentFile?.mkdirs()
            source.copyTo(this, overwrite = true)
        }
        onReady(FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", dest))
    }

    fun deleteRecording(path: String) {
        File(path).takeIf { it.exists() }?.delete()
        if (path == captureFile.absolutePath) captureFile.delete()
        _state.update {
            it.copy(
                hasRecording = captureFile.exists() || recordingsDir.listFiles()?.isNotEmpty() == true,
                recordings = listRecordings()
            )
        }
    }

    fun toggleSweep() {
        val running = sweepJob
        if (running != null) {
            running.cancel()
            sweepJob = null
            _state.update { it.copy(sweeping = false) }
            return
        }
        if (!_state.value.listening) {
            _state.update { it.copy(listenStatus = "Start listening before sweeping bands") }
            return
        }
        _state.update { it.copy(sweeping = true) }
        sweepJob = viewModelScope.launch {
            for (band in SdrBands.all) {
                if (!isActive) break
                tuneBand(band)
                delay(700)
            }
            _state.update { it.copy(sweeping = false) }
            sweepJob = null
        }
    }

    fun toggleRemoteId() {
        if (_state.value.remoteScanning) {
            remoteId.stop()
            _state.update { it.copy(remoteScanning = false) }
            return
        }
        val error = remoteId.start { sightings ->
            _state.update { it.copy(remoteIds = sightings) }
        }
        if (error != null) {
            _state.update { it.copy(listenStatus = error, remoteScanning = false) }
        } else {
            _state.update { it.copy(remoteScanning = true, listenStatus = "Scanning OpenDroneID") }
        }
    }

    fun toggleGnssWatch() {
        if (_state.value.gnssWatch != null) {
            gnssWatch.stop()
            _state.update { it.copy(gnssWatch = null) }
            return
        }
        val error = gnssWatch.start { text -> _state.update { it.copy(gnssWatch = text) } }
        if (error != null) _state.update { it.copy(listenStatus = error) }
    }

    fun checkGnss() {
        if (!_state.value.listening || session.connection == null) {
            _state.update { it.copy(listenStatus = "Start listening before the GPS L1 check") }
            return
        }
        engine.gnssCheck = true
    }

    fun addBookmark(name: String, band: Boolean = false) {
        val hz = _state.value.frequencyHz + _state.value.offsetHz
        val label = name.ifBlank { formatMhz(hz) }
        val next = _state.value.bookmarks + SdrBookmark(
            label, hz, _state.value.mode, _state.value.channelBandwidthHz, band
        )
        saveBookmarks(next)
        _state.update { it.copy(bookmarks = next) }
    }

    fun deleteBookmark(bookmark: SdrBookmark) {
        val next = _state.value.bookmarks.filterNot { it == bookmark }
        saveBookmarks(next)
        _state.update { it.copy(bookmarks = next) }
    }

    fun addBandPlans() {
        val existing = _state.value.bookmarks.map { it.name }.toSet()
        val next = _state.value.bookmarks + SdrBands.stationPlans.filter { it.name !in existing }
        saveBookmarks(next)
        _state.update { it.copy(bookmarks = next) }
    }

    fun tuneBookmark(bookmark: SdrBookmark) {
        setMode(bookmark.mode)
        if (bookmark.bandwidthHz > 0) setChannelBandwidth(bookmark.bandwidthHz)
        setFrequency(bookmark.hz)
    }

    fun exportCot(fileName: String = "sdr_detections.cot.xml", onExported: (Uri) -> Unit) {
        val detections = _state.value.detections
        val remotes = _state.value.remoteIds
        if (detections.isEmpty() && remotes.isEmpty()) {
            _state.update { it.copy(listenStatus = "No signals logged yet") }
            return
        }
        val clean = app.radiusrf.core.sessions.ExportNames.withExtension(
            app.radiusrf.core.sessions.ExportNames.clean(fileName, "sdr_detections.cot.xml"),
            "xml"
        )
        val file = File(appContext.cacheDir, "exports/$clean").apply {
            parentFile?.mkdirs()
            writeText(cot(detections, remotes))
        }
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
        onExported(uri)
    }

    private fun watchUsb() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                refresh()
                val attached = intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED
                val connected = _state.value.connectedVidPid
                if (connected != null && _state.value.devices.none { it.vidPid == connected }) {
                    disconnect()
                    return
                }
                if (attached) adoptAttached()
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
        usbReceiver = receiver
    }

    override fun onCleared() {
        usbReceiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        usbReceiver = null
        sweepJob?.cancel()
        remoteId.stop()
        gnssWatch.stop()
    }

    private fun loadBookmarks(): List<SdrBookmark> {
        if (!bookmarksFile.exists()) return emptyList()
        return bookmarksFile.readLines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 3) return@mapNotNull null
            val hz = parts[1].toLongOrNull() ?: return@mapNotNull null
            val mode = SdrMode.entries.firstOrNull { it.name == parts[2] } ?: SdrMode.WFM
            SdrBookmark(
                parts[0],
                hz,
                mode,
                parts.getOrNull(3)?.toIntOrNull() ?: 0,
                parts.getOrNull(4)?.toBooleanStrictOrNull() ?: false
            )
        }
    }

    private fun saveBookmarks(bookmarks: List<SdrBookmark>) {
        bookmarksFile.writeText(
            bookmarks.joinToString("\n") {
                "${it.name}\t${it.hz}\t${it.mode.name}\t${it.bandwidthHz}\t${it.isBand}"
            }
        )
    }

    private fun applyEngineSettings() {
        val state = _state.value
        engine.ppm = state.ppm
        engine.lna = state.lna
        engine.vga = state.vga
        engine.amp = state.amp
        engine.gainTenths = if (state.agc) -1 else state.gainDb * 10
        engine.biasTee = state.biasTee
        engine.directSampling = state.directSampling
        engine.allowOutOfBand = state.allowOutOfBand
        engine.channelBandwidthHz = state.channelBandwidthHz
        engine.waterfallFps = state.waterfallFps
        engine.repeatPlayback = state.fileRepeat
        engine.fileFormat = state.fileFormat
        engine.recordOnSquelch = state.recordOnSquelch
        engine.squelchEnabled = state.squelch > 0
        engine.squelchDb = if (state.squelch > 0) -120f + (state.squelch / 8000f) * 120f else -120f
    }

    private fun archiveCapture() {
        if (!captureFile.exists() || captureFile.length() < 1_024) return
        recordingsDir.mkdirs()
        val radio = _state.value.radio.name.lowercase()
        val hz = _state.value.frequencyHz / 1_000
        val rate = _state.value.sampleRate / 1_000
        val dest = File(recordingsDir, "${radio}_${hz}kHz_${rate}ksps.iq")
        captureFile.copyTo(dest, overwrite = true)
    }

    private fun listRecordings(): List<SdrRecording> {
        val files = buildList {
            if (captureFile.exists()) add(captureFile)
            recordingsDir.listFiles()?.let { addAll(it.filter { file -> file.isFile }) }
        }.distinctBy { it.absolutePath }.sortedByDescending { it.lastModified() }
        return files.map { file ->
            val parsed = SdrBands.parseIqName(file.name)
            SdrRecording(
                name = file.name,
                path = file.absolutePath,
                frequencyHz = parsed.first ?: _state.value.frequencyHz,
                sampleRate = parsed.second ?: _state.value.sampleRate,
                format = parsed.third ?: _state.value.fileFormat,
                bytes = file.length()
            )
        }
    }

    private fun cot(detections: List<SdrDetection>, remotes: List<app.radiusrf.features.sdr.domain.RemoteIdSighting>): String {
        val stamp = iso(System.currentTimeMillis())
        val point = lastFix()
        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            detections.forEach { detection ->
                appendLine(
                    """<event version="2.0" uid="lucid-sdr-${detection.timeMs}" type="b-m-p-s-m" time="$stamp" start="$stamp" stale="$stamp">"""
                )
                appendLine("""  <point lat="${point.first}" lon="${point.second}" hae="0" ce="9999999" le="9999999"/>""")
                appendLine("""  <detail><remarks>${formatMhz(detection.hz)} MHz ${detection.mode.label}</remarks></detail>""")
                appendLine("</event>")
            }
            remotes.forEach { sighting ->
                val lat = sighting.lat?.toString() ?: point.first
                val lon = sighting.lon?.toString() ?: point.second
                appendLine(
                    """<event version="2.0" uid="lucid-rid-${sighting.address}" type="a-n-A-M-F-Q" time="$stamp" start="$stamp" stale="$stamp">"""
                )
                appendLine("""  <point lat="$lat" lon="$lon" hae="0" ce="9999999" le="9999999"/>""")
                appendLine("""  <detail><remarks>Remote ID ${sighting.id}</remarks></detail>""")
                appendLine("</event>")
            }
        }
    }

    private fun lastFix(): Pair<String, String> {
        val allowed = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return "0" to "0"
        val manager = appContext.getSystemService(LocationManager::class.java) ?: return "0" to "0"
        val fix = runCatching { manager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
            ?: runCatching { manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
        return if (fix == null) "0" to "0" else fix.latitude.toString() to fix.longitude.toString()
    }

    private fun iso(timeMs: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(timeMs))
    }

    private fun formatMhz(hz: Long): String = String.format(Locale.US, "%.4f", hz / 1_000_000.0).trimEnd('0').trimEnd('.')

    init {
        observeDemoRequests(demoBus, DemoData.Routes.SDR) { loadDemoData() }
    }

    private fun loadDemoData() {
        _state.update { it.copy(hasUsbHost = true, devices = DemoData.sdrDevices, connectionError = null) }
    }
}
