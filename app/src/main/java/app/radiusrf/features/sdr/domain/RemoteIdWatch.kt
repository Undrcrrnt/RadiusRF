package app.radiusrf.features.sdr.domain

import android.annotation.SuppressLint
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid

data class RemoteIdSighting(
    val address: String,
    val id: String,
    val lat: Double?,
    val lon: Double?,
    val operatorLat: Double? = null,
    val operatorLon: Double? = null,
    val operatorKind: String? = null,
    val rssi: Int,
    val timeMs: Long
)

/**
 * Receives ASTM F3411 / OpenDroneID Bluetooth advertisements.
 * Service data is application code 0x0D, a counter, then one 25-byte message.
 */
class RemoteIdWatch(context: Context) {
    private val scanner = context.getSystemService(android.bluetooth.BluetoothManager::class.java)
        ?.adapter
        ?.bluetoothLeScanner
    private val found = LinkedHashMap<String, RemoteIdSighting>()
    private var callback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    fun start(onUpdate: (List<RemoteIdSighting>) -> Unit): String? {
        val ble = scanner ?: return "Bluetooth is off"
        stop()
        val uuid = ParcelUuid.fromString("0000fffa-0000-1000-8000-00805f9b34fb")
        val filter = ScanFilter.Builder()
            .setServiceData(uuid, byteArrayOf(0x0D), byteArrayOf(0xFF.toByte()))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val scan = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val data = result.scanRecord?.getServiceData(uuid) ?: return
                parse(result.device.address, result.rssi, data)
                onUpdate(found.values.sortedByDescending { it.rssi })
            }
        }
        callback = scan
        ble.startScan(listOf(filter), settings, scan)
        return null
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val scan = callback ?: return
        callback = null
        runCatching { scanner?.stopScan(scan) }
    }

    private fun parse(address: String, rssi: Int, data: ByteArray) {
        val messages = OpenDroneId.messages(data)
        if (messages.isEmpty()) return
        messages.forEach { msg -> found[address] = OpenDroneId.fold(found[address], address, rssi, msg) }
        RemoteIdLog.note(address, rssi, messages)
        while (found.size > 40) {
            val oldest = found.entries.minByOrNull { it.value.timeMs }?.key ?: break
            found.remove(oldest)
        }
    }
}
