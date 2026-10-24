package app.radiusrf.features.sdr.domain

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

class SdrEngine {
    private val _spectrum = MutableStateFlow(FloatArray(SdrDsp.FFT_N))
    val spectrum: StateFlow<FloatArray> = _spectrum.asStateFlow()

    private val _waterfall = MutableStateFlow<List<FloatArray>>(emptyList())
    val waterfall: StateFlow<List<FloatArray>> = _waterfall.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _detections = MutableStateFlow<List<SdrDetection>>(emptyList())
    val detections: StateFlow<List<SdrDetection>> = _detections.asStateFlow()

    @Volatile var mode: SdrMode = SdrMode.WFM
    @Volatile var frequencyHz: Long = 100_000_000L
    @Volatile var gainTenths: Int = -1
    @Volatile var record: Boolean = false
    @Volatile var hold: Boolean = false
    @Volatile var gnssCheck: Boolean = false
    @Volatile var sampleRate: Int = SdrDsp.SAMPLE_RATE
    @Volatile var signedIq: Boolean = false
    @Volatile var offsetHz: Double = 0.0
    @Volatile var squelch: Int = 0
    @Volatile var ppm: Int = 0
    @Volatile var lna: Int = 16
    @Volatile var vga: Int = 20
    @Volatile var amp: Boolean = false
    @Volatile var audioGain: Float = 1f
    @Volatile var fftSize: Int = SdrDsp.FFT_N
    @Volatile var channelBandwidthHz: Int = SdrBands.defaultBandwidth(SdrMode.WFM)
    @Volatile var waterfallFps: Int = 12
    @Volatile var biasTee: Boolean = false
    @Volatile var directSampling: DirectSampling = DirectSampling.AUTO
    @Volatile var allowOutOfBand: Boolean = false
    @Volatile var repeatPlayback: Boolean = true
    @Volatile var recordOnSquelch: Boolean = false
    @Volatile var fileFormat: IqFileFormat = IqFileFormat.UNSIGNED8
    @Volatile var squelchDb: Float = -80f
    @Volatile var squelchEnabled: Boolean = false
    @Volatile var decodeOnly: Boolean = false
    @Volatile var onIq: ((ByteArray, Int, Boolean, Int, Long) -> Unit)? = null

    private enum class Kind { NONE, RTL, HACKRF, AIRSPY, AIRSPYHF, HYDRA, FILE, TCP }
    private var kind = Kind.NONE

    private val rows = ArrayDeque<FloatArray>()
    @Volatile private var running = false
    private var thread: Thread? = null
    private var audio: AudioTrack? = null
    private var nativeOpen = false
    private var capture: File? = null
    private val rtlTcp = SdrRtlTcp()

    fun listen(fd: Int, captureFile: File): Boolean = listenRtl(fd, captureFile)

    fun listenRtl(fd: Int, captureFile: File): Boolean =
        beginListen(Kind.RTL, signed = false, fd, captureFile, rtlStatus())

    fun listenRtlTcp(host: String, port: Int, captureFile: File): Boolean {
        stop()
        record = false
        kind = Kind.TCP
        signedIq = false
        capture = captureFile
        val error = rtlTcp.connect(host, port) ?: rtlTcp.configure(
            frequencyHz, sampleRate, gainTenths, ppm, biasTee, tcpDirect()
        )
        if (error != null) {
            kind = Kind.NONE
            _status.value = error
            return false
        }
        _status.value = "Listening on RTL-SDR server $host:$port"
        startLoop(fromUsb = true, file = null)
        SdrLevel.update(SdrLevel.levelDb.value, frequencyHz, true)
        return true
    }

    fun listenHackrf(fd: Int, captureFile: File): Boolean =
        beginListen(Kind.HACKRF, signed = true, fd, captureFile, "Listening on HackRF")

