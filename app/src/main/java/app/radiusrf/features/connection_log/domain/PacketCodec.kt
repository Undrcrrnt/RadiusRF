package app.radiusrf.features.connection_log.domain

/** IPv4, TCP, and UDP views plus the checksums a local forwarder has to write back. */
object PacketCodec {
    const val TCP = 6
    const val UDP = 17

    data class Ipv4(
        val headerLength: Int,
        val totalLength: Int,
        val protocol: Int,
        val source: ByteArray,
        val destination: ByteArray
    ) {
        val sourceText: String get() = dotted(source)
        val destinationText: String get() = dotted(destination)
    }

    data class Tcp(
        val sourcePort: Int,
        val destinationPort: Int,
        val sequence: Long,
        val acknowledgment: Long,
        val flags: Int,
        val payload: ByteArray
    )

    data class Udp(
        val sourcePort: Int,
        val destinationPort: Int,
        val payload: ByteArray
    )

    fun ipv4(packet: ByteArray, length: Int = packet.size): Ipv4? {
        if (length < 20 || packet.size < length) return null
        val version = packet[0].toInt() ushr 4
        val headerLength = (packet[0].toInt() and 0x0F) * 4
        if (version != 4 || headerLength < 20 || length < headerLength) return null
        val total = u16(packet, 2)
        if (total < headerLength || total > length) return null
        val fragment = u16(packet, 6) and 0x3FFF
        if (fragment != 0) return null
        return Ipv4(
            headerLength = headerLength,
            totalLength = total,
            protocol = packet[9].toInt() and 0xFF,
            source = packet.copyOfRange(12, 16),
            destination = packet.copyOfRange(16, 20)
        )
    }

    fun tcp(packet: ByteArray, ip: Ipv4): Tcp? {
        val offset = ip.headerLength
        if (ip.protocol != TCP || packet.size < offset + 20) return null
        val dataOffset = ((packet[offset + 12].toInt() ushr 4) and 0x0F) * 4
        if (dataOffset < 20 || offset + dataOffset > ip.totalLength) return null
        val payload = packet.copyOfRange(offset + dataOffset, ip.totalLength)
        return Tcp(
            sourcePort = u16(packet, offset),
            destinationPort = u16(packet, offset + 2),
            sequence = u32(packet, offset + 4),
            acknowledgment = u32(packet, offset + 8),
            flags = packet[offset + 13].toInt() and 0xFF,
            payload = payload
        )
    }

    fun udp(packet: ByteArray, ip: Ipv4): Udp? {
        val offset = ip.headerLength
        if (ip.protocol != UDP || ip.totalLength < offset + 8) return null
        val stated = u16(packet, offset + 4)
        val payloadEnd = if (stated >= 8) minOf(offset + stated, ip.totalLength) else ip.totalLength
        if (payloadEnd < offset + 8) return null
        return Udp(
            sourcePort = u16(packet, offset),
            destinationPort = u16(packet, offset + 2),
            payload = packet.copyOfRange(offset + 8, payloadEnd)
        )
    }

    fun tcpPacket(
        source: ByteArray,
        destination: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        sequence: Long,
        acknowledgment: Long,
        flags: Int,
        payload: ByteArray = ByteArray(0),
        mss: Int? = null
    ): ByteArray {
        val optionLength = if (mss != null) 4 else 0
        val tcpLength = 20 + optionLength + payload.size
        val packet = ByteArray(20 + tcpLength)
        writeIpv4(packet, 20 + tcpLength, TCP, source, destination)
        val tcp = 20
        put16(packet, tcp, sourcePort)
        put16(packet, tcp + 2, destinationPort)
        put32(packet, tcp + 4, sequence)
        put32(packet, tcp + 8, acknowledgment)
        packet[tcp + 12] = (((20 + optionLength) / 4) shl 4).toByte()
        packet[tcp + 13] = flags.toByte()
        put16(packet, tcp + 14, 65535)
        if (mss != null) {
            packet[tcp + 20] = 2
            packet[tcp + 21] = 4
            put16(packet, tcp + 22, mss)
        }
        payload.copyInto(packet, tcp + 20 + optionLength)
        val sum = checksum(packet, tcp, tcpLength, pseudo(source, destination, TCP, tcpLength))
        put16(packet, tcp + 16, sum)
        return packet
    }

    fun udpPacket(
        source: ByteArray,
        destination: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLength = 8 + payload.size
        val packet = ByteArray(20 + udpLength)
        writeIpv4(packet, 20 + udpLength, UDP, source, destination)
        put16(packet, 20, sourcePort)
        put16(packet, 22, destinationPort)
        put16(packet, 24, udpLength)
        payload.copyInto(packet, 28)
        var sum = checksum(packet, 20, udpLength, pseudo(source, destination, UDP, udpLength))
        if (sum == 0) sum = 0xFFFF
        put16(packet, 26, sum)
        return packet
    }

