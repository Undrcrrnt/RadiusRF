package app.radiusrf.features.connection_log.domain

import java.io.File
import java.io.FileOutputStream

/** DLT_RAW (101) capture of the IPv4 packets the local forwarder sees. Stops at 6 MB. */
class PcapSink(val file: File) : AutoCloseable {
    private val out = FileOutputStream(file)
    private var used = 24
    private val lock = Any()
    var closed = false
        private set

    init {
        out.write(globalHeader())
    }

    fun write(packet: ByteArray, length: Int = packet.size) {
        synchronized(lock) {
            if (closed || used >= MAX) return
            val size = length.coerceAtMost(packet.size).coerceAtMost(65535)
            val now = System.currentTimeMillis()
            val header = ByteArray(16)
            put32(header, 0, now / 1000L)
            put32(header, 4, (now % 1000L) * 1000L)
            put32(header, 8, size.toLong())
            put32(header, 12, size.toLong())
            out.write(header)
            out.write(packet, 0, size)
            used += 16 + size
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { out.close() }
        }
    }

    private fun globalHeader(): ByteArray {
        val header = ByteArray(24)
        header[0] = 0xD4.toByte()
        header[1] = 0xC3.toByte()
        header[2] = 0xB2.toByte()
        header[3] = 0xA1.toByte()
        header[4] = 2
        header[6] = 4
        put32(header, 16, 65535)
        put32(header, 20, 101)
        return header
    }

    private fun put32(data: ByteArray, offset: Int, value: Long) {
        data[offset] = value.toByte()
        data[offset + 1] = (value shr 8).toByte()
        data[offset + 2] = (value shr 16).toByte()
        data[offset + 3] = (value shr 24).toByte()
    }

    companion object {
        private const val MAX = 6 * 1024 * 1024
    }
}
