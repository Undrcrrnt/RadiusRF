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
        if (data.size < 27 || data[0] != 0x0D.toByte()) return
        var offset = 2
        val firstType = data[offset].toInt() and 0x0F
        if (firstType == 0xF && data.size >= offset + 3) {
            val count = data[offset + 2].toInt() and 0xFF
            offset += 3
            repeat(count) {
                if (offset + 25 > data.size) return@repeat
                apply(address, rssi, data.copyOfRange(offset, offset + 25))
                offset += 25
            }
        } else if (data.size >= offset + 25) {
            apply(address, rssi, data.copyOfRange(offset, offset + 25))
        }
    }

    private fun apply(address: String, rssi: Int, msg: ByteArray) {
        val type = msg[0].toInt() and 0x0F
        val previous = found[address]
        val now = System.currentTimeMillis()
        val next = when (type) {
            0 -> {
                val text = msg.copyOfRange(2, 22).toString(Charsets.US_ASCII)
                    .filter { it.code in 32..126 }
                    .trim()
                (previous ?: blank(address, rssi, now)).copy(
                    id = text.ifBlank { address },
                    rssi = rssi,
                    timeMs = now
                )
            }
            1 -> {
                val lat = leInt(msg, 5) / 10_000_000.0
                val lon = leInt(msg, 9) / 10_000_000.0
                val valid = lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)
                (previous ?: blank(address, rssi, now)).copy(
                    lat = if (valid) lat else previous?.lat,
                    lon = if (valid) lon else previous?.lon,
                    rssi = rssi,
                    timeMs = now
                )
            }
            4 -> {
                val lat = leInt(msg, 2) / 10_000_000.0
                val lon = leInt(msg, 6) / 10_000_000.0
                val valid = lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)
                val kind = when (msg[1].toInt() and 0x03) {
                    0 -> "Takeoff"
                    1 -> "Pilot"
                    else -> "Fixed"
                }
                (previous ?: blank(address, rssi, now)).copy(
                    operatorLat = if (valid) lat else previous?.operatorLat,
                    operatorLon = if (valid) lon else previous?.operatorLon,
                    operatorKind = if (valid) kind else previous?.operatorKind,
                    rssi = rssi,
                    timeMs = now
                )
            }
            else -> (previous ?: blank(address, rssi, now)).copy(rssi = rssi, timeMs = now)
        }
        found[address] = next
        while (found.size > 40) {
            val oldest = found.entries.minByOrNull { it.value.timeMs }?.key ?: break
            found.remove(oldest)
        }
    }

    private fun blank(address: String, rssi: Int, now: Long) =
        RemoteIdSighting(address = address, id = address, lat = null, lon = null, rssi = rssi, timeMs = now)

    private fun leInt(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }
}