    /** Server name from a TLS ClientHello, when the bytes start at the record header. */
    fun serverName(payload: ByteArray): String? {
        if (payload.size < 44 || payload[0] != 0x16.toByte()) return null
        val recordLength = u16(payload, 3)
        if (payload.size < 5 + recordLength || payload[5] != 0x01.toByte()) return null
        var index = 43
        if (index >= payload.size) return null
        val session = payload[index].toInt() and 0xFF
        index += 1 + session
        if (index + 2 > payload.size) return null
        val ciphers = u16(payload, index)
        index += 2 + ciphers
        if (index + 1 > payload.size) return null
        val compression = payload[index].toInt() and 0xFF
        index += 1 + compression
        if (index + 2 > payload.size) return null
        val extensionsEnd = index + 2 + u16(payload, index)
        index += 2
        while (index + 4 <= minOf(extensionsEnd, payload.size)) {
            val type = u16(payload, index)
            val length = u16(payload, index + 2)
            val body = index + 4
            if (body + length > payload.size) return null
            if (type == 0 && length >= 5) {
                val nameLength = u16(payload, body + 3)
                val nameAt = body + 5
                if (payload[body + 2].toInt() == 0 && nameAt + nameLength <= payload.size) {
                    val name = payload.copyOfRange(nameAt, nameAt + nameLength).toString(Charsets.US_ASCII)
                    if (name.isNotBlank()) return name
                }
            }
            index = body + length
        }
        return null
    }

    /** IPv4 answers from a DNS response, keyed by dotted address. */
    fun dnsAnswers(payload: ByteArray): Map<String, String> {
        if (payload.size < 12) return emptyMap()
        val questions = u16(payload, 4)
        val answers = u16(payload, 6)
        var index = 12
        repeat(questions) {
            val question = readName(payload, index) ?: return emptyMap()
            index = question.second + 4
            if (index > payload.size) return emptyMap()
        }
        val found = LinkedHashMap<String, String>()
        repeat(answers) {
            val name = readName(payload, index) ?: return found
            index = name.second
            if (index + 10 > payload.size) return found
            val type = u16(payload, index)
            val dataLength = u16(payload, index + 8)
            val data = index + 10
            if (data + dataLength > payload.size) return found
            if (type == 1 && dataLength == 4) {
                found[dotted(payload.copyOfRange(data, data + 4))] = name.first.trimEnd('.')
            }
            index = data + dataLength
        }
        return found
    }

    fun dotted(address: ByteArray): String =
        address.joinToString(".") { (it.toInt() and 0xFF).toString() }

    private fun writeIpv4(packet: ByteArray, total: Int, protocol: Int, source: ByteArray, destination: ByteArray) {
        packet[0] = 0x45
        put16(packet, 2, total)
        packet[6] = 0x40
        packet[8] = 64
        packet[9] = protocol.toByte()
        source.copyInto(packet, 12)
        destination.copyInto(packet, 16)
        put16(packet, 10, checksum(packet, 0, 20, 0))
    }

    private fun pseudo(source: ByteArray, destination: ByteArray, protocol: Int, length: Int): Long {
        var sum = 0L
        sum += u16(source, 0).toLong() + u16(source, 2)
        sum += u16(destination, 0).toLong() + u16(destination, 2)
        sum += protocol.toLong() + length
        return sum
    }

    internal fun checksum(data: ByteArray, offset: Int, length: Int, seed: Long): Int {
        var sum = seed
        var index = 0
        var even = length
        if (even % 2 == 1) {
            sum += (data[offset + even - 1].toLong() and 0xFF) shl 8
            even -= 1
        }
        while (index < even) {
            sum += u16(data, offset + index).toLong()
            index += 2
        }
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun readName(data: ByteArray, start: Int): Pair<String, Int>? {
        val labels = ArrayList<String>()
        var index = start
        var end = start
        var jumped = false
        var hops = 0
        while (index < data.size && hops++ < 16) {
            val length = data[index].toInt() and 0xFF
            if (length == 0) {
                if (!jumped) end = index + 1
                return labels.joinToString(".") to end
            }
            if (length and 0xC0 == 0xC0) {
                if (index + 1 >= data.size) return null
                if (!jumped) end = index + 2
                index = ((length and 0x3F) shl 8) or (data[index + 1].toInt() and 0xFF)
                jumped = true
                continue
            }
            if (index + 1 + length > data.size) return null
            labels += data.copyOfRange(index + 1, index + 1 + length).toString(Charsets.US_ASCII)
            index += 1 + length
            if (!jumped) end = index
        }
        return null
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun u32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)

    private fun put16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value shr 8).toByte()
        data[offset + 1] = value.toByte()
    }

    private fun put32(data: ByteArray, offset: Int, value: Long) {
        data[offset] = (value shr 24).toByte()
        data[offset + 1] = (value shr 16).toByte()
        data[offset + 2] = (value shr 8).toByte()
        data[offset + 3] = value.toByte()
    }
}
