package app.radiusrf.features.sdr.domain

import android.content.Context
import app.radiusrf.core.usb.UsbConnectionManager
import app.radiusrf.core.usb.UsbConnectionResult
import app.radiusrf.core.usb.UsbOpenConnection
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** SDR listen session that stays open when Fox Hunt is on screen. */
@Singleton
class SdrSession @Inject constructor(
    @ApplicationContext private val context: Context,
    private val usb: UsbConnectionManager,
    private val detector: SdrDetector
) {
    val engine = SdrEngine()
    var connection: UsbOpenConnection? = null
    var connectedVidPid: String? = null
    var radio: SdrRadio = SdrRadio.NONE
    private val _paused = MutableStateFlow(false)
    val pausedFlow: StateFlow<Boolean> = _paused.asStateFlow()
    var paused: Boolean
        get() = _paused.value
        private set(value) { _paused.value = value }

    private val captureFile = File(context.cacheDir, "sdr/capture.cu8")
    private var opening = false

    /** Opens a plugged-in receiver and starts listening. Resumes a pause. */
    suspend fun start(): String? {
        if (engine.isRunning()) {
            engine.hold = false
            paused = false
            return null
        }
        if (opening) return null
        opening = true
        try {
            val device = detector.detect().firstOrNull { it.canListen }
                ?: return "Plug in an RTL-SDR, HackRF, Airspy, Airspy HF+, or HydraSDR"
            if (connection == null || connectedVidPid != device.vidPid) {
                val usbDevice = usb.findDevice(device.vendorId, device.productId)
                    ?: return "${device.chipset} is no longer connected"
                when (val result = usb.requestPermissionAndOpen(usbDevice, claim = false)) {
                    is UsbConnectionResult.Success -> {
                        connection?.close()
                        connection = result.connection
                        connectedVidPid = device.vidPid
                    }
                    is UsbConnectionResult.Failure -> return result.reason
                }
            }
            val open = connection ?: return "The receiver did not open"
            radio = device.radioKind()
            engine.sampleRate = SdrBands.nearestRate(radio, SdrBands.preferredRate(radio))
            engine.signedIq = radio != SdrRadio.RTL
            tune(SdrLevel.frequencyHz.value)
            engine.hold = false
            paused = false
            val fd = open.connection.fileDescriptor
            val started = when (radio) {
                SdrRadio.HACKRF -> engine.listenHackrf(fd, captureFile)
                SdrRadio.AIRSPY -> engine.listenAirspy(fd, captureFile)
                SdrRadio.AIRSPYHF -> engine.listenAirspyHf(fd, captureFile)
                SdrRadio.HYDRA -> engine.listenHydra(fd, captureFile)
                else -> engine.listenRtl(fd, captureFile)
            }
            return if (started) null else engine.status.value ?: "The receiver did not start"
        } finally {
            opening = false
        }
    }

    fun pause() {
        if (!engine.isRunning()) return
        engine.hold = true
        engine.record = false
        paused = true
    }

    fun resume() {
        if (!engine.isRunning()) return
        engine.hold = false
        paused = false
    }

    fun stop() {
        paused = false
        engine.record = false
        engine.stop()
    }

    fun tune(hz: Long) {
        val range = SdrBands.tuneRange(radio)
        val tuned = if (engine.allowOutOfBand) hz else hz.coerceIn(range.first, range.last)
        engine.frequencyHz = tuned
        engine.offsetHz = 0.0
        SdrLevel.setFrequency(tuned)
    }
}
