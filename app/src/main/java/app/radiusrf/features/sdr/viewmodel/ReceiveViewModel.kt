package app.radiusrf.features.sdr.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radiusrf.features.sdr.domain.AcarsDecoder
import app.radiusrf.features.sdr.domain.AdsbDecoder
import app.radiusrf.features.sdr.domain.AisDecoder
import app.radiusrf.features.sdr.domain.AprsDecoder
import app.radiusrf.features.sdr.domain.IsmBands
import app.radiusrf.features.sdr.domain.IsmDecoder
import app.radiusrf.features.sdr.domain.PocsagDecoder
import app.radiusrf.features.sdr.domain.SdrMode
import app.radiusrf.features.sdr.domain.SdrSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

enum class ReceiveApp(val label: String, val blurb: String) {
    AUDIO("Audio", "WFM, NFM, AM, USB, and LSB"),
    SCAN("Scan", "Hop bookmarks and hold when the squelch opens"),
    GLASS("Glass", "Step across the band list on the waterfall"),
    AIRCRAFT("Aircraft", "1090 MHz Mode S"),
    SHIPS("Ships", "AIS on 162.025 or 161.975"),
    APRS("APRS", "1200 baud packet on 144.390"),
    PAGER("Pager", "POCSAG numeric and text"),
    ACARS("ACARS", "VHF aircraft messages on 131.550"),
    SUBGHZ("SubGHz", "315, 433, 868, and 915 MHz bursts"),
    FT8("FT8", "USB on a band's FT8 frequency"),
    DRONES("Drones", "OpenDroneID Bluetooth broadcasts"),
    CAPTURE("Capture", "Record and play IQ")
}

data class ReceiveHit(
    val title: String,
    val detail: String,
    val lat: Double? = null,
    val lon: Double? = null
)

data class ReceiveUiState(
    val app: ReceiveApp = ReceiveApp.AUDIO,
    val hits: List<ReceiveHit> = emptyList(),
    val scanning: Boolean = false,
    val pagerBaud: Int = 1200,
    val note: String = ReceiveApp.AUDIO.blurb
)

