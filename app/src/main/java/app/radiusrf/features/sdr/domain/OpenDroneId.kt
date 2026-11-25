package app.radiusrf.features.sdr.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ASTM F3411 / OpenDroneID message packs from Bluetooth service data or a Wi-Fi vendor field.
 * The Wi-Fi field uses OUI FA:0B:BC and type 0x0D, then the same pack as Bluetooth.
 */
object OpenDroneId {
    private val WIFI_OUI = byteArrayOf(0xFA.toByte(), 0x0B, 0xBC.toByte())

    fun messages(data: ByteArray): List<ByteArray> {
        if (data.size < 27 || data[0] != 0x0D.toByte()) return emptyList()
        var offset = 2
        val firstType = data[offset].toInt() and 0x0F
        val out = ArrayList<ByteArray>()
        if (firstType == 0xF && data.size >= offset + 3) {
            val count = data[offset + 2].toInt() and 0xFF
            offset += 3
            repeat(count) {
                if (offset + 25 > data.size) return@repeat
                out += data.copyOfRange(offset, offset + 25)
                offset += 25
            }
        } else if (data.size >= offset + 25) {
            out += data.copyOfRange(offset, offset + 25)
        }
        return out
    }

    /** Vendor IE body, starting at the OUI. */
    fun fromVendorIe(body: ByteArray): List<ByteArray> {
        if (body.size < 5) return emptyList()
        if (body[0] != WIFI_OUI[0] || body[1] != WIFI_OUI[1] || body[2] != WIFI_OUI[2]) return emptyList()
        if (body[3] != 0x0D.toByte()) return emptyList()
        return messages(body.copyOfRange(3, body.size))
    }

    /** Beacon or probe response, radiotap header optional. */
    fun fromBeacon(raw: ByteArray): List<ByteArray> {
        var offset = 0
        if (raw.size >= 8 && raw[0] == 0.toByte() && raw[1] == 0.toByte()) {
            val headerLen = (raw[2].toInt() and 0xFF) or ((raw[3].toInt() and 0xFF) shl 8)
            if (headerLen in 8 until raw.size) offset = headerLen
        }
        if (raw.size - offset < 38) return emptyList()
        val fc = raw[offset].toInt() and 0xFF
        val type = (fc shr 2) and 0x3
        val subtype = (fc shr 4) and 0xF
        if (type != 0 || (subtype != 8 && subtype != 5)) return emptyList()
        val out = ArrayList<ByteArray>()
        var index = offset + 36
        while (index + 2 <= raw.size) {
            val id = raw[index].toInt() and 0xFF
            val length = raw[index + 1].toInt() and 0xFF
            if (index + 2 + length > raw.size) break
            if (id == 221) out += fromVendorIe(raw.copyOfRange(index + 2, index + 2 + length))
            index += 2 + length
        }
        return out
    }

    fun fold(previous: RemoteIdSighting?, address: String, rssi: Int, msg: ByteArray): RemoteIdSighting {
        val type = msg[0].toInt() and 0x0F
        val now = System.currentTimeMillis()
        val base = previous ?: RemoteIdSighting(address, address, null, null, rssi = rssi, timeMs = now)
        return when (type) {
            0 -> {
                val text = msg.copyOfRange(2, 22).toString(Charsets.US_ASCII).filter { it.code in 32..126 }.trim()
                base.copy(id = text.ifBlank { address }, rssi = rssi, timeMs = now)
            }
            1 -> {
                val lat = leInt(msg, 5) / 10_000_000.0
                val lon = leInt(msg, 9) / 10_000_000.0
                val valid = lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)
                base.copy(
                    lat = if (valid) lat else base.lat,
                    lon = if (valid) lon else base.lon,
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
                base.copy(
                    operatorLat = if (valid) lat else base.operatorLat,
                    operatorLon = if (valid) lon else base.operatorLon,
                    operatorKind = if (valid) kind else base.operatorKind,
                    rssi = rssi,
                    timeMs = now
                )
            }
            else -> base.copy(rssi = rssi, timeMs = now)
        }
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}

/** Bluetooth and Wi-Fi Remote ID sightings, shared by the field map. */
object RemoteIdLog {
    private val _sightings = MutableStateFlow<List<RemoteIdSighting>>(emptyList())
    val sightings: StateFlow<List<RemoteIdSighting>> = _sightings.asStateFlow()

    fun note(address: String, rssi: Int, messages: List<ByteArray>) {
        if (messages.isEmpty()) return
        var sighting = _sightings.value.find { it.address.equals(address, ignoreCase = true) }
        messages.forEach { msg -> sighting = OpenDroneId.fold(sighting, address, rssi, msg) }
        val saved = sighting ?: return
        val rest = _sightings.value.filterNot {
            it.address.equals(address, ignoreCase = true) || it.id.equals(saved.id, ignoreCase = true)
        }
        _sightings.value = (rest + saved).sortedByDescending { it.timeMs }.take(40)
    }
}