    fun listenAirspy(fd: Int, captureFile: File): Boolean =
        beginListen(Kind.AIRSPY, signed = true, fd, captureFile, "Listening on Airspy")

    fun listenAirspyHf(fd: Int, captureFile: File): Boolean =
        beginListen(Kind.AIRSPYHF, signed = true, fd, captureFile, "Listening on Airspy HF+")

    fun listenHydra(fd: Int, captureFile: File): Boolean =
        beginListen(Kind.HYDRA, signed = true, fd, captureFile, "Listening on HydraSDR")

    private fun beginListen(next: Kind, signed: Boolean, fd: Int, captureFile: File, ready: String): Boolean {
        stop()
        record = false
        kind = next
        signedIq = signed
        capture = captureFile
        val error = openNative(fd)
        if (error != null) {
            kind = Kind.NONE
            _status.value = error
            return false
        }
        nativeOpen = true
        val tuned = retune()
        if (tuned != null) {
            closeNative()
            nativeOpen = false
            kind = Kind.NONE
            _status.value = tuned
            return false
        }
        _status.value = ready
        startLoop(fromUsb = true, file = null)
        SdrLevel.update(SdrLevel.levelDb.value, frequencyHz, true)
        return true
    }

    fun play(file: File): Boolean {
        stop()
        record = false
        if (!file.exists() || file.length() < 1024) {
            _status.value = "No IQ recording to play"
            return false
        }
        _status.value = "Playing recording"
        startLoop(fromUsb = false, file = file)
        SdrLevel.update(SdrLevel.levelDb.value, frequencyHz, true)
        return true
    }

    fun stop() {
        hold = false
        record = false
        decodeOnly = false
        onIq = null
        running = false
        thread?.interrupt()
        if (nativeOpen) {
            closeNative()
            nativeOpen = false
        }
        rtlTcp.close()
        kind = Kind.NONE
        thread?.join(1000)
        if (thread?.isAlive == true) {
            _status.value = "Receiver is still stopping"
            return
        }
        thread = null
        audio?.runCatching {
            stop()
            release()
        }
        audio = null
        SdrLevel.update(SdrLevel.levelDb.value, frequencyHz, false)
    }

    fun isRunning(): Boolean = running

