package app.radiusrf.features.wardriving.domain

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds a pcapng Wireshark and Netseer can open.
 * Wi-Fi sightings become radiotap beacon frames. Bluetooth LE sightings become
 * advertising PDUs. The frames are rebuilt from the survey log, not a raw capture.
 */
object SurveyPcap {

    private const val LINKTYPE_IEEE802_11_RADIO = 127
    private const val LINKTYPE_BLUETOOTH_LE_LL_WITH_PHDR = 256

    fun write(records: List<WardrivingRecord>): ByteArray {
        val out = ByteArrayWriter()
        out.sectionHeader()
        out.interfaceDescription(LINKTYPE_IEEE802_11_RADIO)
        out.interfaceDescription(LINKTYPE_BLUETOOTH_LE_LL_WITH_PHDR)
        records.forEach { record ->
            when (record.radio) {
                SurveyRadio.WIFI -> out.packet(0, record.timestamp, beacon(record))
                SurveyRadio.CLIENT -> {
                    val ap = record.associatedBssid.orEmpty()
                    if (ap.split(':', '-').size == 6) out.packet(0, record.timestamp, clientData(record, ap))
                }
                SurveyRadio.BLE -> out.packet(1, record.timestamp, bleAdvert(record))
            }
        }
        return out.toByteArray()
    }

    private fun beacon(record: WardrivingRecord): ByteArray {
        val ssid = record.ssid.orEmpty().encodeToByteArray().let { if (it.size > 32) it.copyOf(32) else it }
        val channel = channelByte(record.frequency)
        val body = ByteArrayWriter()
        body.u8(0x80); body.u8(0x00)
        body.u16(0)
        repeat(6) { body.u8(0xFF) }
        body.mac(record.bssid)
        body.mac(record.bssid)
        body.u16(0)
        repeat(8) { body.u8(0) }
        body.u16(0x0064)
        val privacy = record.capabilities.orEmpty().let { it.contains("WPA") || it.contains("WEP") || it.contains("RSN") }
        body.u16(if (privacy) 0x0011 else 0x0001)
        body.u8(0); body.u8(ssid.size); body.bytes(ssid)
        if (channel in 1..233) {
            body.u8(3); body.u8(1); body.u8(channel)
        }
        return radiotap(record) + body.toByteArray()
    }

    private fun clientData(record: WardrivingRecord, accessPoint: String): ByteArray {
        val body = ByteArrayWriter()
        body.u8(0x08)
        body.u8(0x01)
        body.u16(0)
        body.mac(accessPoint)
        body.mac(record.bssid)
        body.mac(accessPoint)
        body.u16(0)
        return radiotap(record) + body.toByteArray()
    }

    private fun radiotap(record: WardrivingRecord): ByteArray {
        val freq = if (record.frequency > 0) record.frequency else 2412
        val flags = if (freq >= 5000) 0x0140 else 0x00A0
        val out = ByteArrayWriter()
        out.u8(0); out.u8(0); out.u16(15); out.u32(0x2E)
        out.u8(0); out.u8(0x02)
        out.u16(freq); out.u16(flags)
        out.u8(record.rssi.coerceIn(-128, 127))
        return out.toByteArray()
    }

    private fun bleAdvert(record: WardrivingRecord): ByteArray {
        val name = record.ssid?.encodeToByteArray()?.let { if (it.size > 26) it.copyOf(26) else it }
        val nameLen = if (name == null) 0 else name.size + 2
        val pduLen = 6 + nameLen
        val out = ByteArrayWriter()
        out.u8(37)
        out.u8(record.rssi.coerceIn(-128, 127))
        out.u8(0); out.u8(0); out.u32(0); out.u16(0)
        out.u8(0x00); out.u8(pduLen)
        out.macLe(record.bssid)
        if (name != null) {
            out.u8(name.size + 1); out.u8(0x09); out.bytes(name)
        }
        return out.toByteArray()
    }

    private fun channelByte(freqMhz: Int): Int {
        if (freqMhz <= 0) return 0
        val parsed = app.radiusrf.core.util.FrequencyUtils.frequencyToChannel(freqMhz)
        return if (parsed < 0) 0 else parsed
    }

    private class ByteArrayWriter {
        private val parts = ArrayList<ByteArray>()

        fun u8(value: Int) = bytes(byteArrayOf(value.toByte()))
        fun bytes(value: ByteArray) { parts += value }

        fun u16(value: Int) {
            val buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
            buf.putShort(value.toShort())
            bytes(buf.array())
        }

        fun u32(value: Int) = u32(value.toLong())

        fun u32(value: Long) {
            val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(value.toInt())
            bytes(buf.array())
        }

        fun mac(text: String) = bytes(macBytes(text))
        fun macLe(text: String) = bytes(macBytes(text).reversedArray())

        fun sectionHeader() {
            block(0x0A0D0D0A) {
                u32(0x1A2B3C4D)
                u16(1); u16(0)
                u32(0xFFFFFFFF); u32(0xFFFFFFFF)
                u32(0)
            }
        }

        fun interfaceDescription(linkType: Int) {
            block(0x00000001) {
                u16(linkType); u16(0); u32(65535); u32(0)
            }
        }

        fun packet(interfaceId: Int, epochMs: Long, data: ByteArray) {
            val micros = epochMs * 1000L
            block(0x00000006) {
                u32(interfaceId.toLong())
                u32((micros ushr 32) and 0xFFFFFFFF)
                u32(micros and 0xFFFFFFFF)
                u32(data.size.toLong())
                u32(data.size.toLong())
                bytes(data)
                val pad = (4 - (data.size % 4)) % 4
                repeat(pad) { u8(0) }
                u32(0)
            }
        }

        private fun block(type: Long, body: ByteArrayWriter.() -> Unit) {
            val inner = ByteArrayWriter().apply(body).toByteArray()
            val length = 12 + inner.size
            u32(type)
            u32(length.toLong())
            bytes(inner)
            u32(length.toLong())
        }

        fun toByteArray(): ByteArray {
            val size = parts.sumOf { it.size }
            val out = ByteArray(size)
            var offset = 0
            parts.forEach {
                it.copyInto(out, offset)
                offset += it.size
            }
            return out
        }
    }

    private fun macBytes(text: String): ByteArray {
        val parts = text.split(':', '-')
        if (parts.size != 6) return ByteArray(6)
        return ByteArray(6) {
            parts[it].toIntOrNull(16)?.coerceIn(0, 255)?.toByte() ?: 0
        }
    }
}
