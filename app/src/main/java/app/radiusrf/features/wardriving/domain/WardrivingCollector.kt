package app.radiusrf.features.wardriving.domain

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.radiusrf.features.celltower.domain.CellTowerAnalyzer
import app.radiusrf.features.wifi.domain.BeaconFrame
import app.radiusrf.features.wifi.domain.UsbWifiSession
import app.radiusrf.features.wifi.domain.WifiLink
import app.radiusrf.features.wifi.domain.WifiAccessPoint
import app.radiusrf.features.wifi.domain.WifiNicPreference
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class WardrivingCollector(
    private val context: Context,
    private val wifiManager: WifiManager,
    private val bluetoothManager: BluetoothManager?,
    private val cellTowerAnalyzer: CellTowerAnalyzer,
    private val nic: WifiNicPreference,
    private val usb: UsbWifiSession
) {
    @SuppressLint("MissingPermission")
    fun collect(mode: SurveyMode): Flow<List<WardrivingRecord>> = callbackFlow {
        data class BleObservation(val name: String?, val rssi: Int, val seenElapsedMs: Long)
        data class ClassicObservation(val name: String?, val rssi: Int, val kind: String, val seenElapsedMs: Long)
        data class UsbClient(val mac: String, val ap: String, val rssi: Int, val heardAt: Long)
        val ble = ConcurrentHashMap<String, BleObservation>()
        val classic = ConcurrentHashMap<String, ClassicObservation>()
        val usbAps = ConcurrentHashMap<String, WifiAccessPoint>()
        val usbClients = ConcurrentHashMap<String, UsbClient>()
        val lastUsbSnapshotMs = AtomicLong(0L)
        var lastSnapshotElapsedMs = SystemClock.elapsedRealtime()
        var leCallback: ScanCallback? = null
        var classicReceiver: BroadcastReceiver? = null
        var usbJob: Job? = null
        var usbFailureJob: Job? = null
        var usbHeld = false
        val adapter = bluetoothManager?.adapter
        val scope = this

        fun externalWifi(): Boolean = mode.wifi && nic.source.value.kind != null

        fun listenOnSelectedRadio() {
            usbJob?.cancel()
            usbJob = null
            usbFailureJob?.cancel()
            usbFailureJob = null
            if (usbHeld) {
                usb.close()
                usbHeld = false
            }
            usbAps.clear()
            usbClients.clear()
            lastUsbSnapshotMs.set(0L)
            if (!mode.wifi) return
            val kind = nic.source.value.kind
            if (kind == null) {
                runCatching { wifiManager.startScan() }
                return
            }
            val error = usb.open(kind)
            if (error != null) {
                close(IllegalStateException(error))
                return
            }
            usbHeld = true
            usbJob = scope.launch {
                usb.frames.collect { frame ->
                    val heardAt = System.currentTimeMillis()
                    val link = WifiLink.parse(frame.bytes)
                    if (link != null) {
                        val mac = link.client.lowercase()
                        val apMac = link.accessPoint.lowercase()
                        usbClients["$mac|$apMac"] = UsbClient(mac, apMac, frame.rssi ?: -100, heardAt)
                    }
                    val ap = BeaconFrame.parse(frame.bytes, frame.channel, frame.rssi) ?: return@collect
                    usbAps[ap.bssid.lowercase()] = ap.copy(bssid = ap.bssid.lowercase(), heardAt = heardAt)
                }
            }
            usbFailureJob = scope.launch {
                val message = usb.failure.filterNotNull().first()
                close(IllegalStateException(message))
            }
        }

        listenOnSelectedRadio()
        launch {
            nic.source.drop(1).collect { listenOnSelectedRadio() }
        }

        if (mode.bluetooth && granted(android.Manifest.permission.BLUETOOTH_SCAN)) {
            val scanner = adapter?.bluetoothLeScanner
            if (scanner != null) {
                leCallback = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        val name = result.scanRecord?.deviceName ?: result.device.name
                        ble[result.device.address] = BleObservation(
                            name ?: ble[result.device.address]?.name,
                            result.rssi,
                            result.timestampNanos / 1_000_000L
                        )
                    }
                }
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                    .build()
                scanner.startScan(null, settings, leCallback)
            }
            if (adapter != null) {
                classicReceiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        when (intent.action) {
                            BluetoothDevice.ACTION_FOUND -> runCatching {
                                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                                } else {
                                    @Suppress("DEPRECATION")
                                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                                } ?: return@runCatching
                                val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                                val name = device.name
                                val kind = device.bluetoothClass?.majorDeviceClass?.let { majorClassName(it) } ?: "Uncategorized"
                                classic[device.address] = ClassicObservation(
                                    name ?: classic[device.address]?.name,
                                    rssi,
                                    kind,
                                    SystemClock.elapsedRealtime()
                                )
                            }
                            BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> adapter.startDiscovery()
                        }
                    }
                }
                val filter = IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(classicReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(classicReceiver, filter)
                }
                adapter.startDiscovery()
            }
        }

        val client = LocationServices.getFusedLocationProviderClient(context)
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
            .setMinUpdateIntervalMillis(1000)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                try {
                snapshot(location)
                } catch (_: SecurityException) {
                }
            }

            private fun snapshot(location: android.location.Location) {
                val now = System.currentTimeMillis()
                val nowElapsedMs = SystemClock.elapsedRealtime()
                val records = ArrayList<WardrivingRecord>()
                if (mode.wifi && externalWifi()) {
                    val cutoff = lastUsbSnapshotMs.get()
                    lastUsbSnapshotMs.set(now)
                    usbAps.values
                        .filter { it.heardAt in (cutoff + 1)..now }
                        .forEach { ap ->
                            records += sighting(
                                id = ap.bssid,
                                name = ap.ssid.takeIf { it.isNotBlank() && it != "<Hidden>" },
                                rssi = ap.rssi,
                                frequency = ap.frequency,
                                details = ap.capabilities,
                                radio = SurveyRadio.WIFI,
                                location = location,
                                timestamp = now
                            )
                        }
                    usbClients.values
                        .filter { it.heardAt in (cutoff + 1)..now }
                        .forEach { client ->
                            val ap = usbAps[client.ap]
                            records += sighting(
                                id = client.mac,
                                name = ap?.ssid?.takeIf { it.isNotBlank() && it != "<Hidden>" },
                                rssi = client.rssi,
                                frequency = ap?.frequency ?: 0,
                                details = client.ap,
                                radio = SurveyRadio.CLIENT,
                                location = location,
                                timestamp = now
                            )
                        }
                    usbAps.entries.removeAll { now - it.value.heardAt > OBSERVATION_MAX_AGE_MS }
                    usbClients.entries.removeAll { now - it.value.heardAt > OBSERVATION_MAX_AGE_MS }
                } else if (mode.wifi) {
                    records += wifiManager.scanResults
                        .filter { it.timestamp / 1_000L > lastSnapshotElapsedMs }
                        .map { ap ->
                        sighting(
                            id = ap.BSSID,
                            name = ap.SSID.takeIf { it.isNotBlank() },
                            rssi = ap.level,
                            frequency = ap.frequency,
                            details = ap.capabilities,
                            radio = SurveyRadio.WIFI,
                            location = location,
                            timestamp = now
                        )
                    }
                    wifiManager.startScan()
                }
                if (mode.bluetooth) {
                    ble.filterValues { it.seenElapsedMs > lastSnapshotElapsedMs }.forEach { (address, snap) ->
                        records += sighting(address, snap.name, snap.rssi, 0, "Misc", SurveyRadio.BLE, location, now)
                    }
                    classic.filterValues { it.seenElapsedMs > lastSnapshotElapsedMs }.forEach { (address, snap) ->
                        records += sighting(address, snap.name, snap.rssi, 0, snap.kind, SurveyRadio.BT, location, now)
                    }
                }
                if (mode.cellular && granted(android.Manifest.permission.READ_PHONE_STATE)) {
                    cellTowerAnalyzer.snapshot().forEach { cell ->
                        val radio = cell.type.name
                        records += sighting(
                            id = cellIdentity(cell.mcc, cell.mnc, cell.lac, cell.cid),
                            name = cell.carrierName,
                            rssi = cell.rssi,
                            frequency = cell.arfcn ?: 0,
                            details = "$radio;${plmn(cell.mcc, cell.mnc)}",
                            radio = radio,
                            location = location,
                            timestamp = now
                        )
                    }
                }
                lastSnapshotElapsedMs = nowElapsedMs
                ble.entries.removeAll { nowElapsedMs - it.value.seenElapsedMs > OBSERVATION_MAX_AGE_MS }
                classic.entries.removeAll { nowElapsedMs - it.value.seenElapsedMs > OBSERVATION_MAX_AGE_MS }
                if (records.isNotEmpty()) trySend(records)
            }
        }
        client.requestLocationUpdates(locationRequest, callback, Looper.getMainLooper())

        awaitClose {
            client.removeLocationUpdates(callback)
            usbJob?.cancel()
            usbFailureJob?.cancel()
            if (usbHeld) usb.close()
            leCallback?.let { adapter?.bluetoothLeScanner?.stopScan(it) }
            classicReceiver?.let { receiver ->
                adapter?.cancelDiscovery()
                runCatching { context.unregisterReceiver(receiver) }
            }
        }
    }

    private fun sighting(
        id: String,
        name: String?,
        rssi: Int,
        frequency: Int,
        details: String?,
        radio: String,
        location: Location,
        timestamp: Long
    ) = WardrivingRecord(
        bssid = id,
        ssid = name?.takeIf { it.isNotBlank() },
        rssi = rssi,
        frequency = frequency,
        capabilities = details,
        lat = location.latitude,
        lng = location.longitude,
        timestamp = timestamp,
        radio = radio,
        altitude = if (location.hasAltitude()) location.altitude else 0.0,
        accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else 0.0
    )

    private fun granted(permission: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && permission == android.Manifest.permission.BLUETOOTH_SCAN) {
            return true
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun majorClassName(major: Int): String = when (major) {
        0x0100 -> "Computer"
        0x0200 -> "Phone"
        0x0300 -> "Network"
        0x0400 -> "Audio"
        0x0500 -> "Peripheral"
        0x0600 -> "Imaging"
        0x0700 -> "Wearable"
        0x0800 -> "Toy"
        else -> "Uncategorized"
    }

    private companion object {
        const val OBSERVATION_MAX_AGE_MS = 15_000L
    }
}
