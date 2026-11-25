package app.radiusrf.features.connection_log.viewmodel

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import app.radiusrf.features.connection_log.domain.ConnectionLog
import app.radiusrf.features.connection_log.service.OwnTrafficService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

@HiltViewModel
class ConnectionLogViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: ConnectionLog
) : ViewModel() {
    val rows = log.rows
    val running = log.running
    val status = log.status

    /** Null when the phone has already allowed the local VPN. */
    fun consentIntent(): Intent? = VpnService.prepare(context)

    fun start() {
        ContextCompat.startForegroundService(context, Intent(context, OwnTrafficService::class.java))
    }

    fun stop() {
        context.startService(Intent(context, OwnTrafficService::class.java).setAction(OwnTrafficService.ACTION_STOP))
    }

    fun share(): Intent? {
        val file = log.pcap ?: return null
        if (!file.exists() || file.length() <= 24L) return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.tcpdump.pcap"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
