package app.radiusrf.features.wifi_direct.viewmodel

import android.content.BroadcastReceiver
import androidx.lifecycle.ViewModel
import app.radiusrf.features.wifi_direct.domain.WifiDirectManager
import app.radiusrf.features.wifi_direct.domain.WifiDirectState
import app.radiusrf.features.wifi_direct.domain.WifiDirectFileTransfer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class WifiDirectViewModel @Inject constructor(
    private val manager: WifiDirectManager,
    /** Sends and receives files over an established group. */
    val fileTransfer: WifiDirectFileTransfer
) : ViewModel() {

    val state: StateFlow<WifiDirectState> = manager.state

    private var receiver: BroadcastReceiver? = null

    fun initialize() {
        if (receiver != null) return
        manager.initialize()
        receiver = manager.registerReceiver()
    }

    fun startDiscovery() = manager.startDiscovery()
    fun pauseDiscovery() = manager.pauseDiscovery()
    fun resumeDiscovery() = manager.startDiscovery()
    fun stopDiscovery() = manager.stopDiscovery()
    fun connect(deviceAddress: String) = manager.connect(deviceAddress)
    fun disconnect() = manager.disconnect()

    override fun onCleared() {
        fileTransfer.cancel()
        manager.stopDiscovery()
        receiver?.let { manager.unregisterReceiver(it) }
        manager.cleanup()
    }
}
