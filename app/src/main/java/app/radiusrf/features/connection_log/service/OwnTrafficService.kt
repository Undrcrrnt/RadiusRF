package app.radiusrf.features.connection_log.service

import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.ServiceCompat
import app.radiusrf.core.notify.ZdNotifier
import app.radiusrf.features.connection_log.domain.ConnectionLog
import app.radiusrf.features.connection_log.domain.PcapSink
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.inject.Inject

/**
 * A local VPN that forwards this phone's IPv4 and records each connection.
 * It does not block traffic and it does not decrypt TLS.
 */
@AndroidEntryPoint
class OwnTrafficService : VpnService() {

    @Inject lateinit var log: ConnectionLog
    @Inject lateinit var notifier: ZdNotifier

    private val gate = Any()
    private var relay: Ipv4Relay? = null
    private var tun: ParcelFileDescriptor? = null
    @Volatile private var stopping = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = notifier.connectionLog("Recording this phone's IPv4", stopIntent())
        startInForeground(notification)
        if (intent?.action == ACTION_STOP) {
            stopping = true
            finish("Off. Nothing is being recorded.")
            return START_NOT_STICKY
        }
        stopping = false
        synchronized(gate) {
            if (relay != null) return START_NOT_STICKY
        }
        startRelay()
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        finish("The phone closed the connection log.")
    }

    override fun onDestroy() {
        stopRelay()
        super.onDestroy()
    }

    private fun startRelay() {
        val builder = Builder()
            .setSession("RadiusRF connection log")
            .addAddress("10.251.1.1", 32)
            .addRoute("0.0.0.0", 0)
            .setMtu(1280)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        if (stopping) return
        val established = try {
            builder.establish()
        } catch (_: Exception) {
            null
        }
        if (stopping) {
            runCatching { established?.close() }
            return
        }
        if (established == null) {
            finish("Android did not start the log. Stop any other VPN and try again.")
            return
        }
        val file = File(cacheDir, "exports").apply { mkdirs() }.let { File(it, "connection-log.pcap") }
        val sink = PcapSink(file)
        val input = FileInputStream(established.fileDescriptor)
        val output = FileOutputStream(established.fileDescriptor)
        val created = Ipv4Relay(
            input = input,
            output = output,
            protectSocket = { socket -> protect(socket) },
            protectDatagram = { socket -> protect(socket) },
            appName = ::appName,
            pcap = sink,
            onUpdate = { rows, status ->
                log.publish(rows, true, status, file)
                runCatching { startInForeground(notifier.connectionLog(status, stopIntent())) }
            }
        )
        synchronized(gate) {
            tun = established
            relay = created
        }
        log.publish(emptyList(), true, "Recording this phone's IPv4 connections.", file)
        created.start()
    }

    private fun appName(protocol: Int, local: InetAddress, localPort: Int, remote: InetAddress, remotePort: Int): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "This phone"
        val uid = runCatching {
            val connectivity = getSystemService(ConnectivityManager::class.java)
            connectivity.getConnectionOwnerUid(
                protocol,
                InetSocketAddress(local, localPort),
                InetSocketAddress(remote, remotePort)
            )
        }.getOrDefault(-1)
        if (uid < 0) return "This phone"
        val packages = packageManager.getPackagesForUid(uid).orEmpty()
        val pkg = packages.firstOrNull { it != packageName } ?: packages.firstOrNull() ?: return "This phone"
        return runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault("This phone")
    }

    private fun finish(status: String) {
        val file = log.pcap
        val rows = log.rows.value
        stopRelay()
        log.publish(rows, false, status, file)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopRelay() {
        val current: Ipv4Relay?
        val descriptor: ParcelFileDescriptor?
        synchronized(gate) {
            current = relay
            descriptor = tun
            relay = null
            tun = null
        }
        current?.stop()
        runCatching { descriptor?.close() }
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
        }
    }

    private fun stopIntent() = android.app.PendingIntent.getService(
        this,
        7,
        Intent(this, OwnTrafficService::class.java).setAction(ACTION_STOP),
        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
    )

    companion object {
        const val ACTION_STOP = "app.radiusrf.connection_log.STOP"
        private const val NOTIFICATION_ID = 2003
    }
}
