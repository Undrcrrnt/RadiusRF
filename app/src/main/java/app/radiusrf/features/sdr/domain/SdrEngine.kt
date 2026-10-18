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
    @Volatile var fftSize: Int = SdrDsp.FFT_N

    private enum class Kind { NONE, RTL, HACKRF, FILE }
    private var kind = Kind.NONE

    private val rows = ArrayDeque<FloatArray>()
    private var running = false
    private var thread: Thread? = null
    private var audio: AudioTrack? = null
    private var nativeOpen = false
    private var capture: File? = null

    fun listen(fd: Int, captureFile: File): Boolean = listenRtl(fd, captureFile)

    fun listenRtl(fd: Int, captureFile: File): Boolean {
        stop()
        record = false
        kind = Kind.RTL
        signedIq = false
        capture = captureFile
        val error = NativeRtl.open(fd)
        if (error != null) {
            _status.value = error
            return false
        }
        nativeOpen = true
        val tuned = retune()
        if (tuned != null) {
            NativeRtl.close()
            nativeOpen = false
            _status.value = tuned
            return false
        }
        _status.value = if (frequencyHz < 24_000_000L) "Listening, direct sampling" else "Listening"
        startLoop(fromUsb = true, file = null)
        SdrLevel.update(SdrLevel.levelDb.value, frequencyHz, true)
        return true
    }

    fun listenHackrf(fd: Int, captureFile: File): Boolean {
        stop()
        record = false
        kind = Kind.HACKRF
        signedIq = true
        capture = captureFile
        val error = NativeHackrf.open(fd)
        if (error != null) {
            kind = Kind.NONE
            _status.value = error
            return false
        }
        nativeOpen = true
        val tuned = retune()
        if (tuned != null) {
            NativeHackrf.close()
            nativeOpen = false
            kind = Kind.NONE
            _status.value = tuned
            return false
        }
        _status.value = "Listening on HackRF"
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
        return true
    }

    fun stop() {
        hold = false
        running = false
        thread?.join(2500)
        thread = null
        audio?.runCatching {
            stop()
            release()
        }
        audio = null
        if (nativeOpen) {
            when (kind) {
                Kind.HACKRF -> NativeHackrf.close()
                Kind.RTL -> NativeRtl.close()
                else -> Unit
            }
            nativeOpen = false
            kind = Kind.NONE
        }
        SdrLevel.update(SdrLevel.levelDb.value, frequencyHz, false)
    }

    fun isRunning(): Boolean = running

    private fun startLoop(fromUsb: Boolean, file: File?) {
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
        val pcm = ShortArray(iq.size / 2 / SdrDsp.DECIM + 8)
        var bins = FloatArray(fftSize.coerceAtLeast(16))
        val demod = DemodState()
        var appliedHz = frequencyHz
        var appliedGain = gainTenths
        var appliedRate = sampleRate
        var appliedPpm = ppm
        var appliedLna = lna
        var appliedVga = vga
        var appliedAmp = amp
        var lastSpectrum = 0L
        var lastDetect = 0L
        val opened = if (file != null) file.inputStream() else null
        val playback = opened?.let { inspectPlayback(it) }
        val input = playback?.stream
        val playSigned = playback?.signed ?: signedIq
        val playRate = playback?.rate ?: sampleRate
        val recorder = if (fromUsb) capture?.let { dest ->
            dest.parentFile?.mkdirs()
            FileOutputStream(dest, false).also { writeHeader(it) }
        } else null
        try {
            while (running) {
                if (fromUsb && (
                        frequencyHz != appliedHz || gainTenths != appliedGain || sampleRate != appliedRate ||
                            ppm != appliedPpm || lna != appliedLna || vga != appliedVga || amp != appliedAmp
                        )
                ) {
                    appliedHz = frequencyHz
                    appliedGain = gainTenths
                    appliedRate = sampleRate
                    appliedPpm = ppm
                    appliedLna = lna
                    appliedVga = vga
                    appliedAmp = amp
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
                val n = if (fromUsb) {
                    if (kind == Kind.HACKRF) NativeHackrf.read(iq) else NativeRtl.read(iq)
                } else {
                    val got = input?.read(iq) ?: -1
                    if (got < 0) break
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
                if (fromUsb && record) recorder?.write(iq, 0, n)
                val rateNow = if (fromUsb) sampleRate else playRate
                val signedNow = if (fromUsb) signedIq else playSigned
                val audioN = SdrDsp.demod(iq, n, mode, demod, pcm, rateNow, signedNow, offsetHz)
                if (audioN > 0) {
                    if (squelch > 0) {
                        var energy = 0
                        for (i in 0 until audioN) energy += kotlin.math.abs(pcm[i].toInt())
                        if (energy / audioN < squelch) java.util.Arrays.fill(pcm, 0, audioN, 0.toShort())
                    }
                    track.write(pcm, 0, audioN)
                }
                val now = System.currentTimeMillis()
                if (now - lastSpectrum > 90) {
                    lastSpectrum = now
                    val size = fftSize.coerceAtLeast(16)
                    if (bins.size != size) {
                        bins = FloatArray(size)
                        rows.clear()
                    }
                    SdrDsp.spectrum(iq, n, bins, signedNow)
                    SdrLevel.update(bins[bins.size / 2], frequencyHz, fromUsb)
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
        } finally {
            input?.close()
            recorder?.close()
            running = false
        }
    }

    private fun measureGnss(iq: ByteArray, bins: FloatArray) {
        val l1 = bandPower(1_575_420_000L, iq, bins)
        val side = bandPower(1_570_000_000L, iq, bins)
        val delta = l1 - side
        _status.value = if (delta > 8f) {
            "GPS L1 is ${delta.toInt()} dB above the nearby channel"
        } else {
            "GPS L1 is within ${kotlin.math.abs(delta).toInt()} dB of the nearby channel"
        }
    }

    private fun bandPower(hz: Long, iq: ByteArray, bins: FloatArray): Float {
        val saved = frequencyHz
        frequencyHz = hz
        retune()
        var total = 0f
        var count = 0
        repeat(6) {
            val n = if (kind == Kind.HACKRF) NativeHackrf.read(iq) else NativeRtl.read(iq)
            if (n >= 512) {
                SdrDsp.spectrum(iq, n, bins, signedIq)
                total += bins.average().toFloat()
                count++
            }
        }
        frequencyHz = saved
        return if (count == 0) -120f else total / count
    }

    private fun retune(): String? = when (kind) {
        Kind.RTL -> NativeRtl.configure(frequencyHz, sampleRate, gainTenths, ppm)
        Kind.HACKRF -> NativeHackrf.configure(frequencyHz, sampleRate, lna, vga, if (amp) 1 else 0)
        else -> null
    }

    private fun writeHeader(out: FileOutputStream) {
        val header = ByteArray(16)
        header[0] = 'L'.code.toByte()
        header[1] = 'E'.code.toByte()
        header[2] = 'I'.code.toByte()
        header[3] = 'Q'.code.toByte()
        header[4] = if (signedIq) 1 else 0
        val rate = sampleRate
        header[8] = (rate and 0xFF).toByte()
        header[9] = ((rate ushr 8) and 0xFF).toByte()
        header[10] = ((rate ushr 16) and 0xFF).toByte()
        header[11] = ((rate ushr 24) and 0xFF).toByte()
        out.write(header)
    }

    private class Playback(val stream: java.io.InputStream, val signed: Boolean, val rate: Int)

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
            return Playback(input, header[4].toInt() == 1, rate.coerceAtLeast(SdrDsp.AUDIO_RATE))
        }
        val prefix = if (got > 0) java.io.ByteArrayInputStream(header, 0, got) else java.io.ByteArrayInputStream(ByteArray(0))
        return Playback(java.io.SequenceInputStream(prefix, input), false, SdrDsp.SAMPLE_RATE)
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
