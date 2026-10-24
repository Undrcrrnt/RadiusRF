package app.radiusrf.features.sdr.domain

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** rtl_tcp client used when the source is a remote RTL-SDR server. */
class SdrRtlTcp {
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    fun connect(host: String, port: Int): String? {
        close()
        return try {
            val next = Socket()
            next.tcpNoDelay = true
            next.connect(InetSocketAddress(host.trim(), port), 4_000)
            input = next.getInputStream()
            output = next.getOutputStream()
            socket = next
            val banner = ByteArray(12)
            var read = 0
            while (read < banner.size) {
                val n = input?.read(banner, read, banner.size - read) ?: -1
                if (n < 0) break
                read += n
            }
            null
        } catch (error: Exception) {
            close()
            error.message ?: "Could not reach the RTL-SDR server"
        }
    }

    fun configure(
        freqHz: Long,
        sampleRate: Int,
        gainTenths: Int,
        ppm: Int,
        biasTee: Boolean,
        directSampling: Int
    ): String? {
        if (socket == null) return "RTL-SDR server is not connected"
        return try {
            send(0x02, sampleRate.toLong())
            send(0x01, freqHz)
            send(0x05, ppm.toLong())
            send(0x03, if (gainTenths < 0) 0 else 1)
            send(0x08, if (gainTenths < 0) 1 else 0)
            if (gainTenths >= 0) send(0x04, gainTenths.toLong())
            send(0x09, directSampling.coerceIn(0, 2).toLong())
            send(0x0e, if (biasTee) 1 else 0)
            null
        } catch (error: Exception) {
            error.message ?: "RTL-SDR server rejected the settings"
        }
    }

    fun read(buffer: ByteArray): Int {
        val stream = input ?: return -1
        var offset = 0
        while (offset < buffer.size) {
            val n = stream.read(buffer, offset, buffer.size - offset)
            if (n < 0) return if (offset == 0) -1 else offset
            offset += n
            if (offset >= 512) break
        }
        return offset
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    private fun send(command: Int, value: Long) {
        val packet = byteArrayOf(
            command.toByte(),
            ((value ushr 24) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
        output?.write(packet)
        output?.flush()
    }
}
