package app.radiusrf.features.wifi.domain

import android.content.Context
import com.emptyset.detector.radio.RadioBackend
import com.emptyset.detector.radio.RadioCatalog
import com.emptyset.detector.radio.RadioFactory
import com.emptyset.detector.radio.RadioKind
import com.emptyset.detector.radio.UsbDeviceFinder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class UsbWifiFrame(
    val bytes: ByteArray,
    val channel: Int?,
    val rssi: Int?
)

/**
 * One receive-only capture shared by every tool that opts into an external adapter.
 * The phone radio is not touched here.
 */
@Singleton
class UsbWifiSession @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var clients = 0
    private var dead = false
    private var backend: RadioBackend? = null
    private var job: Job? = null
    private var runningKind: RadioKind? = null

    private val _frames = MutableSharedFlow<UsbWifiFrame>(extraBufferCapacity = 256)
    val frames: SharedFlow<UsbWifiFrame> = _frames.asSharedFlow()

    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure.asStateFlow()

    private val _channel = MutableStateFlow(0)
    val channel: StateFlow<Int> = _channel.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    var adapterTitle: String? = null
        private set

    private fun plugInHint(kind: RadioKind): String = when (kind) {
        RadioKind.T2U_PLUS -> "Plug in an RTL8821AU, such as the Archer T2U Plus."
        RadioKind.RTL8812AU -> "Plug in an RTL8812AU."
        RadioKind.RTL8814AU -> "Plug in an RTL8814AU."
        RadioKind.MT7612U -> "Plug in an MT7612U."
        RadioKind.PAU0A -> "Plug in a Panda PAU0A."
        RadioKind.PAU0B, RadioKind.UNKNOWN -> "That adapter is not supported."
    }

    fun open(kind: RadioKind): String? {
        synchronized(lock) {
            if (clients > 0 && !dead && runningKind == kind) {
                clients += 1
                return null
            }
            if (clients > 0) {
                val current = adapterTitle ?: "another adapter"
                return "$current is already open."
            }
            val attached = UsbDeviceFinder.attached(context)
            val match = attached.firstOrNull { it.kind == kind && it.device != null }
            val device = match?.device
            if (device == null) {
                val excluded = kind == RadioKind.PAU0A && attached.any { it.kind == RadioKind.PAU0B }
                return if (excluded) {
                    "Panda PAU0B is not used. Plug in a Panda PAU0A."
                } else {
                    plugInHint(kind)
                }
            }
            val option = RadioCatalog.optionFor(kind) ?: return "That adapter is not supported."
            val radio = RadioFactory.create(context, option, device)
                ?: return "That adapter is not supported."
            clients = 1
            dead = false
            runningKind = kind
            backend = radio
            adapterTitle = radio.info.title
            _failure.value = null
            _status.value = radio.info.title
            job = scope.launch {
                val listener = object : RadioBackend.Listener {
                    override fun onStatus(message: String) {
                        _status.value = message
                    }

                    override fun onChannel(channel: Int, band: com.emptyset.detector.detect.WifiBand) {
                        _channel.value = channel
                    }

                    override fun onRawFrame(frame: ByteArray, channel: Int?, rssiDbm: Int?) {
                        _frames.tryEmit(UsbWifiFrame(frame, channel, rssiDbm))
                    }

                    override fun onError(message: String) {
                        fail(message)
                    }
                }
                try {
                    radio.start(listener)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    fail(error.message ?: "USB adapter failed")
                }
            }
            return null
        }
    }

    fun close() {
        val radio = synchronized(lock) {
            if (clients > 0) clients -= 1
            if (clients != 0) return@synchronized null
            dead = false
            _failure.value = null
            detachRadio()
        }
        radio?.stop()
    }

    private fun fail(message: String) {
        val radio = synchronized(lock) {
            dead = true
            _failure.value = message
            detachRadio()
        }
        radio?.stop()
    }

    private fun detachRadio(): RadioBackend? {
        val radio = backend
        backend = null
        job?.cancel()
        job = null
        runningKind = null
        return radio
    }
}