    private fun startLoop(fromUsb: Boolean, file: File?) {
        rows.clear()
        _waterfall.value = emptyList()
        _detections.value = emptyList()
        val min = AudioTrack.getMinBufferSize(
            SdrDsp.AUDIO_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SdrDsp.AUDIO_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(max(min, SdrDsp.AUDIO_RATE))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        audio = track
        running = true
        val worker = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            pump(fromUsb, file, track)
        }, "sdr-listen")
        thread = worker
        worker.start()
    }

    private fun pump(fromUsb: Boolean, file: File?, track: AudioTrack) {
        val iq = ByteArray(16 * 16384)
        var bins = FloatArray(fftSize.coerceAtLeast(16))
        var appliedHz = frequencyHz
        var appliedGain = gainTenths
        var appliedRate = sampleRate
        var appliedPpm = ppm
        var appliedLna = lna
        var appliedVga = vga
        var appliedAmp = amp
        var appliedBias = biasTee
        var appliedDirect = directSampling
        var lastSpectrum = 0L
        var lastDetect = 0L
        val opened = if (file != null) file.inputStream() else null
        var playback = opened?.let { inspectPlayback(it) }
        var input = playback?.stream
        val playSigned = playback?.signed ?: signedIq
        val playRate = playback?.rate ?: sampleRate
        playback?.frequencyHz?.let { frequencyHz = it }
        playback?.mode?.let { mode = it }
        playback?.offsetHz?.let { offsetHz = it }
        if (playback != null) SdrLevel.update(SdrLevel.levelDb.value, frequencyHz, true)
        val renderer = AudioRenderer(track)
        try {
            while (running) {
                if (fromUsb && (
                        frequencyHz != appliedHz || gainTenths != appliedGain || sampleRate != appliedRate ||
                            ppm != appliedPpm || lna != appliedLna || vga != appliedVga || amp != appliedAmp ||
                            biasTee != appliedBias || directSampling != appliedDirect
                        )
                ) {
                    appliedHz = frequencyHz
                    appliedGain = gainTenths
                    appliedRate = sampleRate
                    appliedPpm = ppm
                    appliedLna = lna
                    appliedVga = vga
                    appliedAmp = amp
                    appliedBias = biasTee
                    appliedDirect = directSampling
                    val error = retune()
                    if (error != null) {
                        _status.value = error
                        break
                    }
                }
                if (fromUsb && gnssCheck) {
                    gnssCheck = false
                    measureGnss(iq, bins)
                    appliedHz = -1
                    continue
                }
                val n = if (fromUsb) readNative(iq) else {
                    val got = readPlayback(input, iq, playFormat(playback))
                    if (got < 0) {
                        if (repeatPlayback && file != null) {
                            input?.close()
                            val again = file.inputStream()
                            playback = inspectPlayback(again)
                            input = playback.stream
                            continue
                        }
                        break
                    }
                    val paceMs = got / 2 * 1000L / playRate
                    if (paceMs > 0) Thread.sleep(paceMs)
                    got
                }
                if (n < 0) {
                    _status.value = "USB read failed ($n)"
                    break
                }
                if (n < 512) continue
                if (hold) continue
                val rateNow = if (fromUsb) sampleRate else playRate
                val signedNow = if (fromUsb) signedIq else playSigned
                onIq?.invoke(iq, n, signedNow, rateNow, frequencyHz)
                if (decodeOnly) continue
                renderer.submit(iq, n, signedNow, rateNow, fromUsb)
                val now = System.currentTimeMillis()
                val spectrumGap = (1000L / waterfallFps.coerceIn(1, 30))
                if (now - lastSpectrum > spectrumGap) {
                    lastSpectrum = now
                    val size = fftSize.coerceAtLeast(16)
                    if (bins.size != size) {
                        bins = FloatArray(size)
                        rows.clear()
                    }
                    SdrDsp.spectrum(iq, n, bins, signedNow)
                    SdrLevel.update(selectedChannelLevel(bins, rateNow), frequencyHz, true)
                    _spectrum.value = bins.copyOf()
                    rows.addLast(bins.copyOf())
                    while (rows.size > 48) rows.removeFirst()
                    _waterfall.value = rows.toList()
                }
                if (now - lastDetect > 700) {
                    lastDetect = now
                    notePeak(bins, now)
                }
            }
        } catch (_: InterruptedException) {
        } catch (error: Exception) {
            _status.value = error.message ?: "Receiver stopped unexpectedly"
        } finally {
            renderer.close()
            input?.close()
            running = false
            SdrLevel.update(SdrLevel.levelDb.value, frequencyHz, false)
        }
    }

    private fun measureGnss(iq: ByteArray, bins: FloatArray) {
        if (kind == Kind.AIRSPYHF) {
            _status.value = "This receiver cannot tune to GPS L1"
            return
        }
        val l1 = bandPower(1_575_420_000L, iq, bins) ?: return
        val side = bandPower(1_570_000_000L, iq, bins) ?: return
        val delta = l1 - side
        _status.value = if (delta > 8f) {
            "GPS L1 is ${delta.toInt()} dB above the nearby channel"
        } else {
            "GPS L1 is within ${kotlin.math.abs(delta).toInt()} dB of the nearby channel"
        }
    }

    private fun bandPower(hz: Long, iq: ByteArray, bins: FloatArray): Float? {
        val saved = frequencyHz
        frequencyHz = hz
        val tuneError = retune()
        if (tuneError != null) {
            frequencyHz = saved
            _status.value = tuneError
            return null
        }
        var total = 0f
        var count = 0
        repeat(6) {
            val n = readNative(iq)
            if (n >= 512) {
                SdrDsp.spectrum(iq, n, bins, signedIq)
                total += bins.average().toFloat()
                count++
            }
        }
        frequencyHz = saved
        return if (count == 0) -120f else total / count
    }

    private fun openNative(fd: Int): String? = when (kind) {
        Kind.RTL -> NativeRtl.open(fd)
        Kind.HACKRF -> NativeHackrf.open(fd)
        Kind.AIRSPY -> NativeAirspy.open(fd)
        Kind.AIRSPYHF -> NativeAirspyHf.open(fd)
        Kind.HYDRA -> NativeHydra.open(fd)
        else -> "No receiver"
    }

    private fun closeNative() {
        when (kind) {
            Kind.RTL -> NativeRtl.close()
            Kind.HACKRF -> NativeHackrf.close()
            Kind.AIRSPY -> NativeAirspy.close()
            Kind.AIRSPYHF -> NativeAirspyHf.close()
            Kind.HYDRA -> NativeHydra.close()
            else -> Unit
        }
    }

    private fun readNative(iq: ByteArray): Int = when (kind) {
        Kind.HACKRF -> NativeHackrf.read(iq)
        Kind.AIRSPY -> NativeAirspy.read(iq)
        Kind.AIRSPYHF -> NativeAirspyHf.read(iq)
        Kind.HYDRA -> NativeHydra.read(iq)
        Kind.TCP -> rtlTcp.read(iq)
        else -> NativeRtl.read(iq)
    }

    private fun retune(): String? {
        val preamp = if (amp) 1 else 0
        val bias = if (biasTee) 1 else 0
        return when (kind) {
            Kind.RTL -> NativeRtl.configure(frequencyHz, sampleRate, gainTenths, ppm, bias, rtlDirect())
            Kind.HACKRF -> NativeHackrf.configure(frequencyHz, sampleRate, lna, vga, preamp, bias)
            Kind.AIRSPY -> NativeAirspy.configure(frequencyHz, sampleRate, gainTenths, bias)
            Kind.AIRSPYHF -> NativeAirspyHf.configure(frequencyHz, sampleRate, gainTenths, preamp, bias)
            Kind.HYDRA -> NativeHydra.configure(frequencyHz, sampleRate, gainTenths, bias)
            Kind.TCP -> rtlTcp.configure(frequencyHz, sampleRate, gainTenths, ppm, biasTee, tcpDirect())
            else -> null
        }
    }

    private fun rtlDirect(): Int = when (directSampling) {
        DirectSampling.OFF -> 0
        DirectSampling.I -> 1
        DirectSampling.Q -> 2
        DirectSampling.AUTO -> -1
    }

    private fun tcpDirect(): Int = when (directSampling) {
        DirectSampling.I -> 1
        DirectSampling.Q -> 2
        DirectSampling.AUTO -> if (frequencyHz < 24_000_000L) 1 else 0
        DirectSampling.OFF -> 0
    }

    private fun rtlStatus(): String = when (directSampling) {
        DirectSampling.I, DirectSampling.Q -> "Listening, direct sampling"
        DirectSampling.AUTO -> if (frequencyHz < 24_000_000L) "Listening, direct sampling" else "Listening"
        DirectSampling.OFF -> "Listening"
    }

    private fun playFormat(playback: Playback?): IqFileFormat = when {
        playback?.signed == true -> IqFileFormat.SIGNED8
        playback != null -> IqFileFormat.UNSIGNED8
        else -> fileFormat
    }

    private fun readPlayback(input: java.io.InputStream?, dest: ByteArray, format: IqFileFormat): Int {
        val stream = input ?: return -1
        return if (format == IqFileFormat.SIGNED16) {
            val wide = ByteArray(dest.size * 2)
            val got = stream.read(wide)
            if (got < 4) return -1
            val pairs = got / 4
            for (i in 0 until pairs) {
                dest[i * 2] = wide[i * 4 + 1]
                dest[i * 2 + 1] = wide[i * 4 + 3]
            }
            pairs * 2
        } else {
            stream.read(dest)
        }
    }

    private fun selectedChannelLevel(bins: FloatArray, rate: Int): Float {
        if (bins.isEmpty()) return -120f
        val selected = (((offsetHz + rate / 2.0) / rate) * bins.size)
            .toInt().coerceIn(0, bins.lastIndex)
        val values = (selected - 2..selected + 2)
            .filter { it in bins.indices && (it != bins.size / 2 || selected != bins.size / 2) }
            .map { bins[it] }
        return if (values.isEmpty()) bins[selected] else values.average().toFloat()
    }

    private fun writeHeader(out: FileOutputStream) {
        val header = ByteArray(40)
        header[0] = 'L'.code.toByte()
        header[1] = 'E'.code.toByte()
        header[2] = 'I'.code.toByte()
        header[3] = 'Q'.code.toByte()
        header[4] = if (signedIq) 1 else 0
        header[5] = 2
        header[6] = header.size.toByte()
        val rate = sampleRate
        header[8] = (rate and 0xFF).toByte()
        header[9] = ((rate ushr 8) and 0xFF).toByte()
        header[10] = ((rate ushr 16) and 0xFF).toByte()
        header[11] = ((rate ushr 24) and 0xFF).toByte()
        putLongLe(header, 12, frequencyHz)
        header[20] = mode.ordinal.toByte()
        putLongLe(header, 21, java.lang.Double.doubleToLongBits(offsetHz))
        out.write(header)
    }

    private class Playback(
        val stream: java.io.InputStream,
        val signed: Boolean,
        val rate: Int,
        val frequencyHz: Long? = null,
        val mode: SdrMode? = null,
        val offsetHz: Double? = null
    )

    private fun inspectPlayback(input: java.io.InputStream): Playback {
        val header = ByteArray(16)
        val got = input.read(header)
        val magic = got == 16 &&
            header[0] == 'L'.code.toByte() &&
            header[1] == 'E'.code.toByte() &&
            header[2] == 'I'.code.toByte() &&
            header[3] == 'Q'.code.toByte()
        if (magic) {
            var rate = header[8].toInt() and 0xFF
            rate = rate or ((header[9].toInt() and 0xFF) shl 8)
            rate = rate or ((header[10].toInt() and 0xFF) shl 16)
            rate = rate or ((header[11].toInt() and 0xFF) shl 24)
            if (header[5].toInt() >= 2) {
                val headerSize = header[6].toInt() and 0xFF
                val rest = ByteArray((headerSize - header.size).coerceAtLeast(0))
                var offset = 0
                while (offset < rest.size) {
                    val n = input.read(rest, offset, rest.size - offset)
                    if (n < 0) break
                    offset += n
                }
                if (offset == rest.size) {
                    val full = header + rest
                    val recordedMode = SdrMode.entries.getOrNull(full[20].toInt() and 0xFF)
                    return Playback(
                        input,
                        header[4].toInt() == 1,
                        rate.coerceAtLeast(SdrDsp.AUDIO_RATE),
                        getLongLe(full, 12),
                        recordedMode,
                        java.lang.Double.longBitsToDouble(getLongLe(full, 21))
                    )
                }
            }
            return Playback(input, header[4].toInt() == 1, rate.coerceAtLeast(SdrDsp.AUDIO_RATE))
        }
        val prefix = if (got > 0) java.io.ByteArrayInputStream(header, 0, got) else java.io.ByteArrayInputStream(ByteArray(0))
        return Playback(java.io.SequenceInputStream(prefix, input), false, SdrDsp.SAMPLE_RATE)
    }

    private fun putLongLe(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) bytes[offset + i] = ((value ushr (i * 8)) and 0xFF).toByte()
    }

    private fun getLongLe(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = value or ((bytes[offset + i].toLong() and 0xFF) shl (i * 8))
        return value
    }

    private class AudioFrame(
        val iq: ByteArray,
        val n: Int,
        val signed: Boolean,
        val rate: Int,
        val live: Boolean
    )

    private inner class AudioRenderer(private val track: AudioTrack) {
        private val lock = Object()
        private var incoming = ByteArray(0)
        private var processing = ByteArray(0)
        private var length = 0
        private var signedSamples = false
        private var rate = 0
        private var live = false
        private var pending = false
        @Volatile private var running = true
        private val demod = DemodState()
        private var pcm = ShortArray(4096)
        private var recorder: FileOutputStream? = null

        private val thread = Thread({
            while (running) {
                val frame = try {
                    synchronized(lock) {
                        while (running && !pending) lock.wait()
                        if (!running) null
                        else {
                            pending = false
                            val swap = processing
                            processing = incoming
                            incoming = swap
                            AudioFrame(processing, length, signedSamples, rate, live)
                        }
                    }
                } catch (_: InterruptedException) {
                    null
                } ?: break
                runCatching { render(frame.iq, frame.n, frame.signed, frame.rate, frame.live) }
                    .onFailure { error -> _status.value = error.message ?: "Audio stopped" }
            }
        }, "sdr-audio").also {
            it.isDaemon = true
            it.start()
        }

        fun submit(iq: ByteArray, n: Int, signedNow: Boolean, rateNow: Int, liveNow: Boolean) {
            if (!running || n <= 0) return
            synchronized(lock) {
                if (!running || pending) return
                if (incoming.size < n) incoming = ByteArray(n)
                System.arraycopy(iq, 0, incoming, 0, n)
                length = n
                signedSamples = signedNow
                rate = rateNow
                live = liveNow
                pending = true
                lock.notifyAll()
            }
        }

        fun close() {
            running = false
            synchronized(lock) { lock.notifyAll() }
            thread.join(500)
            recorder?.close()
            recorder = null
        }

        private fun render(iq: ByteArray, n: Int, signedNow: Boolean, rateNow: Int, liveNow: Boolean) {
            if (pcm.size < n) pcm = ShortArray(n)
            val audioN = SdrDsp.demod(
                iq, n, mode, demod, pcm, rateNow, signedNow, offsetHz, channelBandwidthHz
            )
            val open = !squelchEnabled || SdrDsp.channelPowerDb(demod) >= squelchDb
            if (liveNow && record) {
                if (recorder == null) {
                    recorder = capture?.let { dest ->
                        dest.parentFile?.mkdirs()
                        FileOutputStream(dest, false).also { writeHeader(it) }
                    }
                }
                if (open || !recordOnSquelch) recorder?.write(iq, 0, n)
            } else if (recorder != null) {
                recorder?.close()
                recorder = null
            }
            if (audioN > 0) {
                if (!open) java.util.Arrays.fill(pcm, 0, audioN, 0.toShort())
                track.setVolume(audioGain.coerceIn(0f, 1f))
                track.write(pcm, 0, audioN)
            }
        }
    }

    private fun notePeak(bins: FloatArray, now: Long) {
        var peak = 0
        var loud = bins[0]
        val sorted = bins.copyOf().apply { sort() }
        val noise = sorted[sorted.size / 2]
        for (i in bins.indices) {
            if (bins[i] > loud) {
                loud = bins[i]
                peak = i
            }
        }
        if (loud - noise < 12f) return
        val hz = SdrDsp.binFrequency(frequencyHz, peak, sampleRate, bins.size)
        val recent = _detections.value.any { kotlin.math.abs(it.hz - hz) < 50_000 && now - it.timeMs < 8_000 }
        if (recent) return
        _detections.value = (listOf(SdrDetection(hz, loud, now, mode)) + _detections.value).take(40)
    }
}
