package app.radiusrf.features.network_scanner.domain

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import kotlin.coroutines.resume

class LanAnnouncer(private val context: Context) {
    suspend fun discover(windowMs: Long = 6_000L): List<LanService> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("radiusrf-lan")
        lock?.setReferenceCounted(true)
        lock?.acquire()
        try {
            coroutineScope {
                val mdns = async { mdns() }
                val ssdp = async { ssdp(windowMs.coerceAtMost(2_500L)) }
                (mdns.await() + ssdp.await()).distinctBy { "${it.protocol}:${it.name}:${it.ip}:${it.port}" }
            }
        } finally {
            if (lock?.isHeld == true) lock.release()
        }
    }

    private suspend fun mdns(): List<LanService> {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return emptyList()
        val found = linkedMapOf<String, LanService>()
        var resolves = 0
        for (type in SERVICE_TYPES) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
                override fun onDiscoveryStarted(serviceType: String?) = Unit
                override fun onDiscoveryStopped(serviceType: String?) = Unit
                override fun onServiceLost(serviceInfo: NsdServiceInfo?) = Unit
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    val foundType = serviceInfo.serviceType.orEmpty()
                    val name = serviceInfo.serviceName.orEmpty()
                    found["$foundType:$name"] = LanService(
                        name = name.ifBlank { foundType },
                        kind = LanServiceClassifier.kind(foundType, name),
                        protocol = "mDNS",
                        host = name,
                        ip = null,
                        port = null,
                        detail = foundType
                    )
                    if (resolves++ > 12) return
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = Unit
                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            val key = "${resolved.serviceType}:${resolved.serviceName}"
                            found[key] = LanService(
                                name = resolved.serviceName,
                                kind = LanServiceClassifier.kind(resolved.serviceType.orEmpty(), resolved.serviceName.orEmpty()),
                                protocol = "mDNS",
                                host = resolved.host?.hostName,
                                ip = resolved.host?.hostAddress,
                                port = resolved.port,
                                detail = resolved.serviceType.orEmpty()
                            )
                        }
                    })
                }
            }
            runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
            delay(700)
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
        return found.values.toList()
    }

    private suspend fun ssdp(windowMs: Long): List<LanService> = withTimeoutOrNull(windowMs) {
        suspendCancellableCoroutine { cont ->
            val socket = MulticastSocket(1900)
            val group = InetAddress.getByName("239.255.255.250")
            socket.joinGroup(group)
            socket.soTimeout = 800
            val search = """
                M-SEARCH * HTTP/1.1
                HOST: 239.255.255.250:1900
                MAN: "ssdp:discover"
                MX: 2
                ST: ssdp:all

            """.trimIndent().replace("\n", "\r\n")
            socket.send(DatagramPacket(search.toByteArray(), search.length, group, 1900))
            val found = linkedMapOf<String, LanService>()
            val buf = ByteArray(2048)
            val deadline = System.currentTimeMillis() + windowMs
            try {
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buf, buf.size)
                    runCatching { socket.receive(packet) }.onSuccess {
                        val text = String(packet.data, 0, packet.length)
                        SsdpParser.parse(text)?.let { service ->
                            found[service.detail.ifBlank { service.name }] = service.copy(
                                ip = service.ip ?: packet.address?.hostAddress
                            )
                        }
                    }
                }
            } finally {
                runCatching { socket.leaveGroup(group) }
                socket.close()
            }
            if (cont.isActive) cont.resume(found.values.toList())
        }
    } ?: emptyList()

    companion object {
        private val SERVICE_TYPES = arrayOf(
            "_googlecast._tcp.",
            "_ipp._tcp.",
            "_hap._tcp.",
            "_airplay._tcp.",
            "_rtsp._tcp.",
            "_androidtvremote2._tcp."
        )
    }
}
