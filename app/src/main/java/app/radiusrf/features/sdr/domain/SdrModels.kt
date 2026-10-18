package app.radiusrf.features.sdr.domain

data class SdrDeviceInfo(
    val vendorId: Int,
    val productId: Int,
    val deviceName: String,
    val chipset: String,
    val isRtlSdr: Boolean,
    val isHackrf: Boolean = false
) {
    val vidPid: String get() = "%04X:%04X".format(vendorId, productId)
}

data class SdrState(
    val devices: List<SdrDeviceInfo> = emptyList(),
    val hasUsbHost: Boolean = false,
    val connectingVidPid: String? = null,
    val connectedVidPid: String? = null,
    val connectionError: String? = null,
    val listening: Boolean = false,
    val paused: Boolean = false,
    val frequencyHz: Long = 100_000_000L,
    val mode: SdrMode = SdrMode.WFM,
    val gainDb: Int = 20,
    val agc: Boolean = true,
    val radio: SdrRadio = SdrRadio.NONE,
    val sampleRate: Int = SdrDsp.SAMPLE_RATE,
    val fftSize: Int = SdrDsp.FFT_N,
    val offsetHz: Long = 0L,
    val squelch: Int = 0,
    val ppm: Int = 0,
    val lna: Int = 16,
    val vga: Int = 20,
    val amp: Boolean = false,
    val sweeping: Boolean = false,
    val remoteIds: List<RemoteIdSighting> = emptyList(),
    val remoteScanning: Boolean = false,
    val gnssWatch: String? = null,
    val recording: Boolean = false,
    val spectrum: FloatArray = FloatArray(SdrDsp.FFT_N),
    val waterfall: List<FloatArray> = emptyList(),
    val detections: List<SdrDetection> = emptyList(),
    val bookmarks: List<SdrBookmark> = emptyList(),
    val listenStatus: String? = null,
    val hasRecording: Boolean = false
)
