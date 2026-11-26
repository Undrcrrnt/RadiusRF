package app.radiusrf.features.connection_log.service

import app.radiusrf.features.connection_log.domain.ConnectionRow
import app.radiusrf.features.connection_log.domain.PacketCodec
import app.radiusrf.features.connection_log.domain.PcapSink
import android.os.Handler
import android.os.Looper
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Forwards this phone's IPv4 through the local VPN and counts each flow.
 * Sockets are protected so their traffic does not re-enter the tunnel.
 */
class Ipv4Relay(
    private val input: InputStream,
    private val output: OutputStream,
    private val protectSocket: (Socket) -> Boolean,
    private val protectDatagram: (DatagramSocket) -> Boolean,
    private val appName: (protocol: Int, local: InetAddress, localPort: Int, remote: InetAddress, remotePort: Int) -> String,
    private val pcap: PcapSink,
    private val onStopped: () -> Unit,
    private val onUpdate: (List<ConnectionRow>, String) -> Unit
) {
    private val running = AtomicBoolean(true)
    private val packets = ArrayBlockingQueue<ByteArray>(512)
    private val pool = Executors.newCachedThreadPool()
    private val tcpFlows = ConcurrentHashMap<String, TcpFlow>()
    private val udpFlows = ConcurrentHashMap<String, UdpFlow>()
    private val rows = ConcurrentHashMap<String, LiveRow>()
    private val names = ConcurrentHashMap<String, String>()
    private val readBuffer = ByteArray(2048)

    fun start() {
        pool.execute { readTun() }
        pool.execute { drain() }
        pool.execute { sweep() }
    }

    fun stop() {
        running.set(false)
        tcpFlows.values.forEach { runCatching { it.socket?.close() } }
        udpFlows.values.forEach { runCatching { it.socket.close() } }
        tcpFlows.clear()
        udpFlows.clear()
        runCatching { input.close() }
        runCatching { pool.shutdownNow() }
        runCatching { pcap.close() }
    }

    private fun readTun() {
        try {
            while (running.get()) {
                val size = try {
                    input.read(readBuffer)
                } catch (error: Exception) {
                    if (!running.get() || !isAgain(error) || !pause()) break
                    continue
                }
                if (size < 0) break
                if (size == 0) {
                    if (!pause()) break
                    continue
                }
                val copy = readBuffer.copyOf(size)
                pcap.write(copy)
                packets.offer(copy)
            }
        } finally {
            running.set(false)
            Handler(Looper.getMainLooper()).post(onStopped)
        }
    }

    private fun pause(): Boolean {
        return try {
            Thread.sleep(15)
            true
        } catch (_: InterruptedException) {
            false
        }
    }

    private fun isAgain(error: Throwable): Boolean {
        var current: Throwable? = error
        repeat(4) {
            val text = current?.message.orEmpty()
            if (text.contains("EAGAIN", ignoreCase = true) || text.contains("try again", ignoreCase = true)) return true
            current = current?.cause
        }
        return false
    }

    private fun drain() {
        while (running.get()) {
            val packet = try {
                packets.poll(200, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            } ?: continue
            runCatching { handle(packet) }
        }
    }

    private fun sweep() {
        while (running.get()) {
            try {
                Thread.sleep(5_000)
            } catch (_: InterruptedException) {
                break
            }
            val now = System.currentTimeMillis()
            udpFlows.entries.removeIf { entry ->
                val idle = now - entry.value.touched > 30_000
                if (idle) runCatching { entry.value.socket.close() }
                if (idle) rows.remove(entry.key)
                idle
            }
            publish(false)
        }
    }

    private fun handle(packet: ByteArray) {
        val ip = PacketCodec.ipv4(packet) ?: return
        if (isMulticast(ip.destination) || ip.destinationText == "255.255.255.255" || ip.destinationText == "10.251.1.1") return
        when (ip.protocol) {
            PacketCodec.TCP -> {
                val tcp = PacketCodec.tcp(packet, ip) ?: return
                onTcp(ip, tcp)
            }
            PacketCodec.UDP -> {
                val udp = PacketCodec.udp(packet, ip) ?: return
                onUdp(ip, udp)
            }
        }
    }

    private fun onTcp(ip: PacketCodec.Ipv4, tcp: PacketCodec.Tcp) {
        val id = id(PacketCodec.TCP, ip.sourceText, tcp.sourcePort, ip.destinationText, tcp.destinationPort)
        val syn = tcp.flags and 0x02 != 0 && tcp.flags and 0x10 == 0
        if (syn) {
            val existing = tcpFlows[id]
            if (existing != null) {
                if (!existing.open) emitSynAck(ip, tcp, existing)
                return
            }
            if (tcpFlows.size >= 80) {
                reply(ip, tcp, 0x14, ByteArray(0), null, 0, (tcp.sequence + 1) and 0xFFFFFFFF)
                return
            }
            openTcp(ip, tcp, id)
            return
        }
        tcpFlows[id]?.fromClient(ip, tcp)
    }

    private fun openTcp(ip: PacketCodec.Ipv4, tcp: PacketCodec.Tcp, id: String) {
        val isn = Random.nextLong().and(0xFFFFFFFF)
        val flow = TcpFlow(
            isn = isn,
            expected = (tcp.sequence + 1) and 0xFFFFFFFF,
            ourSeq = isn
        )
        tcpFlows[id] = flow
        val row = LiveRow(id, "This phone", names[ip.destinationText] ?: ip.destinationText, "TCP", tcp.destinationPort)
        rows[id] = row
        emitSynAck(ip, tcp, flow)
        publish(true)
        pool.execute {
            val socket = Socket()
            try {
                if (!protectSocket(socket)) throw IllegalStateException("protect")
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(InetAddress.getByAddress(ip.destination), tcp.destinationPort), 8_000)
                row.app = appName(
                    PacketCodec.TCP,
                    InetAddress.getByAddress(ip.source),
                    tcp.sourcePort,
                    InetAddress.getByAddress(ip.destination),
                    tcp.destinationPort
                )
                row.host = names[ip.destinationText] ?: row.host
                synchronized(flow) {
                    flow.socket = socket
                    flow.open = true
                    flow.pending.forEach { chunk ->
                        socket.getOutputStream().write(chunk)
                        row.sent += chunk.size
                    }
                    flow.pending.clear()
                }
                publish(true)
                val buffer = ByteArray(1200)
                while (running.get()) {
                    val read = socket.getInputStream().read(buffer)
                    if (read < 0) break
                    val data = buffer.copyOf(read)
                    synchronized(flow) {
                        reply(ip, tcp, 0x18, data, null, flow.ourSeq, flow.expected)
                        flow.ourSeq = (flow.ourSeq + data.size) and 0xFFFFFFFF
                    }
                    row.received += read
                    publish(false)
                }
                synchronized(flow) {
                    reply(ip, tcp, 0x11, ByteArray(0), null, flow.ourSeq, flow.expected)
                    flow.ourSeq = (flow.ourSeq + 1) and 0xFFFFFFFF
                }
            } catch (_: Exception) {
                if (running.get()) reply(ip, tcp, 0x14, ByteArray(0), null, flow.ourSeq, flow.expected)
            } finally {
                runCatching { socket.close() }
                tcpFlows.remove(id)
                publish(false)
            }
        }
    }

    private fun onUdp(ip: PacketCodec.Ipv4, udp: PacketCodec.Udp) {
        val id = id(PacketCodec.UDP, ip.sourceText, udp.sourcePort, ip.destinationText, udp.destinationPort)
        val existing = udpFlows[id]
        if (existing == null && udpFlows.size >= 80) return
        val flow = existing ?: openUdp(ip, udp, id) ?: return
        flow.touched = System.currentTimeMillis()
        val row = rows[id] ?: return
        row.sent += udp.payload.size
        if (udp.destinationPort == 443 || udp.destinationPort == 853) {
            PacketCodec.serverName(udp.payload)?.let { row.host = it }
        }
        runCatching {
            val packet = DatagramPacket(udp.payload, udp.payload.size, InetAddress.getByAddress(ip.destination), udp.destinationPort)
            flow.socket.send(packet)
        }
        publish(false)
    }

    private fun openUdp(ip: PacketCodec.Ipv4, udp: PacketCodec.Udp, id: String): UdpFlow? {
        val socket = DatagramSocket()
        if (!protectDatagram(socket)) {
            socket.close()
            return null
        }
        val flow = UdpFlow(socket)
        udpFlows[id] = flow
        val row = LiveRow(
            id,
            "This phone",
            names[ip.destinationText] ?: ip.destinationText,
            "UDP",
            udp.destinationPort
        )
        rows[id] = row
        pool.execute {
            row.app = runCatching {
                appName(
                    PacketCodec.UDP,
                    InetAddress.getByAddress(ip.source),
                    udp.sourcePort,
                    InetAddress.getByAddress(ip.destination),
                    udp.destinationPort
                )
            }.getOrDefault("This phone")
            val buffer = ByteArray(2048)
            val incoming = DatagramPacket(buffer, buffer.size)
            while (running.get() && !socket.isClosed) {
                try {
                    socket.receive(incoming)
                } catch (_: Exception) {
                    break
                }
                val payload = buffer.copyOf(incoming.length)
                if (udp.destinationPort == 53) {
                    PacketCodec.dnsAnswers(payload).forEach { (address, name) ->
                        names[address] = name
                        rows.values.forEach { live -> if (live.host == address) live.host = name }
                    }
                }
                val back = PacketCodec.udpPacket(ip.destination, ip.source, udp.destinationPort, udp.sourcePort, payload)
                writeTun(back)
                row.received += payload.size
                flow.touched = System.currentTimeMillis()
                publish(false)
            }
        }
        return flow
    }

    private fun TcpFlow.fromClient(ip: PacketCodec.Ipv4, tcp: PacketCodec.Tcp) {
        val row = rows[id(PacketCodec.TCP, ip.sourceText, tcp.sourcePort, ip.destinationText, tcp.destinationPort)]
        synchronized(this) {
            if (tcp.payload.isNotEmpty() && tcp.sequence == expected) {
                PacketCodec.serverName(tcp.payload)?.let { name -> row?.host = name }
                val kept = if (open && socket != null) {
                    val wrote = runCatching { socket!!.getOutputStream().write(tcp.payload) }.isSuccess
                    if (wrote && row != null) row.sent += tcp.payload.size
                    wrote
                } else if (pending.sumOf { it.size } + tcp.payload.size <= 64 * 1024) {
                    pending.add(tcp.payload.copyOf())
                    true
                } else {
                    false
                }
                if (kept) expected = (expected + tcp.payload.size) and 0xFFFFFFFF
            }
            if (tcp.flags and 0x01 != 0 && tcp.sequence + (if (tcp.payload.isEmpty()) 0 else tcp.payload.size) == expected) {
                expected = (expected + 1) and 0xFFFFFFFF
                runCatching { socket?.shutdownOutput() }
            }
            if (tcp.flags and 0x04 != 0) {
                runCatching { socket?.close() }
            }
            reply(ip, tcp, 0x10, ByteArray(0), null, ourSeq, expected)
        }
        publish(false)
    }

    private fun emitSynAck(ip: PacketCodec.Ipv4, tcp: PacketCodec.Tcp, flow: TcpFlow) {
        synchronized(flow) {
            reply(ip, tcp, 0x12, ByteArray(0), 1200, flow.isn, flow.expected)
            if (!flow.synSent) {
                flow.ourSeq = (flow.isn + 1) and 0xFFFFFFFF
                flow.synSent = true
            }
        }
    }

    private fun reply(
        ip: PacketCodec.Ipv4,
        tcp: PacketCodec.Tcp,
        flags: Int,
        payload: ByteArray,
        mss: Int?,
        sequence: Long,
        acknowledgment: Long
    ) {
        val packet = PacketCodec.tcpPacket(
            source = ip.destination,
            destination = ip.source,
            sourcePort = tcp.destinationPort,
            destinationPort = tcp.sourcePort,
            sequence = sequence,
            acknowledgment = acknowledgment,
            flags = flags,
            payload = payload,
            mss = mss
        )
        writeTun(packet)
    }

    private fun writeTun(packet: ByteArray) {
        synchronized(output) {
            runCatching {
                output.write(packet)
                output.flush()
            }
        }
        pcap.write(packet)
    }

    private fun publish(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPublish < 400) return
        lastPublish = now
        val list = rows.values
            .map { ConnectionRow(it.id, it.app, it.host, it.protocol, it.port, it.sent, it.received) }
            .sortedByDescending { it.sent + it.received }
        onUpdate(list, "${list.size} connection${if (list.size == 1) "" else "s"}")
    }

    private var lastPublish = 0L

    private fun isMulticast(address: ByteArray): Boolean {
        val first = address[0].toInt() and 0xFF
        return first >= 224
    }

    private fun id(protocol: Int, source: String, sourcePort: Int, destination: String, destinationPort: Int) =
        "$protocol $source:$sourcePort $destination:$destinationPort"

    private class LiveRow(
        val id: String,
        var app: String,
        var host: String,
        val protocol: String,
        val port: Int,
        var sent: Long = 0,
        var received: Long = 0
    )

    private class TcpFlow(
        val isn: Long,
        var expected: Long,
        var ourSeq: Long,
        var socket: Socket? = null,
        var open: Boolean = false,
        var synSent: Boolean = false,
        val pending: ArrayDeque<ByteArray> = ArrayDeque()
    )

    private class UdpFlow(val socket: DatagramSocket, var touched: Long = System.currentTimeMillis())
}
