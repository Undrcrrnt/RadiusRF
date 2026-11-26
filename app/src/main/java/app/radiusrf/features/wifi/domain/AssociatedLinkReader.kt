package app.radiusrf.features.wifi.domain

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.SupplicantState
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** The Wi-Fi network this phone is joined to right now. */
data class AssociatedLink(
    val associated: Boolean,
    val ssid: String?,
    val bssid: String?,
    val rssi: Int?,
    val frequencyMhz: Int?
) {
    companion object {
        val None = AssociatedLink(false, null, null, null, null)
    }
}

/**
 * Reads the joined link from the system. This does not call [WifiManager.startScan],
 * so the analyzer's scan timer stays as it is.
 */
class AssociatedLinkReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wifiManager: WifiManager
) {
    fun read(): AssociatedLink = runCatching { readLink() }.getOrDefault(AssociatedLink.None)

    @Suppress("DEPRECATION")
    private fun readLink(): AssociatedLink {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) return AssociatedLink.None
        val fromTransport = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            caps.transportInfo as? WifiInfo
        } else {
            null
        }
        val info = fromTransport ?: wifiManager.connectionInfo ?: return AssociatedLink.None
        if (fromTransport == null && info.supplicantState != SupplicantState.COMPLETED) return AssociatedLink.None
        val bssid = info.bssid?.takeIf { it.isNotBlank() && !it.equals("02:00:00:00:00:00", ignoreCase = true) }
        return AssociatedLink(
            associated = true,
            ssid = ssidOf(info),
            bssid = bssid,
            rssi = info.rssi.takeIf { FloorWalkMath.usableRssi(it) },
            frequencyMhz = info.frequency.takeIf { it > 0 }
        )
    }

    private fun ssidOf(info: WifiInfo): String? {
        @Suppress("DEPRECATION")
        return FloorWalkMath.cleanSsid(info.ssid)
    }
}
