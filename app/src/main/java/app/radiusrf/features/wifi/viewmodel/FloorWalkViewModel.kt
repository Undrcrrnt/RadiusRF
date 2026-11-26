package app.radiusrf.features.wifi.viewmodel

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radiusrf.features.wifi.domain.AssociatedLinkReader
import app.radiusrf.features.wifi.domain.FloorArHost
import app.radiusrf.features.wifi.domain.FloorPose
import app.radiusrf.features.wifi.domain.FloorSample
import app.radiusrf.features.wifi.domain.FloorWalkImage
import app.radiusrf.features.wifi.domain.FloorWalkMath
import app.radiusrf.features.wifi.domain.FloorWalkRecord
import app.radiusrf.features.wifi.domain.FloorWalkStore
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID
import javax.inject.Inject

sealed interface FloorAr {
    data object Checking : FloorAr
    data object Ready : FloorAr
    data object Installable : FloorAr
    data class Unavailable(val reason: String) : FloorAr
}

data class FloorWalkState(
    val ar: FloorAr = FloorAr.Checking,
    val linkReady: Boolean = false,
    val associated: Boolean = false,
    val ssid: String? = null,
    val bssid: String? = null,
    val rssi: Int? = null,
    val frequencyMhz: Int? = null,
    val walking: Boolean = false,
    val note: String? = null,
    val live: List<FloorSample> = emptyList(),
    val saved: List<FloorWalkRecord> = emptyList(),
    val viewing: FloorWalkRecord? = null
)

