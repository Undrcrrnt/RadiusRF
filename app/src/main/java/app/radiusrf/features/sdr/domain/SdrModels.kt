package app.radiusrf.features.sdr.domain

data class SdrDeviceInfo(
    val vendorId: Int,
    val productId: Int,
    val deviceName: String,
    val chipset: String,
    val isRtlSdr: Boolean,
    val isHackrf: Boolean = false,
    val isAirspy: Boolean = false,
    val isAirspyHf: Boolean = false,
    val isHydra: Boolean = false
) {
    val vidPid: String get() = "%04X:%04X".format(vendorId, productId)
    val canListen: Boolean get() = isRtlSdr || isHackrf || isAirspy || isAirspyHf || isHydra

    fun radioKind(): SdrRadio = when {
        isHackrf -> SdrRadio.HACKRF
        isAirspy -> SdrRadio.AIRSPY
        isAirspyHf -> SdrRadio.AIRSPYHF
        isHydra -> SdrRadio.HYDRA
        isRtlSdr -> SdrRadio.RTL
        else -> SdrRadio.OTHER
    }
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
    val biasTee: Boolean = false,
    val directSampling: DirectSampling = DirectSampling.AUTO,
    val allowOutOfBand: Boolean = false,
    val channelBandwidthHz: Int = SdrBands.defaultBandwidth(SdrMode.WFM),
    val waterfallFps: Int = 12,
    val fileRepeat: Boolean = true,
    val fileFormat: IqFileFormat = IqFileFormat.UNSIGNED8,
    val rtlTcpHost: String = "192.168.1.1",
    val rtlTcpPort: Int = 1234,
    val recordOnSquelch: Boolean = false,
    val recordings: List<SdrRecording> = emptyList(),
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
