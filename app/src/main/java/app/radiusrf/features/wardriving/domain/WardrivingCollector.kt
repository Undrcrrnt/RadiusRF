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
import androidx.core.content.ContextCompat
import app.radiusrf.features.celltower.domain.CellTowerAnalyzer
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.ConcurrentHashMap

class WardrivingCollector(
    private val context: Context,
    private val wifiManager: WifiManager,
    private val bluetoothManager: BluetoothManager?,
    private val cellTowerAnalyzer: CellTowerAnalyzer
) {
    @SuppressLint("MissingPermission")
    fun collect(mode: SurveyMode): Flow<List<WardrivingRecord>> = callbackFlow {
        val ble = ConcurrentHashMap<String, Pair<String?, Int>>()
        val classic = ConcurrentHashMap<String, Triple<String?, Int, String>>()
        var leCallback: ScanCallback? = null
        var classicReceiver: BroadcastReceiver? = null
        val adapter = bluetoothManager?.adapter

        if (mode.bluetooth && granted(android.Manifest.permission.BLUETOOTH_SCAN)) {
            val scanner = adapter?.bluetoothLeScanner
            if (scanner != null) {
                leCallback = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        val name = result.scanRecord?.deviceName ?: result.device.name
                        ble[result.device.address] = (name ?: ble[result.device.address]?.first) to result.rssi
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
                                classic[device.address] = Triple(name ?: classic[device.address]?.first, rssi, kind)
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
                val records = ArrayList<WardrivingRecord>()
                if (mode.wifi) {
                    records += wifiManager.scanResults.map { ap ->
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
                    ble.forEach { (address, snap) ->
                        records += sighting(address, snap.first, snap.second, 0, "Misc", SurveyRadio.BLE, location, now)
                    }
                    classic.forEach { (address, snap) ->
                        records += sighting(address, snap.first, snap.second, 0, snap.third, SurveyRadio.BT, location, now)
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
                if (records.isNotEmpty()) trySend(records)
            }
        }
        client.requestLocationUpdates(locationRequest, callback, Looper.getMainLooper())
        if (mode.wifi) wifiManager.startScan()

        awaitClose {
            client.removeLocationUpdates(callback)
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
}