@HiltViewModel
class FloorWalkViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reader: AssociatedLinkReader,
    private val store: FloorWalkStore
) : ViewModel() {

    val host = FloorArHost(context)

    private val _state = MutableStateFlow(FloorWalkState())
    val state: StateFlow<FloorWalkState> = _state

    private var walkStartedAt: Long? = null

    private val rssiReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val rssi = intent?.getIntExtra(WifiManager.EXTRA_NEW_RSSI, Int.MIN_VALUE) ?: return
            if (!FloorWalkMath.usableRssi(rssi)) return
            val current = _state.value
            if (!current.associated) return
            _state.value = current.copy(rssi = rssi)
        }
    }

    init {
        _state.value = _state.value.copy(saved = runCatching { store.list() }.getOrDefault(emptyList()))
        ContextCompat.registerReceiver(
            context,
            rssiReceiver,
            IntentFilter(WifiManager.RSSI_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        viewModelScope.launch {
            while (isActive) {
                val link = reader.read()
                val current = _state.value
                _state.value = current.copy(
                    linkReady = true,
                    associated = link.associated,
                    ssid = link.ssid,
                    bssid = link.bssid,
                    rssi = link.rssi,
                    frequencyMhz = link.frequencyMhz
                )
                delay(1_000)
            }
        }
        refreshAr()
    }

    fun refreshAr() {
        viewModelScope.launch(Dispatchers.Default) {
            val ar = runCatching { readAvailability() }.getOrDefault(
                FloorAr.Unavailable("Floor tracking is unavailable on this phone.")
            )
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(ar = ar)
            }
        }
    }

    fun installAr(activity: Activity) {
        try {
            val status = ArCoreApk.getInstance().requestInstall(activity, true)
            if (status == ArCoreApk.InstallStatus.INSTALLED) {
                _state.value = _state.value.copy(ar = FloorAr.Ready)
            }
        } catch (_: UnavailableDeviceNotCompatibleException) {
            _state.value = _state.value.copy(ar = FloorAr.Unavailable("This phone cannot track a floor plan."))
        } catch (_: UnavailableUserDeclinedInstallationException) {
            _state.value = _state.value.copy(
                ar = FloorAr.Installable,
                note = "Floor tracking needs Play Services for AR."
            )
        } catch (_: Exception) {
            _state.value = _state.value.copy(ar = FloorAr.Unavailable("Floor tracking is unavailable on this phone."))
        }
    }

    fun startWalk() {
        val current = _state.value
        if (!current.associated || current.ar !is FloorAr.Ready || current.walking) return
        host.resetOrigin()
        walkStartedAt = System.currentTimeMillis()
        _state.value = current.copy(walking = true, live = emptyList(), viewing = null, note = null)
    }

    fun onPose(pose: FloorPose) {
        val current = _state.value
        if (!current.walking) return
        if (!pose.tracking) {
            note(current, "Point the camera at the room.")
            return
        }
        if (!current.associated) {
            note(current, "Join a Wi-Fi network to keep mapping.")
            return
        }
        val rssi = current.rssi
        if (rssi == null) {
            note(current, "Waiting for the Wi-Fi signal.")
            return
        }
        if (current.live.size >= FloorWalkMath.MAX_SAMPLES) {
            note(current, "This walk is full. Save it to keep the path.")
            return
        }
        val now = System.currentTimeMillis()
        val last = current.live.lastOrNull()
        if (!FloorWalkMath.shouldStore(last, pose.x, pose.z, rssi, now)) {
            note(current, null)
            return
        }
        val sample = FloorWalkMath.markRoam(
            last,
            FloorSample(
                timeMs = now,
                x = pose.x,
                z = pose.z,
                headingDeg = pose.headingDeg,
                rssi = rssi,
                bssid = current.bssid,
                frequencyMhz = current.frequencyMhz
            )
        )
        _state.value = current.copy(live = current.live + sample, note = null)
    }

    fun onCameraError(message: String) {
        val current = _state.value
        if (current.note == message) return
        _state.value = current.copy(note = message)
    }

    fun finish() {
        val current = _state.value
        val live = current.live
        val started = walkStartedAt ?: live.firstOrNull()?.timeMs ?: System.currentTimeMillis()
        walkStartedAt = null
        _state.value = current.copy(walking = false)
        host.close()
        if (live.isEmpty()) {
            _state.value = _state.value.copy(note = "No points yet. Walk while the camera can see the room.")
            return
        }
        val record = FloorWalkRecord(
            id = UUID.randomUUID().toString(),
            startedAt = started,
            ssid = current.ssid ?: "Joined network",
            samples = live
        )
        store.save(record)
        _state.value = _state.value.copy(
            live = emptyList(),
            note = null,
            saved = store.list(),
            viewing = record
        )
    }

    fun cancel() {
        walkStartedAt = null
        host.close()
        _state.value = _state.value.copy(walking = false, live = emptyList(), note = null)
    }

    fun open(record: FloorWalkRecord) {
        _state.value = _state.value.copy(viewing = record)
    }

    /** PNG of the saved map, ready for the share sheet. Null when the file could not be written. */
    fun shareImage(record: FloorWalkRecord): Intent? {
        if (record.samples.isEmpty()) return null
        val whenText = runCatching {
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(record.startedAt))
        }.getOrDefault("")
        val slug = record.ssid.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(32)
            .ifBlank { "walk" }
        val file = File(File(context.cacheDir, "exports").apply { mkdirs() }, "floor-walk-$slug-${record.startedAt}.png")
        return runCatching {
            FloorWalkImage.write(record, whenText, file)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Floor walk · ${record.ssid}")
                clipData = ClipData.newUri(context.contentResolver, "Floor walk", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }.getOrNull()
    }

    fun closeView() {
        _state.value = _state.value.copy(viewing = null)
    }

    fun deleteViewing() {
        val id = _state.value.viewing?.id ?: return
        store.delete(id)
        _state.value = _state.value.copy(viewing = null, saved = store.list())
    }

    override fun onCleared() {
        val current = _state.value
        if (current.walking && current.live.isNotEmpty()) {
            runCatching {
                store.save(
                    FloorWalkRecord(
                        id = UUID.randomUUID().toString(),
                        startedAt = walkStartedAt ?: current.live.first().timeMs,
                        ssid = current.ssid ?: "Joined network",
                        samples = current.live
                    )
                )
            }
        }
        host.close()
        runCatching { context.unregisterReceiver(rssiReceiver) }
    }

    private fun note(current: FloorWalkState, message: String?) {
        if (current.note == message) return
        _state.value = current.copy(note = message)
    }

    private suspend fun readAvailability(): FloorAr {
        val apk = ArCoreApk.getInstance()
        var availability = apk.checkAvailability(context)
        var spins = 0
        while (availability.isTransient && spins < 10) {
            delay(200)
            availability = apk.checkAvailability(context)
            spins++
        }
        return when (availability) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> FloorAr.Ready
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> FloorAr.Installable
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE ->
                FloorAr.Unavailable("This phone cannot track a floor plan.")
            ArCoreApk.Availability.UNKNOWN_CHECKING -> FloorAr.Checking
            else -> FloorAr.Unavailable("Floor tracking is unavailable on this phone.")
        }
    }
}