@HiltViewModel
class ReceiveViewModel @Inject constructor(
    private val session: SdrSession
) : ViewModel() {
    private val _state = MutableStateFlow(ReceiveUiState())
    val state: StateFlow<ReceiveUiState> = _state.asStateFlow()

    private val app = AtomicReference(ReceiveApp.AUDIO)
    private val baud = AtomicReference(1200)
    private val busy = AtomicBoolean(false)
    private val even = HashMap<String, Pair<Int, Int>>()
    private val odd = HashMap<String, Pair<Int, Int>>()
    private val sink: (ByteArray, Int, Boolean, Int, Long) -> Unit = { iq, n, signed, rate, hz ->
        offer(iq, n, signed, rate, hz)
    }

    fun select(next: ReceiveApp) {
        app.set(next)
        _state.update { it.copy(app = next, hits = emptyList(), scanning = false, note = next.blurb) }
        even.clear()
        odd.clear()
    }

    fun setPagerBaud(next: Int) {
        baud.set(next)
        _state.update { it.copy(pagerBaud = next) }
    }

    fun setScanning(on: Boolean) {
        _state.update { it.copy(scanning = on) }
    }

    fun arm() {
        session.engine.decodeOnly = false
        session.engine.onIq = sink
    }

    fun disarm() {
        if (session.engine.onIq === sink) session.engine.onIq = null
    }

    private fun offer(iq: ByteArray, n: Int, signed: Boolean, rate: Int, hz: Long) {
        if (n < 64 || !busy.compareAndSet(false, true)) return
        val copy = iq.copyOf(n)
        val chosen = app.get()
        val pager = baud.get()
        viewModelScope.launch {
            try {
                val found = withContext(Dispatchers.Default) {
                    read(chosen, copy, n, signed, rate, hz, pager)
                }
                if (found.isNotEmpty()) {
                    _state.update { state ->
                        if (state.app != chosen) state
                        else state.copy(hits = (found + state.hits).distinctBy { it.title to it.detail }.take(40))
                    }
                }
            } finally {
                busy.set(false)
            }
        }
    }

    private fun read(
        chosen: ReceiveApp,
        iq: ByteArray,
        n: Int,
        signed: Boolean,
        rate: Int,
        hz: Long,
        pager: Int
    ): List<ReceiveHit> = when (chosen) {
        ReceiveApp.AIRCRAFT -> AdsbDecoder.decode(iq, n, signed, rate, even, odd).map { frame ->
            val place = listOfNotNull(frame.callsign, frame.altitudeFt?.let { "$it ft" }).joinToString(" · ")
            ReceiveHit(frame.icao, place.ifBlank { "Mode S" }, frame.lat, frame.lon)
        }
        ReceiveApp.SHIPS -> AisDecoder.demod(iq, n, signed, rate).mapNotNull { AisDecoder.parse(it) }.map { vessel ->
            val place = vessel.name ?: "MMSI ${vessel.mmsi}"
            ReceiveHit(vessel.mmsi, place, vessel.lat, vessel.lon)
        }
        ReceiveApp.APRS -> AprsDecoder.decodeIq(iq, n, signed, rate).map { packet ->
            val via = if (packet.path.isBlank()) packet.to else "${packet.to} via ${packet.path}"
            ReceiveHit(packet.from, "$via  ${packet.info}")
        }
        ReceiveApp.PAGER -> PocsagDecoder.decodeIq(iq, n, signed, rate, pager).map { message ->
            ReceiveHit(message.address.toString(), message.text)
        }
        ReceiveApp.ACARS -> AcarsDecoder.decodeIq(iq, n, signed, rate).map { ReceiveHit("ACARS", it.text) }
        ReceiveApp.SUBGHZ -> {
            val train = IsmDecoder.extract(iq, n, signed, rate) ?: return emptyList()
            val burst = IsmDecoder.classify(train, hz)
            listOf(ReceiveHit(burst.family, "${burst.modulation} · ${burst.pulses} pulses · ${burst.bits.orEmpty()}"))
        }
        else -> emptyList()
    }

    override fun onCleared() {
        disarm()
    }
}

data class ReceiveTune(
    val hz: Long,
    val mode: SdrMode,
    val bandwidth: Int,
    val rate: Int,
    val amp: Boolean
)

fun ReceiveApp.readsIq(): Boolean = when (this) {
    ReceiveApp.AIRCRAFT, ReceiveApp.SHIPS, ReceiveApp.APRS,
    ReceiveApp.PAGER, ReceiveApp.ACARS, ReceiveApp.SUBGHZ -> true
    else -> false
}

fun ReceiveApp.tune(): ReceiveTune? = when (this) {
    ReceiveApp.AIRCRAFT -> ReceiveTune(1_090_000_000L, SdrMode.AM, 2_000_000, 2_048_000, amp = true)
    ReceiveApp.SHIPS -> ReceiveTune(AisDecoder.CHANNEL_B, SdrMode.NFM, 16_000, 1_024_000, amp = false)
    ReceiveApp.APRS -> ReceiveTune(144_390_000L, SdrMode.NFM, 12_500, 1_024_000, amp = false)
    ReceiveApp.PAGER -> ReceiveTune(152_180_000L, SdrMode.NFM, 12_500, 1_024_000, amp = false)
    ReceiveApp.ACARS -> ReceiveTune(AcarsDecoder.MHZ, SdrMode.AM, 10_000, 1_024_000, amp = false)
    ReceiveApp.SUBGHZ -> ReceiveTune(IsmBands.all[1].hz, SdrMode.NFM, 200_000, 1_024_000, amp = false)
    ReceiveApp.FT8 -> ReceiveTune(14_074_000L, SdrMode.USB, 2_700, 1_024_000, amp = false)
    else -> null
}
