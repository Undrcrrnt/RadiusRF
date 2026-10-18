package app.radiusrf.features.sdr.viewmodel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.speech.tts.TextToSpeech
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
import app.radiusrf.features.sdr.domain.SdrDsp
import app.radiusrf.features.sdr.domain.SdrLevel
import app.radiusrf.features.sdr.domain.SdrMode
import app.radiusrf.features.sdr.domain.SdrRadio
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
    private val remoteId = RemoteIdWatch(appContext)
    private val gnssWatch = GnssInterference(appContext)
    private var sweepJob: Job? = null
    private val _state = MutableStateFlow(SdrState(hasUsbHost = hardwareChecker.hasUsbHost()))
    val state: StateFlow<SdrState> = _state.asStateFlow()

    private var speech: TextToSpeech? = null
    private var lastSpoken = 0L
    private val bookmarksFile = File(appContext.filesDir, "sdr_bookmarks.txt")
    private val captureFile = File(appContext.cacheDir, "sdr/capture.cu8")

    init {
        refresh()
        _state.update { it.copy(bookmarks = loadBookmarks(), hasRecording = captureFile.exists()) }
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
                    amp = engine.amp
                )
            }
        }
        viewModelScope.launch { engine.spectrum.collect { bins -> _state.update { s -> s.copy(spectrum = bins) } } }
        viewModelScope.launch { engine.waterfall.collect { rows -> _state.update { s -> s.copy(waterfall = rows) } } }
        viewModelScope.launch { engine.status.collect { text -> _state.update { s -> s.copy(listenStatus = text) } } }
        viewModelScope.launch {
            SdrLevel.frequencyHz.collect { hz ->
                _state.update { s -> if (s.frequencyHz == hz) s else s.copy(frequencyHz = hz, offsetHz = 0L) }
            }
        }
        viewModelScope.launch {
            engine.detections.collect { found ->
                _state.update { s -> s.copy(detections = found) }
                maybeSpeak(found.firstOrNull())
            }
        }
    }

    fun refresh() {
        _state.update { it.copy(devices = sdrDetector.detect(), hasRecording = captureFile.exists()) }
    }

    fun connect(device: SdrDeviceInfo) {
        viewModelScope.launch {
            _state.update { it.copy(connectingVidPid = device.vidPid, connectionError = null) }
            val usbDevice = usbConnectionManager.findDevice(device.vendorId, device.productId)
            if (usbDevice == null) {
                _state.update { it.copy(connectingVidPid = null, connectionError = "${device.chipset} is no longer connected") }
                return@launch
            }
            when (val result = usbConnectionManager.requestPermissionAndOpen(
                usbDevice,
                claim = !(device.isRtlSdr || device.isHackrf)
            )) {
                is UsbConnectionResult.Success -> {
                    stopListening()
                    session.connection?.close()
                    session.connection = result.connection
                    session.connectedVidPid = device.vidPid
                    val radio = when {
                        device.isHackrf -> SdrRadio.HACKRF
                        device.isRtlSdr -> SdrRadio.RTL
                        else -> SdrRadio.OTHER
                    }
                    val rate = SdrBands.nearestRate(radio, if (radio == SdrRadio.HACKRF) 8_000_000 else SdrDsp.SAMPLE_RATE)
                    engine.sampleRate = rate
                    engine.signedIq = radio == SdrRadio.HACKRF
                    session.radio = radio
                    _state.update {
                        it.copy(
                            connectingVidPid = null,
                            connectedVidPid = device.vidPid,
                            connectionError = null,
                            radio = radio,
                            sampleRate = rate,
                            listenStatus = when (radio) {
                                SdrRadio.RTL -> "RTL-SDR ready"
                                SdrRadio.HACKRF -> "HackRF ready"
                                else -> "Recognised. Listening in this build is RTL-SDR and HackRF."
                            }
                        )
                    }
                }
                is UsbConnectionResult.Failure -> {
                    _state.update { it.copy(connectingVidPid = null, connectionError = result.reason) }
                }
            }
        }
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
        session.tune(hz)
        _state.update { it.copy(frequencyHz = engine.frequencyHz, offsetHz = 0L) }
    }

    fun nudgeFrequency(deltaHz: Long) {
        if (deltaHz == 0L) return
        setFrequency(_state.value.frequencyHz + deltaHz)
    }

    fun setOffset(hz: Long) {
        val span = _state.value.sampleRate / 2
        val offset = hz.coerceIn(-span.toLong(), span.toLong())
        engine.offsetHz = offset.toDouble()
        _state.update { it.copy(offsetHz = offset) }
    }

    fun setSampleRate(rate: Int) {
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
        val value = level.coerceIn(0, 8000)
        engine.squelch = value
        _state.update { it.copy(squelch = value) }
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
        val rate = if (_state.value.radio == SdrRadio.HACKRF) 8_000_000 else 2_048_000
        setSampleRate(rate)
        setFrequency(band.centerHz)
    }

    fun setMode(mode: SdrMode) {
        engine.mode = mode
        _state.update { it.copy(mode = mode) }
    }

    fun setGain(db: Int) {
        val gain = db.coerceIn(0, 50)
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
        if (connection == null || (device?.isRtlSdr != true && device?.isHackrf != true)) {
            _state.update { it.copy(listenStatus = "Plug in an RTL-SDR or HackRF to listen. Other dongles are recognised only.") }
            return
        }
        engine.frequencyHz = _state.value.frequencyHz
        engine.mode = _state.value.mode
        engine.sampleRate = _state.value.sampleRate
        engine.fftSize = _state.value.fftSize
        engine.offsetHz = _state.value.offsetHz.toDouble()
        engine.squelch = _state.value.squelch
        engine.ppm = _state.value.ppm
        engine.lna = _state.value.lna
        engine.vga = _state.value.vga
        engine.amp = _state.value.amp
        engine.gainTenths = if (_state.value.agc) -1 else _state.value.gainDb * 10
        val started = if (device.isHackrf) {
            engine.listenHackrf(connection.connection.fileDescriptor, captureFile)
        } else {
            engine.listenRtl(connection.connection.fileDescriptor, captureFile)
        }
        engine.hold = false
        _state.update { it.copy(listening = started, paused = false, recording = false) }
    }

    fun pauseListen() {
        if (!_state.value.listening) return
        engine.hold = true
        engine.record = false
        _state.update { it.copy(paused = true, recording = false) }
    }

    fun resumeListen() {
        if (!_state.value.listening) return
        engine.hold = false
        _state.update { it.copy(paused = false) }
    }

    fun stopListening() {
        engine.record = false
        engine.hold = false
        engine.stop()
        _state.update { it.copy(listening = false, paused = false, recording = false, hasRecording = captureFile.exists()) }
    }

    fun toggleRecord() {
        val next = !_state.value.recording
        engine.record = next && _state.value.listening
        _state.update { it.copy(recording = next && it.listening) }
    }

    fun playRecording() {
        val started = engine.play(captureFile)
        _state.update { it.copy(listening = started, recording = false) }
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

    fun addBookmark(name: String) {
        val label = name.ifBlank { formatMhz(_state.value.frequencyHz) }
        val next = _state.value.bookmarks + SdrBookmark(label, _state.value.frequencyHz, _state.value.mode)
        saveBookmarks(next)
        _state.update { it.copy(bookmarks = next) }
    }

    fun tuneBookmark(bookmark: SdrBookmark) {
        setFrequency(bookmark.hz)
        setMode(bookmark.mode)
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

    override fun onCleared() {
        sweepJob?.cancel()
        remoteId.stop()
        gnssWatch.stop()
        speech?.shutdown()
    }

    private fun maybeSpeak(detection: SdrDetection?) {
        if (detection == null || System.currentTimeMillis() - lastSpoken < 12_000) return
        lastSpoken = System.currentTimeMillis()
        val mhz = formatMhz(detection.hz)
        val say = "Signal at $mhz megahertz"
        val current = speech
        if (current == null) {
            speech = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) speech?.speak(say, TextToSpeech.QUEUE_FLUSH, null, "sdr")
            }
        } else {
            current.speak(say, TextToSpeech.QUEUE_FLUSH, null, "sdr")
        }
    }

    private fun loadBookmarks(): List<SdrBookmark> {
        if (!bookmarksFile.exists()) return emptyList()
        return bookmarksFile.readLines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 3) return@mapNotNull null
            val hz = parts[1].toLongOrNull() ?: return@mapNotNull null
            val mode = SdrMode.entries.firstOrNull { it.name == parts[2] } ?: SdrMode.WFM
            SdrBookmark(parts[0], hz, mode)
        }
    }

    private fun saveBookmarks(bookmarks: List<SdrBookmark>) {
        bookmarksFile.writeText(bookmarks.joinToString("\n") { "${it.name}\t${it.hz}\t${it.mode.name}" })
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
