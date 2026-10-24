package app.radiusrf.features.wifi.domain

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.SystemClock
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WifiScanner(
    private val context: Context,
    private val wifiManager: WifiManager,
    private val nic: WifiNicPreference,
    private val usb: UsbWifiSession
) {

    // Caller (WifiScreen) gates this behind PermissionGate for ACCESS_FINE_LOCATION before scanning.
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun scan(): Flow<List<WifiAccessPoint>> {
        val kind = nic.source.value.kind
        return if (kind == null) phoneScan() else adapterScan(kind)
    }

    private fun adapterScan(kind: com.emptyset.detector.radio.RadioKind): Flow<List<WifiAccessPoint>> = callbackFlow {
        val error = usb.open(kind)
        if (error != null) {
            close(IllegalStateException(error))
            return@callbackFlow
        }
        val seen = linkedMapOf<String, Pair<WifiAccessPoint, Long>>()
        val links = linkedMapOf<String, Triple<String, Int, Long>>()
        val listen = launch {
            usb.frames.collect { frame ->
                val now = System.currentTimeMillis()
                val link = WifiLink.parse(frame.bytes)
                if (link != null) {
                    synchronized(links) {
                        links[link.client.lowercase()] = Triple(link.accessPoint.lowercase(), frame.rssi ?: -100, now)
                        if (links.size > 400) {
                            val oldest = links.minByOrNull { it.value.third }?.key
                            if (oldest != null) links.remove(oldest)
                        }
                    }
                }
                val ap = BeaconFrame.parse(frame.bytes, frame.channel, frame.rssi) ?: return@collect
                synchronized(seen) {
                    seen[ap.bssid] = ap.copy(heardAt = now) to now
                    if (seen.size > 400) {
                        val oldest = seen.minByOrNull { it.value.second }?.key
                        if (oldest != null && oldest != ap.bssid) seen.remove(oldest)
                    }
                }
            }
        }
        val publish = launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val snapshot = synchronized(seen) {
                    val stale = seen.filterValues { now - it.second > 20_000L }.keys
                    stale.forEach { seen.remove(it) }
                    val liveLinks = synchronized(links) {
                        links.entries.groupBy { it.value.first }
                    }
                    seen.values.map { (ap, _) ->
                        val clients = liveLinks[ap.bssid.lowercase()].orEmpty().map { entry ->
                            WifiClient(entry.key, entry.value.second, "Client")
                        }
                        ap.copy(clients = clients)
                    }
                }
                trySend(snapshot.sortedByDescending { it.rssi })
                delay(300)
            }
        }
        val failed = launch {
            val message = usb.failure.filterNotNull().first()
            close(IllegalStateException(message))
        }
        awaitClose {
            listen.cancel()
            publish.cancel()
            failed.cancel()
            usb.close()
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun phoneScan(): Flow<List<WifiAccessPoint>> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val updated = intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) == true
                if (updated) trySend(phoneResults())
            }
        }

        context.registerReceiver(
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        )

        @Suppress("DEPRECATION")
        wifiManager.startScan()

        val refresh = launch {
            while (isActive) {
                delay(8_000)
                @Suppress("DEPRECATION")
                wifiManager.startScan()
            }
        }

        awaitClose {
            refresh.cancel()
            context.unregisterReceiver(receiver)
        }
    }

    @SuppressLint("MissingPermission")
    private fun phoneResults(): List<WifiAccessPoint> {
        val self = runCatching { wifiManager.connectionInfo }.getOrNull()
        val selfBssid = self?.bssid
        val selfRssi = self?.rssi ?: -100
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        return wifiManager.scanResults.map { result ->
            val clients = if (selfBssid != null && result.BSSID.equals(selfBssid, ignoreCase = true) && selfBssid != "02:00:00:00:00:00") {
                listOf(WifiClient("This phone", selfRssi, "Client"))
            } else {
                emptyList()
            }
            WifiAccessPoint(
                ssid = result.SSID.ifBlank { "<Hidden>" },
                bssid = result.BSSID,
                rssi = result.level,
                frequency = result.frequency,
                capabilities = result.capabilities,
                channelWidth = result.channelWidth,
                heardAt = nowWall - (nowElapsed - result.timestamp / 1_000L).coerceAtLeast(0L),
                clients = clients
            )
        }.sortedByDescending { it.rssi }
    }
}
